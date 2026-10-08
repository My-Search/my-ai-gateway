package relay

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/my-search/my-ai-gateway/internal/relay/logsvc"
)

// cancelFixture wires an entry model with several failover candidates pointing at
// one upstream that accepts the connection but never sends a first byte, so the
// request hangs until the test cancels it. It records how many candidates were
// actually dispatched, which the fix must hold at one.
type cancelFixture struct {
	core     *RelayCore
	store    DataStore
	upstream *httptest.Server

	mu         sync.Mutex
	dispatched int
	// release unblocks in-flight handler goroutines at teardown so
	// httptest.Server.Close() cannot deadlock on a hung handler.
	release    chan struct{}
	releaseOne sync.Once
	// firstDispatch is closed once the first upstream connection arrives.
	firstDispatch chan struct{}
	once          sync.Once
}

func (f *cancelFixture) closeRelease() { f.releaseOne.Do(func() { close(f.release) }) }

// waitFirstDispatch blocks until a candidate has actually reached the upstream.
func (f *cancelFixture) waitFirstDispatch() { <-f.firstDispatch }

func (f *cancelFixture) dispatchedCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.dispatched
}

// setupCancelFixture builds three failover candidates over a hanging upstream.
func setupCancelFixture(t *testing.T) *cancelFixture {
	t.Helper()
	st := newGroupTestStore(t)
	core := NewRelayCore(nil)
	core.Store = st
	core.RouteResolver = NewRouteResolver(st)
	core.LogWriter = logsvc.NewLogWriter(st, nil, nil)

	f := &cancelFixture{core: core, store: st, release: make(chan struct{}), firstDispatch: make(chan struct{})}

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.dispatched++
		f.mu.Unlock()
		f.once.Do(func() { close(f.firstDispatch) })
		// Hang like an upstream that accepts the connection but never responds.
		// Also unblock on release so teardown can never deadlock.
		select {
		case <-r.Context().Done():
		case <-f.release:
		}
	}))
	// Register Close first so the release cleanup (registered below) runs before it.
	t.Cleanup(upstream.Close)
	t.Cleanup(f.closeRelease)
	f.upstream = upstream

	ctx := context.Background()
	entryID, err := st.Insert(ctx, "INSERT INTO models (model_name, strategy, enabled) VALUES ('entry','failover',1)")
	if err != nil {
		t.Fatal(err)
	}
	for i, name := range []string{"m-a", "m-b", "m-c"} {
		chID, err := st.Insert(ctx,
			"INSERT INTO channels (name, channel_type, base_url, enabled) VALUES (?, 'openai', ?, 1)",
			"ch-"+name, upstream.URL)
		if err != nil {
			t.Fatal(err)
		}
		keyID, err := st.Insert(ctx,
			"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, 'k', 'sk', 1, 0)", chID)
		if err != nil {
			t.Fatal(err)
		}
		cmID, err := st.Insert(ctx,
			"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, ?, 1, 'text', 200000, ?)",
			chID, name, keyID)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := st.Exec(ctx,
			"INSERT INTO model_channel_rels (model_id, channel_model_id, sort_order, enabled) VALUES (?, ?, ?, 1)",
			entryID, cmID, i); err != nil {
			t.Fatal(err)
		}
	}
	return f
}

// phaseCounts returns how many request_log rows carry each observed phase.
func (f *cancelFixture) phaseCounts(t *testing.T) map[string]int {
	t.Helper()
	rows, err := f.store.Query(context.Background(), "SELECT phase FROM request_logs")
	if err != nil {
		t.Fatal(err)
	}
	counts := map[string]int{}
	for _, r := range rows {
		counts[r.Str("phase")]++
	}
	return counts
}

// TestCancelStopsCandidateLoop proves a canceled parent context aborts the routing
// loop after the in-flight candidate instead of cascading through every remaining
// member — the bug that made one interrupted request look like a full group outage.
func TestCancelStopsCandidateLoop(t *testing.T) {
	f := setupCancelFixture(t)

	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}]}`
	req, err := ParseRequest(body, ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		f.waitFirstDispatch()
		cancel()
	}()

	res := f.core.RelayNonStream(ctx, req, "Bearer gw-key", "", body)
	cancel()

	if !res.Interrupted {
		t.Errorf("expected Interrupted=true, got %+v", res)
	}
	if res.StatusCode != StatusClientClosedRequest {
		t.Errorf("status = %d, want %d", res.StatusCode, StatusClientClosedRequest)
	}
	if got := f.dispatchedCount(); got != 1 {
		t.Errorf("upstream dispatched %d candidates, want exactly 1 (no cascade)", got)
	}
}

// TestCancelDoesNotTripBreakerOrLogFailure proves the cancellation is neither
// counted as a candidate failure (which would trip the breaker) nor written as a
// "fail" row that reads as an upstream outage.
func TestCancelDoesNotTripBreakerOrLogFailure(t *testing.T) {
	f := setupCancelFixture(t)

	var trips int
	f.core.CircuitTripFn = func(context.Context, int64, int64, int64, *int64, string) { trips++ }

	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}]}`
	req, _ := ParseRequest(body, ProtoOpenAI)

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		f.waitFirstDispatch()
		cancel()
	}()
	f.core.RelayNonStream(ctx, req, "Bearer gw-key", "", body)
	cancel()

	if trips != 0 {
		t.Errorf("breaker tripped %d times on cancellation, want 0", trips)
	}
	counts := f.phaseCounts(t)
	if counts["fail"] != 0 {
		t.Errorf("wrote %d 'fail' rows on cancellation, want 0 (it is not an upstream fault)", counts["fail"])
	}
	if counts["retry"] != 0 {
		t.Errorf("wrote %d 'retry' rows on cancellation, want 0 (retrying is pointless)", counts["retry"])
	}
	if counts["interrupted"] != 1 {
		t.Errorf("wrote %d 'interrupted' rows, want exactly 1", counts["interrupted"])
	}
}

// TestCancelDoesNotPolluteLatencyWindow proves a canceled attempt never records
// its configured timeout as a first-byte sample: doing so drove the adaptive
// timeout (avg*3) to the 60s ceiling for every member in the group.
func TestCancelDoesNotPolluteLatencyWindow(t *testing.T) {
	f := setupCancelFixture(t)
	f.core.LatencyTracker = NewLatencyTrackerFixed(DefaultMinTimeoutMs, DefaultMaxTimeoutMs)

	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}]}`
	req, _ := ParseRequest(body, ProtoOpenAI)

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		f.waitFirstDispatch()
		cancel()
	}()
	f.core.RelayNonStream(ctx, req, "Bearer gw-key", "", body)
	cancel()

	for _, cand := range f.core.RouteResolver.BuildCandidates(context.Background(), req) {
		f.core.LatencyTracker.mu.Lock()
		n := len(f.core.LatencyTracker.samples[formatKey(cand.ChannelID, cand.ChannelModelID)])
		f.core.LatencyTracker.mu.Unlock()
		if n != 0 {
			t.Errorf("candidate %s recorded %d latency samples on cancellation, want 0", cand.ModelName, n)
		}
	}
}

// TestUpstreamFaultStillFailsAndTripsBreaker is the counterweight: a genuine
// upstream failure (here a 500) must still be retried, logged as a failure, and
// trip the breaker. Without this, the cancellation fix could silently swallow
// real outages.
func TestUpstreamFaultStillFailsAndTripsBreaker(t *testing.T) {
	st := newGroupTestStore(t)
	core := NewRelayCore(nil)
	core.Store = st
	core.RouteResolver = NewRouteResolver(st)
	core.LogWriter = logsvc.NewLogWriter(st, nil, nil)

	var trips int
	core.CircuitTripFn = func(context.Context, int64, int64, int64, *int64, string) { trips++ }

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":"upstream exploded"}`))
	}))
	t.Cleanup(upstream.Close)

	ctx := context.Background()
	entryID, err := st.Insert(ctx, "INSERT INTO models (model_name, strategy, enabled) VALUES ('entry','failover',1)")
	if err != nil {
		t.Fatal(err)
	}
	chID, err := st.Insert(ctx,
		"INSERT INTO channels (name, channel_type, base_url, enabled) VALUES ('ch','openai',?,1)", upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	keyID, err := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, 'k', 'sk', 1, 0)", chID)
	if err != nil {
		t.Fatal(err)
	}
	cmID, err := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, 'm', 1, 'text', 200000, ?)",
		chID, keyID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.Exec(ctx,
		"INSERT INTO model_channel_rels (model_id, channel_model_id, sort_order, enabled) VALUES (?, ?, 0, 1)",
		entryID, cmID); err != nil {
		t.Fatal(err)
	}

	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}]}`
	req, _ := ParseRequest(body, ProtoOpenAI)
	res := core.RelayNonStream(context.Background(), req, "Bearer gw-key", "", body)

	if res.StatusCode != 503 {
		t.Errorf("status = %d, want 503 for a real upstream failure", res.StatusCode)
	}
	if res.Interrupted {
		t.Error("a 500 must not be reported as interrupted")
	}
	if trips == 0 {
		t.Error("a real upstream failure must still trip the breaker")
	}

	rows, _ := st.Query(context.Background(), "SELECT phase FROM request_logs")
	var fails, retries int
	for _, r := range rows {
		switch r.Str("phase") {
		case "fail":
			fails++
		case "retry":
			retries++
		}
	}
	if fails != 1 {
		t.Errorf("'fail' rows = %d, want 1", fails)
	}
	if retries == 0 {
		t.Errorf("a real failure must still record retries, got %d", retries)
	}
}

// TestDeadlineIsTimeoutNotInterruption locks in the boundary between the two:
// an expired deadline (e.g. the global 600s ceiling, or a caller-imposed timeout)
// is a genuine timeout, so it must still be logged and reported as a failure
// rather than disguised as a caller interruption.
func TestDeadlineIsTimeoutNotInterruption(t *testing.T) {
	f := setupCancelFixture(t)

	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}]}`
	req, _ := ParseRequest(body, ProtoOpenAI)

	ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
	defer cancel()

	res := f.core.RelayNonStream(ctx, req, "Bearer gw-key", "", body)

	if res.Interrupted {
		t.Error("a deadline must not be reported as an interruption")
	}
	if res.StatusCode != 503 {
		t.Errorf("status = %d, want 503 for a timeout", res.StatusCode)
	}
	counts := f.phaseCounts(t)
	if counts["interrupted"] != 0 {
		t.Errorf("wrote %d 'interrupted' rows for a deadline, want 0", counts["interrupted"])
	}
	if counts["fail"] != 1 {
		t.Errorf("wrote %d 'fail' rows for a deadline, want 1", counts["fail"])
	}
}

// TestIsCanceledErrorClassification pins the classification boundary: only a
// canceled parent context is an interruption. A per-attempt deadline stays a
// timeout, and an upstream reset stays an upstream fault.
func TestIsCanceledErrorClassification(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"bare canceled", context.Canceled, true},
		{"deadline", context.DeadlineExceeded, false},
		{"first byte timeout", newFirstByteTimeout(1000), false},
		{"empty response", newEmptyResponseTimeout(), false},
		{"upstream eof", io.EOF, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			// net/http yields a *url.Error wrapping the sentinel; mimic that.
			var wrapped error
			if tc.err != nil {
				wrapped = &wrappedErr{msg: "Post \"https://ac.example/v1/chat/completions\": " + tc.err.Error(), err: tc.err}
			}
			if got := isCanceledError(wrapped); got != tc.want {
				t.Errorf("isCanceledError = %v, want %v", got, tc.want)
			}
		})
	}
}

// wrappedErr mimics net/http's *url.Error: a message plus a wrapped sentinel.
type wrappedErr struct {
	msg string
	err error
}

func (e *wrappedErr) Error() string { return e.msg }
func (e *wrappedErr) Unwrap() error { return e.err }

// TestCanceledStreamStopsImmediately covers the streaming loop: a cancellation
// must end the stream without cascading to the remaining members.
func TestCanceledStreamStopsImmediately(t *testing.T) {
	f := setupCancelFixture(t)

	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}],"stream":true}`
	req, err := ParseRequest(body, ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		f.waitFirstDispatch()
		cancel()
	}()

	var errs int
	f.core.RelayStream(ctx, req, "Bearer gw-key", "", body, false, StreamSink{
		OnEvent: func(string, string) {},
		OnError: func(error) { errs++ },
	})
	cancel()

	if got := f.dispatchedCount(); got != 1 {
		t.Errorf("stream dispatched %d candidates, want exactly 1 (no cascade)", got)
	}
	counts := f.phaseCounts(t)
	if counts["fail"] != 0 {
		t.Errorf("stream wrote %d 'fail' rows, want 0", counts["fail"])
	}
	if counts["interrupted"] != 1 {
		t.Errorf("stream wrote %d 'interrupted' rows, want 1", counts["interrupted"])
	}
	// The client is gone, so no error event should be emitted for the interruption.
	if errs != 0 {
		t.Errorf("emitted %d error events to a dead stream, want 0", errs)
	}
}
