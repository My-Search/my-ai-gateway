package circuit

import (
	"context"
	"database/sql"
	"testing"
	"time"

	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/store"
)

func newTestStore(t *testing.T) *store.Store {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=busy_timeout(30000)")
	if err != nil {
		t.Fatal(err)
	}
	// A plain ":memory:" database is per-connection; keep a single connection so
	// the schema created below is visible to every later query.
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	ddl := []string{
		`CREATE TABLE circuit_breaker_configs (
			id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER, retry_count INTEGER,
			circuit_break_duration INTEGER, circuit_break_scope TEXT, enabled INTEGER,
			created_at TEXT, updated_at TEXT)`,
		`CREATE TABLE circuit_breaker_states (
			id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER, channel_model_id INTEGER,
			is_open INTEGER, fail_count INTEGER, opened_at TEXT, expire_at TEXT,
			created_at TEXT, updated_at TEXT, channel_api_key_id INTEGER)`,
	}
	for _, q := range ddl {
		if _, err := db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	return store.New(db)
}

// TestListExpiredStatesMixedTimestampFormats is the regression test for the
// original bug: records written with the application's "T" format must be found
// by the expiry scan even though SQLite compares TEXT lexicographically.
// Now FormatDefault also produces T-format, so all rows use the same format.
func TestListExpiredStatesMixedTimestampFormats(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	gate := &Gate{Store: st}

	// Two expired rows and two future rows (all T-format).
	past := time.Now().UTC().Add(-10 * time.Minute)
	future := time.Now().UTC().Add(10 * time.Minute)
	for _, tc := range []struct {
		expire string
		label  string
	}{
		{jtime.FormatApp(past), "T-format expired"},
		{jtime.FormatDefault(past), "Default-format expired"},
		{jtime.FormatApp(future), "App-format future"},
		{jtime.FormatDefault(future), "Default-format future"},
	} {
		if _, err := st.Exec(ctx,
			"INSERT INTO circuit_breaker_states (channel_id, is_open, fail_count, opened_at, expire_at, created_at, updated_at) VALUES (1, 1, 1, ?, ?, ?, ?)",
			jtime.FormatApp(past), tc.expire, jtime.FormatApp(past), jtime.FormatApp(past)); err != nil {
			t.Fatalf("%s: %v", tc.label, err)
		}
	}

	expired := gate.ListExpiredStates(ctx)
	if len(expired) != 2 {
		t.Fatalf("expected 2 expired records, got %d (the T-format row is likely invisible)", len(expired))
	}
	byChannel := gate.ListExpiredStatesByChannel(ctx, 1)
	if len(byChannel) != 2 {
		t.Fatalf("expected 2 expired records by channel, got %d", len(byChannel))
	}
}

// TestRecoverModelStateCascade checks the channel gate cascade only removes
// gates that opened no later than the model gate.
func TestRecoverModelStateCascade(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	gate := &Gate{Store: st}

	base := time.Now().UTC().Add(-time.Hour)
	older := base
	newer := base.Add(30 * time.Minute)

	// Model gate (keyed by api key 7) and a channel gate with the same key.
	modelID, _ := st.Insert(ctx,
		"INSERT INTO circuit_breaker_states (channel_id, channel_api_key_id, channel_model_id, is_open, fail_count, opened_at, expire_at, created_at, updated_at) VALUES (1, 7, 100, 1, 1, ?, ?, ?, ?)",
		jtime.FormatApp(newer), jtime.FormatApp(newer), jtime.FormatApp(newer), jtime.FormatApp(newer))
	channelID, _ := st.Insert(ctx,
		"INSERT INTO circuit_breaker_states (channel_id, channel_api_key_id, is_open, fail_count, opened_at, expire_at, created_at, updated_at) VALUES (1, 7, 1, 1, ?, ?, ?, ?)",
		jtime.FormatApp(older), jtime.FormatApp(newer), jtime.FormatApp(older), jtime.FormatApp(older))
	// A channel gate that opened AFTER the model gate must survive.
	survivorID, _ := st.Insert(ctx,
		"INSERT INTO circuit_breaker_states (channel_id, channel_api_key_id, is_open, fail_count, opened_at, expire_at, created_at, updated_at) VALUES (1, 7, 1, 1, ?, ?, ?, ?)",
		jtime.FormatApp(newer.Add(time.Minute)), jtime.FormatApp(newer), jtime.FormatApp(newer.Add(time.Minute)), jtime.FormatApp(newer.Add(time.Minute)))

	state, err := st.QueryOne(ctx, "SELECT * FROM circuit_breaker_states WHERE id = ?", modelID)
	if err != nil {
		t.Fatal(err)
	}
	gate.RecoverModelState(ctx, CircuitBreakerState{
		ID: modelID, ChannelID: 1, ChannelAPIKeyID: i64ptr(7),
		ChannelModelID: i64ptr(100),
		OpenedAt:       jtime.ScanTime(state["opened_at"]),
	})

	if _, err := st.QueryOne(ctx, "SELECT id FROM circuit_breaker_states WHERE id = ?", modelID); err == nil {
		t.Error("model gate should have been deleted")
	}
	if _, err := st.QueryOne(ctx, "SELECT id FROM circuit_breaker_states WHERE id = ?", channelID); err == nil {
		t.Error("older channel gate should have been cascaded away")
	}
	if _, err := st.QueryOne(ctx, "SELECT id FROM circuit_breaker_states WHERE id = ?", survivorID); err != nil {
		t.Error("newer channel gate must survive the cascade")
	}
}

// TestCheckSemantics verifies the three-layer availability judgement.
func TestCheckSemantics(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	check := &Check{Store: st}
	now := jtime.FormatApp(time.Now().UTC())

	// No records: everything is available.
	if !check.IsAvailable(ctx, 100, 1, i64ptr(7)) {
		t.Fatal("no records should be available")
	}

	// Whole-channel record (channel_api_key_id IS NULL).
	if _, err := st.Insert(ctx,
		"INSERT INTO circuit_breaker_states (channel_id, is_open, fail_count, opened_at, expire_at, created_at, updated_at) VALUES (1, 1, 1, ?, ?, ?, ?)",
		now, now, now, now); err != nil {
		t.Fatal(err)
	}
	if !check.IsChannelBroken(ctx, 1) {
		t.Error("whole-channel record should mark the channel broken")
	}
	if check.IsAvailable(ctx, 100, 1, i64ptr(7)) {
		t.Error("whole-channel record should make every path unavailable")
	}
	// Clean up.
	_, _ = st.Exec(ctx, "DELETE FROM circuit_breaker_states")

	// Per-key model record: the key is broken, a legacy NULL-key row covers all.
	if _, err := st.Insert(ctx,
		"INSERT INTO circuit_breaker_states (channel_id, channel_api_key_id, channel_model_id, is_open, fail_count, opened_at, expire_at, created_at, updated_at) VALUES (1, 7, 100, 1, 1, ?, ?, ?, ?)",
		now, now, now, now); err != nil {
		t.Fatal(err)
	}
	if !check.IsModelBroken(ctx, 100, i64ptr(7)) {
		t.Error("model record for key 7 should mark model broken for key 7")
	}
	if check.IsModelBroken(ctx, 100, i64ptr(8)) {
		t.Error("model record for key 7 must not affect key 8")
	}
	if check.IsAvailable(ctx, 100, 1, i64ptr(7)) {
		t.Error("key 7 path should be unavailable")
	}
	if !check.IsAvailable(ctx, 100, 1, i64ptr(8)) {
		t.Error("key 8 path should still be available")
	}
}

// TestEvaluateRelBroken checks the admin-display "fully broken" computation.
func TestEvaluateRelBroken(t *testing.T) {
	now := time.Now().UTC()
	expire := now.Add(time.Minute)
	apiKey := int64(7)

	// Unbound rel with two enabled keys, only one broken → still available.
	states := []CircuitBreakerState{{
		ChannelID: 1, ChannelAPIKeyID: &apiKey, ChannelModelID: i64ptr(100),
		IsOpen: 1, ExpireAt: &expire,
	}}
	mark := EvaluateRelBroken(100, 1, nil,
		states,
		[]KeyRef{{ID: 7, Enabled: true}, {ID: 8, Enabled: true}},
		map[int64]KeyRef{})
	if mark != nil {
		t.Errorf("partially broken rel must not be marked, got %+v", mark)
	}

	// All keys broken → marked with scope "model".
	states = append(states, CircuitBreakerState{
		ChannelID: 1, ChannelAPIKeyID: i64ptr(8), ChannelModelID: i64ptr(100),
		IsOpen: 1, ExpireAt: &expire,
	})
	mark = EvaluateRelBroken(100, 1, nil,
		states,
		[]KeyRef{{ID: 7, Enabled: true}, {ID: 8, Enabled: true}},
		map[int64]KeyRef{})
	if mark == nil || mark.Scope != "model" {
		t.Fatalf("expected model-scope mark, got %+v", mark)
	}

	// Bound key that is disabled: a configuration problem, not a breaker mark.
	mark = EvaluateRelBroken(100, 1, i64ptr(9),
		states,
		[]KeyRef{},
		map[int64]KeyRef{9: {ID: 9, Enabled: false}})
	if mark != nil {
		t.Errorf("disabled bound key must not be marked, got %+v", mark)
	}
}

// TestConfigManagerDefaults checks the default row creation (retry_count=3).
func TestConfigManagerDefaults(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	mgr := &ConfigManager{Store: st}

	cfg := mgr.GetConfig(ctx, 42)
	if cfg == nil {
		t.Fatal("expected a default config")
	}
	if cfg.RetryCount != 3 || cfg.CircuitBreakDuration != 60 || cfg.CircuitBreakScope != "model" || cfg.Enabled != 1 {
		t.Fatalf("defaults wrong: %+v", cfg)
	}
	// A second call must read the persisted row (single insert).
	var count int
	if row, err := st.QueryOne(ctx, "SELECT COUNT(*) cnt FROM circuit_breaker_configs WHERE model_id = 42"); err == nil {
		count = row.Int("cnt", 0)
	}
	if count != 1 {
		t.Fatalf("expected exactly 1 config row, got %d", count)
	}
}

func i64ptr(v int64) *int64 { return &v }
