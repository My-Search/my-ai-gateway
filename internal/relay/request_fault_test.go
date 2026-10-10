package relay

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/my-search/my-ai-gateway/internal/relay/logsvc"
)

// requestFaultFixture wires an entry model with three failover candidates over
// one upstream: m-a rejects with a configurable status, m-b and m-c serve 200.
// Dispatches are counted per upstream model so a test can distinguish "skipped
// after one attempt" from "retried until exhausted".
type requestFaultFixture struct {
	core  *RelayCore
	store DataStore

	mu         sync.Mutex
	dispatched map[string]int
}

func (f *requestFaultFixture) dispatchedCount(model string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.dispatched[model]
}

// setupRequestFaultFixture registers faultStatus on the first candidate (m-a)
// and leaves the rest healthy.
func setupRequestFaultFixture(t *testing.T, faultStatus int) *requestFaultFixture {
	t.Helper()
	st := newGroupTestStore(t)
	core := NewRelayCore(nil)
	core.Store = st
	core.RouteResolver = NewRouteResolver(st)
	core.LogWriter = logsvc.NewLogWriter(st, nil, nil)

	f := &requestFaultFixture{core: core, store: st, dispatched: map[string]int{}}

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var parsed struct {
			Model string `json:"model"`
		}
		_ = json.Unmarshal(raw, &parsed)

		f.mu.Lock()
		f.dispatched[parsed.Model]++
		f.mu.Unlock()

		if parsed.Model == "m-a" {
			w.WriteHeader(faultStatus)
			_, _ = w.Write([]byte(`{"error":{"message":"rejected by upstream","type":"invalid_request_error"}}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"1","choices":[{"message":{"role":"assistant","content":"ok"}}],` +
			`"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}`))
	}))
	t.Cleanup(upstream.Close)

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

func (f *requestFaultFixture) serve(t *testing.T) RelayResult {
	t.Helper()
	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}]}`
	req, err := ParseRequest(body, ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}
	return f.core.RelayNonStream(context.Background(), req, "Bearer gw-key", "", body)
}

// TestRequestFaultStatusesSkipWithoutTrippingBreaker pins the classification
// contract: 400/413/422 blame the request, not the model service, so the
// candidate gets exactly one attempt — no retry, no breaker trip — and routing
// continues to the next member.
func TestRequestFaultStatusesSkipWithoutTrippingBreaker(t *testing.T) {
	for _, status := range []int{400, 413, 422} {
		t.Run(fmt.Sprintf("status-%d", status), func(t *testing.T) {
			f := setupRequestFaultFixture(t, status)

			var trips int
			f.core.CircuitTripFn = func(context.Context, int64, int64, int64, *int64, string) { trips++ }

			res := f.serve(t)

			if res.StatusCode != 200 {
				t.Errorf("status = %d, want 200 (routing must continue); body %s", res.StatusCode, res.Body)
			}
			if trips != 0 {
				t.Errorf("breaker tripped %d times on %d, want 0", trips, status)
			}
			if got := f.dispatchedCount("m-a"); got != 1 {
				t.Errorf("m-a dispatched %d times, want exactly 1 (%d must not be retried)", got, status)
			}
			if got := f.dispatchedCount("m-b"); got != 1 {
				t.Errorf("m-b dispatched %d times, want 1 (the next member must serve the request)", got)
			}
		})
	}
}

// TestAuthFailureStillTripsBreaker locks the other side of the classification:
// 401 means the key we configured is wrong, so every request through that
// candidate fails until the configuration is fixed — the breaker must still
// engage once the retries are exhausted.
func TestAuthFailureStillTripsBreaker(t *testing.T) {
	f := setupRequestFaultFixture(t, http.StatusUnauthorized)

	var trips int
	f.core.CircuitTripFn = func(context.Context, int64, int64, int64, *int64, string) { trips++ }

	res := f.serve(t)

	if res.StatusCode != 200 {
		t.Errorf("status = %d, want 200 (failover must continue); body %s", res.StatusCode, res.Body)
	}
	if trips != 1 {
		t.Errorf("breaker tripped %d times on 401, want 1", trips)
	}
	if got := f.dispatchedCount("m-a"); got <= 1 {
		t.Errorf("m-a dispatched %d times, want retries before the breaker trips", got)
	}
}
