package relay_test

import (
	"context"
	"database/sql"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/my-search/my-ai-gateway/internal/relay"
	"github.com/my-search/my-ai-gateway/internal/relay/circuit"
	"github.com/my-search/my-ai-gateway/internal/relay/logsvc"
	"github.com/my-search/my-ai-gateway/internal/store"

	_ "modernc.org/sqlite"
)

// timeoutStore builds a store carrying the real breaker schema so a genuine
// circuit.Trigger can persist a trip. It lives in the external relay_test
// package because circuit imports relay (an in-package test would be a cycle).
func timeoutStore(t *testing.T) *store.Store {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=busy_timeout(30000)")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	for _, q := range []string{
		`CREATE TABLE models (id INTEGER PRIMARY KEY AUTOINCREMENT, model_name TEXT UNIQUE NOT NULL,
			strategy TEXT DEFAULT 'failover', enabled INTEGER DEFAULT 1, hidden INTEGER DEFAULT 0,
			rel_mode TEXT DEFAULT 'self_add', inherit_from_model_id INTEGER,
			image_invalidate_count INTEGER DEFAULT 0, video_invalidate_count INTEGER DEFAULT 0,
			audio_invalidate_count INTEGER DEFAULT 0, force_override_reasoning_effort INTEGER DEFAULT 0,
			created_at TEXT, updated_at TEXT)`,
		`CREATE TABLE model_channel_rels (id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER,
			channel_model_id INTEGER, weight INTEGER DEFAULT 1, enabled INTEGER DEFAULT 1,
			sort_order INTEGER DEFAULT 0, reasoning_effort TEXT, created_at TEXT)`,
		`CREATE TABLE channel_models (id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER,
			model_name TEXT, display_name TEXT, enabled INTEGER DEFAULT 1, source TEXT,
			input TEXT DEFAULT 'text', context_length INTEGER, channel_api_key_id INTEGER,
			last_used_at TEXT, created_at TEXT)`,
		`CREATE TABLE channels (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, channel_type TEXT,
			base_url TEXT, enabled INTEGER DEFAULT 1, custom_headers TEXT)`,
		`CREATE TABLE channel_api_keys (id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER,
			key_name TEXT, api_key TEXT, enabled INTEGER DEFAULT 1, sort_order INTEGER DEFAULT 0)`,
		`CREATE TABLE api_keys (id INTEGER PRIMARY KEY AUTOINCREMENT, key_value TEXT, key_name TEXT,
			enabled INTEGER DEFAULT 1)`,
		`CREATE TABLE circuit_breaker_configs (id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER,
			retry_count INTEGER DEFAULT 3, circuit_break_duration INTEGER DEFAULT 60,
			circuit_break_scope TEXT DEFAULT 'model', enabled INTEGER DEFAULT 1,
			created_at TEXT, updated_at TEXT)`,
		`CREATE TABLE circuit_breaker_states (id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER,
			channel_api_key_id INTEGER, channel_model_id INTEGER, protocol TEXT, is_open INTEGER DEFAULT 0,
			fail_count INTEGER DEFAULT 0, opened_at TEXT, expire_at TEXT, created_at TEXT, updated_at TEXT,
			last_probe_at TEXT, last_probe_status INTEGER, last_probe_detail TEXT)`,
		`CREATE TABLE request_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, trace_id TEXT,
			api_key_name TEXT, gateway_api_key_id INTEGER, model_name TEXT DEFAULT '',
			channel_model_name TEXT DEFAULT '', channel_name TEXT DEFAULT '', phase TEXT,
			status TEXT DEFAULT 'pending', message TEXT DEFAULT '', retry_index INTEGER DEFAULT 0,
			response_time_ms INTEGER, first_byte_ms INTEGER, prompt_tokens INTEGER DEFAULT 0,
			completion_tokens INTEGER DEFAULT 0, total_tokens INTEGER DEFAULT 0,
			reasoning_effort TEXT DEFAULT '', request_headers TEXT, request_body TEXT,
			route_source TEXT, protocol TEXT, created_at TEXT)`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatalf("schema: %v", err)
		}
	}
	if _, err := db.Exec("INSERT INTO api_keys (key_value, key_name) VALUES ('gw-key','t')"); err != nil {
		t.Fatal(err)
	}
	return store.New(db, nil)
}

// TestStreamHeartbeatWithoutContentTimesOut reproduces the "request sits there
// for minutes and nothing happens" report: the upstream accepts the stream and
// then only ever sends SSE heartbeat comments. Each comment is a read, so the
// idle timer keeps resetting and never fires — the request would hang for the
// whole 600s parent budget. The no-progress timer must cut it short instead,
// and the resulting timeout must still isolate the candidate.
func TestStreamHeartbeatWithoutContentTimesOut(t *testing.T) {
	st := timeoutStore(t)
	core := relay.NewRelayCore(st)
	core.RouteResolver = relay.NewRouteResolver(st)
	core.LogWriter = logsvc.NewLogWriter(st, nil, nil)
	// Generous first-byte / idle timeouts: the heartbeats arrive fast enough
	// that neither can fire, so only the no-progress timer can end the request.
	core.LatencyTracker = relay.NewLatencyTrackerFixed(60000, 60000)
	core.StreamIdleTimeoutMs = 60000
	core.StreamNoProgressTimeoutMs = 60

	trigger := &circuit.Trigger{Store: st, ConfigMgr: &circuit.ConfigManager{Store: st}}
	core.CircuitTripFn = func(ctx context.Context, modelID, channelID, channelModelID int64, apiKeyID *int64, protocol string) {
		trigger.TriggerBreak(ctx, modelID, channelID, apiKeyID, channelModelID, protocol)
	}

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		flusher, _ := w.(http.Flusher)
		ticker := time.NewTicker(10 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-r.Context().Done():
				return
			case <-ticker.C:
				// A bare SSE comment: bytes on the wire, but no event data.
				_, _ = io.WriteString(w, ": keep-alive\n\n")
				if flusher != nil {
					flusher.Flush()
				}
			}
		}
	}))
	t.Cleanup(upstream.Close)

	ctx := context.Background()
	entryID, _ := st.Insert(ctx, "INSERT INTO models (model_name, strategy, enabled) VALUES ('entry','failover',1)")
	chID, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, base_url, enabled) VALUES ('ch','openai',?,1)", upstream.URL)
	keyID, _ := st.Insert(ctx, "INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, 'k','sk',1,0)", chID)
	cmID, _ := st.Insert(ctx, "INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, 'm',1,'text',200000,?)", chID, keyID)
	_, _ = st.Exec(ctx, "INSERT INTO model_channel_rels (model_id, channel_model_id, sort_order, enabled) VALUES (?,?,0,1)", entryID, cmID)

	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}],"stream":true}`
	req, err := relay.ParseRequest(body, relay.ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}

	done := make(chan struct{})
	go func() {
		core.RelayStream(ctx, req, "Bearer gw-key", "", body, false, relay.StreamSink{
			OnEvent: func(string, string) {},
			OnError: func(error) {},
		})
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("RelayStream did not return: heartbeats kept the idle timer alive (no-progress timeout missing)")
	}

	rows, _ := st.Query(ctx, "SELECT * FROM circuit_breaker_states WHERE is_open = 1")
	if len(rows) != 1 {
		t.Fatalf("open breaker rows = %d, want 1 (a no-progress stall must isolate the candidate)", len(rows))
	}
	if got := rows[0].I64("channel_id", 0); got != chID {
		t.Errorf("channel_id = %d, want %d", got, chID)
	}
	if got := rows[0].I64("channel_model_id", 0); got != cmID {
		t.Errorf("channel_model_id = %d, want %d", got, cmID)
	}
}

// TestFirstByteTimeoutTripsBreakerEndToEnd proves the full chain the user asked
// about: a per-attempt first-byte timeout (LatencyTracker avg*3 clamped to the
// configured max) must not only be classified as a failure but actually persist
// an open breaker row through the REAL circuit.Trigger. It uses the stream path
// because the non-stream attempt timeout has a 30s floor that would make the
// test slow; the classification is identical.
func TestFirstByteTimeoutTripsBreakerEndToEnd(t *testing.T) {
	st := timeoutStore(t)
	core := relay.NewRelayCore(st)
	core.RouteResolver = relay.NewRouteResolver(st)
	core.LogWriter = logsvc.NewLogWriter(st, nil, nil)
	// 1ms first-byte timeout: the upstream hangs, so the attempt times out fast.
	core.LatencyTracker = relay.NewLatencyTrackerFixed(1, 1)

	trigger := &circuit.Trigger{Store: st, ConfigMgr: &circuit.ConfigManager{Store: st}}
	core.CircuitTripFn = func(ctx context.Context, modelID, channelID, channelModelID int64, apiKeyID *int64, protocol string) {
		trigger.TriggerBreak(ctx, modelID, channelID, apiKeyID, channelModelID, protocol)
	}

	release := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.ReadAll(r.Body)
		select { // accept the connection but never answer: forces a first-byte timeout
		case <-r.Context().Done():
		case <-release:
		}
	}))
	t.Cleanup(func() { close(release); upstream.Close() })

	ctx := context.Background()
	entryID, _ := st.Insert(ctx, "INSERT INTO models (model_name, strategy, enabled) VALUES ('entry','failover',1)")
	chID, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, base_url, enabled) VALUES ('ch','openai',?,1)", upstream.URL)
	keyID, _ := st.Insert(ctx, "INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, 'k','sk',1,0)", chID)
	cmID, _ := st.Insert(ctx, "INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, 'm',1,'text',200000,?)", chID, keyID)
	_, _ = st.Exec(ctx, "INSERT INTO model_channel_rels (model_id, channel_model_id, sort_order, enabled) VALUES (?,?,0,1)", entryID, cmID)

	body := `{"model":"entry","messages":[{"role":"user","content":"hi"}],"stream":true}`
	req, err := relay.ParseRequest(body, relay.ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}
	core.RelayStream(ctx, req, "Bearer gw-key", "", body, false, relay.StreamSink{
		OnEvent: func(string, string) {},
		OnError: func(error) {},
	})

	rows, _ := st.Query(ctx, "SELECT * FROM circuit_breaker_states WHERE is_open = 1")
	if len(rows) != 1 {
		t.Fatalf("open breaker rows = %d, want 1 (a first-byte timeout must isolate the candidate)", len(rows))
	}
	if got := rows[0].I64("channel_id", 0); got != chID {
		t.Errorf("channel_id = %d, want %d", got, chID)
	}
	if got := rows[0].I64("channel_model_id", 0); got != cmID {
		t.Errorf("channel_model_id = %d, want %d", got, cmID)
	}
}
