package channelload

import (
	"context"
	"database/sql"
	"testing"

	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// newRuleTestStore builds the subset of multimodal_rules + channel_models that
// ComputeInput and ReapplyAllRules touch.
func newRuleTestStore(t *testing.T) *store.Store {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=busy_timeout(30000)")
	if err != nil {
		t.Fatal(err)
	}
	// A plain ":memory:" database is per-connection; keep one connection so the
	// schema and rows created below stay visible.
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	if _, err := db.Exec(`CREATE TABLE multimodal_rules (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		pattern TEXT NOT NULL,
		append_type TEXT NOT NULL,
		created_at TEXT,
		updated_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE TABLE channel_models (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		channel_id INTEGER NOT NULL,
		model_name TEXT NOT NULL,
		display_name TEXT,
		enabled INTEGER DEFAULT 1,
		source TEXT,
		input TEXT DEFAULT 'text',
		created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	// The rule cache is package-level and shared across tests.
	InvalidateRuleCache()
	t.Cleanup(InvalidateRuleCache)
	return store.New(db, nil)
}

func insertRule(t *testing.T, st *store.Store, pattern, appendType, createdAt string) {
	t.Helper()
	if _, err := st.Exec(context.Background(),
		"INSERT INTO multimodal_rules (pattern, append_type, created_at, updated_at) VALUES (?, ?, ?, ?)",
		pattern, appendType, createdAt, createdAt); err != nil {
		t.Fatal(err)
	}
}

// TestMatchPattern pins the invariant behind the test/apply consistency fix:
// the same regexp2 engine and flags the test endpoint uses must drive rule
// application, including the reported "^hy" vs real model-name cases.
func TestMatchPattern(t *testing.T) {
	tests := []struct {
		name    string
		pattern string
		s       string
		want    bool
		wantErr bool
	}{
		{name: "anchored prefix matches bare id", pattern: "^hy", s: "hy4", want: true},
		// The reported bug: users type "hy4" into the test box, but the real
		// channel_models.model_name carries a prefix the anchored rule misses.
		{name: "anchored prefix misses prefixed id", pattern: "^hy", s: "workbuddy/hy4-preview", want: false},
		{name: "unanchored substring matches anywhere", pattern: "hy4", s: "workbuddy/hy4-preview", want: true},
		{name: "case sensitive", pattern: "^hy", s: "HY4", want: false},
		// Lookahead is valid regexp2 syntax: it must be accepted by save/test
		// AND by ComputeInput, never silently dropped on one side only.
		{name: "lookahead works", pattern: "hy(?=4)", s: "hy4", want: true},
		{name: "lookahead rejects non-match", pattern: "hy(?=4)", s: "hy3", want: false},
		{name: "invalid pattern errors", pattern: "([", s: "hy4", wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := MatchPattern(tt.pattern, tt.s)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("MatchPattern(%q, %q) error = nil, want error", tt.pattern, tt.s)
				}
				return
			}
			if err != nil {
				t.Fatalf("MatchPattern(%q, %q) unexpected error: %v", tt.pattern, tt.s, err)
			}
			if got != tt.want {
				t.Errorf("MatchPattern(%q, %q) = %v, want %v", tt.pattern, tt.s, got, tt.want)
			}
		})
	}
}

// TestComputeInput verifies applied results: anchored rules stay anchored
// against real model names, and overlapping comma-separated append types are
// deduped per type rather than per rule.
func TestComputeInput(t *testing.T) {
	ctx := context.Background()
	st := newRuleTestStore(t)
	insertRule(t, st, "^hy", "image", "2026-01-01")
	insertRule(t, st, "^hy4", "image,video", "2026-01-02")
	InvalidateRuleCache()

	tests := []struct {
		model string
		want  string
	}{
		// "hy4" hits both rules: image once (deduped), video appended.
		{model: "hy4", want: "text,image,video"},
		{model: "hy3", want: "text,image"},
		// The reported case: prefixed real name matches neither anchored rule.
		{model: "workbuddy/hy4-preview", want: "text"},
		{model: "gpt-4o", want: "text"},
	}
	for _, tt := range tests {
		if got := ComputeInput(ctx, st, tt.model); got != tt.want {
			t.Errorf("ComputeInput(%q) = %q, want %q", tt.model, got, tt.want)
		}
	}
}

// TestReapplyAllRules verifies the full apply path: rule CRUD runs this to
// rewrite channel_models.input, so a rule that tests as matching must land in
// the column the relay actually reads.
func TestReapplyAllRules(t *testing.T) {
	ctx := context.Background()
	st := newRuleTestStore(t)
	insertRule(t, st, "^hy", "image", "2026-01-01")
	for _, m := range []string{"hy4", "workbuddy/hy4-preview", "claude-3-opus"} {
		if _, err := st.Exec(ctx,
			"INSERT INTO channel_models (channel_id, model_name, input) VALUES (1, ?, 'text')", m); err != nil {
			t.Fatal(err)
		}
	}
	InvalidateRuleCache()
	ReapplyAllRules(ctx, st)

	want := map[string]string{
		"hy4":                   "text,image",
		"workbuddy/hy4-preview": "text",
		"claude-3-opus":         "text",
	}
	for name, expect := range want {
		row, err := st.QueryOne(ctx, "SELECT input FROM channel_models WHERE model_name = ?", name)
		if err != nil {
			t.Fatalf("query %q: %v", name, err)
		}
		if got := row.Str("input"); got != expect {
			t.Errorf("input for %q = %q, want %q", name, got, expect)
		}
	}
}
