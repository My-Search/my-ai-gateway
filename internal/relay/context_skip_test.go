package relay

import (
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/my-search/my-ai-gateway/internal/store"

	_ "modernc.org/sqlite"
)

// newRelayTestStore builds the minimal schema the relay routing loop touches.
func newRelayTestStore(t *testing.T) DataStore {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=busy_timeout(30000)")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })

	schema := []string{
		`CREATE TABLE models (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			model_name TEXT UNIQUE NOT NULL,
			strategy TEXT DEFAULT 'failover',
			enabled INTEGER DEFAULT 1,
			hidden INTEGER DEFAULT 0,
			rel_mode TEXT DEFAULT 'self_add',
			inherit_from_model_id INTEGER,
			image_invalidate_count INTEGER DEFAULT 0,
			video_invalidate_count INTEGER DEFAULT 0,
			audio_invalidate_count INTEGER DEFAULT 0,
			force_override_reasoning_effort INTEGER DEFAULT 0,
			created_at TEXT, updated_at TEXT)`,
		`CREATE TABLE model_channel_rels (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			model_id INTEGER NOT NULL, channel_model_id INTEGER NOT NULL,
			weight INTEGER DEFAULT 1, enabled INTEGER DEFAULT 1,
			sort_order INTEGER DEFAULT 0, reasoning_effort TEXT, created_at TEXT)`,
		`CREATE TABLE channel_models (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			channel_id INTEGER NOT NULL, model_name TEXT NOT NULL,
			display_name TEXT, enabled INTEGER DEFAULT 1, source TEXT,
			input TEXT DEFAULT 'text', context_length INTEGER,
			channel_api_key_id INTEGER, last_used_at TEXT, created_at TEXT)`,
		`CREATE TABLE channels (
			id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, channel_type TEXT,
			base_url TEXT, enabled INTEGER DEFAULT 1, custom_headers TEXT)`,
		`CREATE TABLE channel_api_keys (
			id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER,
			key_name TEXT, api_key TEXT, enabled INTEGER DEFAULT 1, sort_order INTEGER DEFAULT 0)`,
		`CREATE TABLE api_keys (
			id INTEGER PRIMARY KEY AUTOINCREMENT, key_value TEXT, key_name TEXT, enabled INTEGER DEFAULT 1)`,
		`CREATE TABLE circuit_breaker_configs (
			id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER,
			retry_count INTEGER DEFAULT 3, circuit_break_duration INTEGER DEFAULT 60,
			circuit_break_scope TEXT DEFAULT 'model', enabled INTEGER DEFAULT 1,
			created_at TEXT, updated_at TEXT)`,
		`CREATE TABLE circuit_breaker_states (
			id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER, channel_id INTEGER,
			channel_model_id INTEGER, api_key_id INTEGER, broken_at TEXT, expires_at TEXT)`,
	}
	for _, stmt := range schema {
		if _, err := db.Exec(stmt); err != nil {
			t.Fatalf("schema: %v", err)
		}
	}
	if _, err := db.Exec("INSERT INTO api_keys (key_value, key_name) VALUES ('gw-key', 'test')"); err != nil {
		t.Fatal(err)
	}
	return store.New(db, nil)
}

// setupRoutingFixture builds an entry model "m" with two failover candidates:
// sort_order 0 is the small-window channel, sort_order 1 the large-window one.
// The upstream echoes the upstream model name it received so the test can prove
// which candidate actually served the request.
func setupRoutingFixture(t *testing.T) (*RelayCore, DataStore) {
	t.Helper()
	st := newRelayTestStore(t)
	core := NewRelayCore(nil)
	core.Store = st
	core.RouteResolver = NewRouteResolver(st)

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var parsed struct {
			Model string `json:"model"`
		}
		_ = json.Unmarshal(raw, &parsed)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"1","choices":[{"message":{"role":"assistant","content":"served-by:` +
			parsed.Model + `"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}`))
	}))
	t.Cleanup(upstream.Close)

	if _, err := st.Exec(context.Background(),
		"INSERT INTO models (model_name, strategy, enabled) VALUES ('m','failover',1)"); err != nil {
		t.Fatal(err)
	}
	for i, ch := range []struct {
		channel   string
		upstream  string
		ctxLength any
	}{
		// Small window first: failover would pick it, so a pass proves the skip.
		{channel: "small", upstream: "model-small", ctxLength: int64(2000)},
		{channel: "large", upstream: "model-large", ctxLength: int64(200000)},
	} {
		chID, err := st.Insert(context.Background(),
			"INSERT INTO channels (name, channel_type, base_url, enabled) VALUES (?, 'openai', ?, 1)",
			ch.channel, upstream.URL)
		if err != nil {
			t.Fatal(err)
		}
		keyID, err := st.Insert(context.Background(),
			"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, 'k', 'sk', 1, 0)", chID)
		if err != nil {
			t.Fatal(err)
		}
		cmID, err := st.Insert(context.Background(),
			"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, ?, 1, 'text', ?, ?)",
			chID, ch.upstream, ch.ctxLength, keyID)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := st.Exec(context.Background(),
			"INSERT INTO model_channel_rels (model_id, channel_model_id, sort_order, enabled) VALUES (1, ?, ?, 1)",
			cmID, i); err != nil {
			t.Fatal(err)
		}
	}
	return core, st
}

// TestContextSkipInRoutingLoop proves the end-to-end behavior: a request larger
// than a candidate's window (plus tolerance) is skipped, and the next candidate
// serves it.
func TestContextSkipInRoutingLoop(t *testing.T) {
	core, _ := setupRoutingFixture(t)

	// ~60000 chars ≈ 15000 tokens: above small (2000 + 5000 tolerance), below large.
	big := strings.Repeat("a", 60000)
	body := `{"model":"m","messages":[{"role":"user","content":"` + big + `"}]}`
	req, err := ParseRequest(body, ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}

	res := core.RelayNonStream(context.Background(), req, "Bearer gw-key", "", body)
	if res.StatusCode != 200 {
		t.Fatalf("status = %d, body = %s", res.StatusCode, res.Body)
	}
	if !strings.Contains(res.Body, "served-by:model-large") {
		t.Errorf("expected the large-window candidate to serve, got: %s", res.Body)
	}
}

// TestContextSkipDisabledForUnknownWindow proves an unknown window (NULL) is
// never skipped, so the first failover candidate handles the request even when
// the request is large.
func TestContextSkipDisabledForUnknownWindow(t *testing.T) {
	core, st := setupRoutingFixture(t)
	if _, err := st.Exec(context.Background(),
		"UPDATE channel_models SET context_length = NULL WHERE model_name = 'model-small'"); err != nil {
		t.Fatal(err)
	}

	big := strings.Repeat("a", 60000)
	body := `{"model":"m","messages":[{"role":"user","content":"` + big + `"}]}`
	req, _ := ParseRequest(body, ProtoOpenAI)

	res := core.RelayNonStream(context.Background(), req, "Bearer gw-key", "", body)
	if res.StatusCode != 200 {
		t.Fatalf("status = %d, body = %s", res.StatusCode, res.Body)
	}
	if !strings.Contains(res.Body, "served-by:model-small") {
		t.Errorf("unknown window must not skip, got: %s", res.Body)
	}
}

// TestContextSkipWithinTolerance proves a request just under window + tolerance
// still routes to the small candidate.
func TestContextSkipWithinTolerance(t *testing.T) {
	core, _ := setupRoutingFixture(t)

	// Window 2000 + 5000 tolerance = 7000 tokens ≈ 28000 chars. Use 20000 chars
	// (~5000 tokens) which fits.
	body := `{"model":"m","messages":[{"role":"user","content":"` + strings.Repeat("a", 20000) + `"}]}`
	req, _ := ParseRequest(body, ProtoOpenAI)

	res := core.RelayNonStream(context.Background(), req, "Bearer gw-key", "", body)
	if res.StatusCode != 200 {
		t.Fatalf("status = %d, body = %s", res.StatusCode, res.Body)
	}
	if !strings.Contains(res.Body, "served-by:model-small") {
		t.Errorf("request within tolerance must not skip, got: %s", res.Body)
	}
}
