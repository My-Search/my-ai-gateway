package relay

import (
	"context"
	"errors"
	"testing"
	"time"
)

// TestParentBudgetExpiryDoesNotTripBreaker pins the fix for a mis-attribution:
// when the request's parent budget (the global 600s ceiling, or a caller
// deadline) expires *while* a candidate attempt is in flight, the resulting
// context.DeadlineExceeded used to look like an ordinary candidate failure and
// tripped the breaker for a candidate that never got a fair chance to answer.
//
// The candidate is attempted against an upstream that hangs, with a parent
// deadline far below the 30s per-attempt floor, so the parent — not the
// candidate — is what expires.
func TestParentBudgetExpiryDoesNotTripBreaker(t *testing.T) {
	f := setupCancelFixture(t)

	var trips int
	f.core.CircuitTripFn = func(context.Context, int64, int64, int64, *int64, string) { trips++ }

	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}]}`
	req, err := ParseRequest(body, ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()

	res := f.core.RelayNonStream(ctx, req, "Bearer gw-key", "", body)

	if trips != 0 {
		t.Errorf("breaker tripped %d times on parent-budget expiry, want 0", trips)
	}
	if res.Interrupted {
		t.Error("a parent-budget expiry must not be reported as a client interruption")
	}
	if res.StatusCode != 503 {
		t.Errorf("status = %d, want 503 (plain timeout, not 499)", res.StatusCode)
	}
	if got := f.dispatchedCount(); got != 1 {
		t.Errorf("upstream dispatched %d candidates, want exactly 1 (no cascade after expiry)", got)
	}

	counts := f.phaseCounts(t)
	if counts["retry"] != 0 {
		t.Errorf("wrote %d 'retry' rows after budget expiry, want 0 (retrying is pointless)", counts["retry"])
	}
	if counts["fail"] != 1 {
		t.Errorf("wrote %d 'fail' rows, want exactly 1", counts["fail"])
	}

	// The expired attempt must not be recorded as a first-byte sample either:
	// doing so would poison the adaptive timeout with the configured value.
	for _, cand := range f.core.RouteResolver.BuildCandidates(context.Background(), req) {
		f.core.LatencyTracker.mu.Lock()
		n := len(f.core.LatencyTracker.samples[formatKey(cand.ChannelID, cand.ChannelModelID)])
		f.core.LatencyTracker.mu.Unlock()
		if n != 0 {
			t.Errorf("candidate %s recorded %d latency samples on budget expiry, want 0", cand.ModelName, n)
		}
	}
}

// TestIsParentBudgetError pins the discriminator that separates "our budget ran
// out" from "the candidate timed out". The integration counterweight (a real
// upstream fault still trips the breaker) lives in
// TestUpstreamFaultStillFailsAndTripsBreaker; this unit test pins the exact
// predicate, which an integration test cannot reach cheaply because the
// per-attempt timeout floor is 30s.
func TestIsParentBudgetError(t *testing.T) {
	live, cancelLive := context.WithCancel(context.Background())
	defer cancelLive()

	expired, cancelExpired := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer cancelExpired()

	canceled, cancelCanceled := context.WithCancel(context.Background())
	cancelCanceled()

	deadlineErr := context.DeadlineExceeded
	wrappedDeadline := errors.New("Post \"http://x\": context deadline exceeded")

	tests := []struct {
		name string
		ctx  context.Context
		err  error
		want bool
	}{
		{"parent live, per-attempt deadline", live, deadlineErr, false},
		{"parent live, wrapped deadline", live, wrappedDeadline, false},
		{"parent expired, deadline", expired, deadlineErr, true},
		{"parent canceled, deadline err", canceled, deadlineErr, false},
		{"parent expired, unrelated err", expired, errors.New("connection reset"), false},
		{"nil ctx", nil, deadlineErr, false},
		{"nil err", expired, nil, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := isParentBudgetError(tc.ctx, tc.err); got != tc.want {
				t.Errorf("isParentBudgetError = %v, want %v", got, tc.want)
			}
		})
	}
}
