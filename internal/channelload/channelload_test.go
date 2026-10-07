package channelload

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// newRuleTestStore builds the subset of model_config_rules + channel_models that
// the rule engine touches.
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
	if _, err := db.Exec(`CREATE TABLE model_config_rules (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		pattern TEXT NOT NULL,
		append_type TEXT NOT NULL DEFAULT '',
		context_length INTEGER NOT NULL DEFAULT 0,
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
		context_length INTEGER,
		created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	// The rule cache is package-level and shared across tests.
	InvalidateRuleCache()
	t.Cleanup(InvalidateRuleCache)
	return store.New(db, nil)
}

// useCatalogFile points the catalog at a temp file so tests never touch the real
// data/models.json. The file content is written by the caller.
func useCatalogFile(t *testing.T, content string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "models.json")
	if content != "" {
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	prev := CatalogPath()
	SetCatalogPath(path)
	ReloadCatalog()
	t.Cleanup(func() {
		SetCatalogPath(prev)
		ReloadCatalog()
	})
}

func insertRule(t *testing.T, st *store.Store, pattern, appendType string, contextLength int64, createdAt string) {
	t.Helper()
	if _, err := st.Exec(context.Background(),
		"INSERT INTO model_config_rules (pattern, append_type, context_length, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
		pattern, appendType, contextLength, createdAt, createdAt); err != nil {
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
	useCatalogFile(t, "")
	insertRule(t, st, "^hy", "image", 0, "2026-01-01")
	insertRule(t, st, "^hy4", "image,video", 0, "2026-01-02")
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
// rewrite channel_models, so a rule that tests as matching must land in the
// columns the relay actually reads.
func TestReapplyAllRules(t *testing.T) {
	ctx := context.Background()
	st := newRuleTestStore(t)
	useCatalogFile(t, "")
	insertRule(t, st, "^hy", "image", 8192, "2026-01-01")
	for _, m := range []string{"hy4", "workbuddy/hy4-preview", "claude-3-opus"} {
		if _, err := st.Exec(ctx,
			"INSERT INTO channel_models (channel_id, model_name, input) VALUES (1, ?, 'text')", m); err != nil {
			t.Fatal(err)
		}
	}
	InvalidateRuleCache()
	ReapplyAllRules(ctx, st)

	want := map[string]struct {
		input string
		ctx   sql.NullInt64
	}{
		"hy4":                   {input: "text,image", ctx: sql.NullInt64{Int64: 8192, Valid: true}},
		"workbuddy/hy4-preview": {input: "text"},
		"claude-3-opus":         {input: "text"},
	}
	for name, expect := range want {
		row, err := st.QueryOne(ctx, "SELECT input, context_length FROM channel_models WHERE model_name = ?", name)
		if err != nil {
			t.Fatalf("query %q: %v", name, err)
		}
		if got := row.Str("input"); got != expect.input {
			t.Errorf("input for %q = %q, want %q", name, got, expect.input)
		}
		got := row.I64Ptr("context_length")
		if expect.ctx.Valid {
			if got == nil || *got != expect.ctx.Int64 {
				t.Errorf("context_length for %q = %v, want %d", name, got, expect.ctx.Int64)
			}
		} else if got != nil {
			t.Errorf("context_length for %q = %d, want NULL", name, *got)
		}
	}
}

// TestResolveModelConfigPrecedence pins the core contract: our rules win over the
// models.dev baseline, independently per field.
func TestResolveModelConfigPrecedence(t *testing.T) {
	ctx := context.Background()
	st := newRuleTestStore(t)
	useCatalogFile(t, `{"data":[
		{"id":"openai/gpt-4o","context_length":128000,"architecture":{"input_modalities":["text","image"]}},
		{"id":"openai/gpt-4","context_length":8192,"architecture":{"input_modalities":["text"]}}
	]}`)

	// A rule that only sets the context window: the baseline modalities stay.
	insertRule(t, st, "^gpt-4o$", "", 5000, "2026-01-01")
	// A rule that only appends modalities: the baseline window stays.
	insertRule(t, st, "^gpt-4$", "image,video", 0, "2026-01-02")
	InvalidateRuleCache()

	tests := []struct {
		model     string
		wantInput string
		wantCtx   int64
		wantSrc   string
	}{
		// Rule window overrides the baseline; baseline modalities are kept.
		{model: "gpt-4o", wantInput: "text,image", wantCtx: 5000, wantSrc: "rule"},
		// Rule modalities append to the baseline; baseline window is kept.
		{model: "gpt-4", wantInput: "text,image,video", wantCtx: 8192, wantSrc: "catalog"},
		// No rule and no baseline entry: unknown window, text-only.
		{model: "mystery-model", wantInput: "text", wantCtx: 0, wantSrc: "none"},
	}
	for _, tt := range tests {
		input, ctxLen := ResolveModelConfig(ctx, st, tt.model)
		if input != tt.wantInput {
			t.Errorf("ResolveModelConfig(%q) input = %q, want %q", tt.model, input, tt.wantInput)
		}
		if tt.wantCtx == 0 {
			if ctxLen != nil {
				t.Errorf("ResolveModelConfig(%q) contextLength = %d, want nil", tt.model, *ctxLen)
			}
		} else if ctxLen == nil || *ctxLen != tt.wantCtx {
			t.Errorf("ResolveModelConfig(%q) contextLength = %v, want %d", tt.model, ctxLen, tt.wantCtx)
		}
		if src := ContextLengthSource(ctx, st, tt.model); src != tt.wantSrc {
			t.Errorf("ContextLengthSource(%q) = %q, want %q", tt.model, src, tt.wantSrc)
		}
	}
}

// TestMatchRulesContextOverride verifies that when several rules set a window,
// the last one (rule order = creation order) wins, while modality types union.
func TestMatchRulesContextOverride(t *testing.T) {
	ctx := context.Background()
	st := newRuleTestStore(t)
	useCatalogFile(t, "")
	insertRule(t, st, "^claude", "image", 100000, "2026-01-01")
	insertRule(t, st, "^claude-sonnet", "video", 300000, "2026-01-02")
	InvalidateRuleCache()

	m := MatchRules(ctx, st, "claude-sonnet-4-5")
	if m.ContextLength != 300000 {
		t.Errorf("context = %d, want the later rule's 300000", m.ContextLength)
	}
	if got := ComputeInput(ctx, st, "claude-sonnet-4-5"); got != "text,image,video" {
		t.Errorf("input = %q, want text,image,video", got)
	}
}

// TestCatalogShortIDMatch verifies the catalog lookup falls back from full id to
// short id to a date-stripped short id, which is how local channel model names
// (often provider-prefixed or date-suffixed) line up with models.dev entries.
func TestCatalogShortIDMatch(t *testing.T) {
	ctx := context.Background()
	st := newRuleTestStore(t)
	// models.dev writes canonical slugs with dots where provider APIs use hyphens,
	// so the lookup must normalize: catalog "claude-sonnet-4.5" vs API
	// "claude-sonnet-4-5". One file covers both the literal and normalized keys.
	useCatalogFile(t, `{"data":[
		{"id":"openai/gpt-4o","context_length":128000,"architecture":{"input_modalities":["text","image"]}},
		{"id":"anthropic/claude-sonnet-4.5","context_length":200000,"architecture":{"input_modalities":["text","image"]}}
	]}`)

	for _, name := range []string{"gpt-4o", "openai/gpt-4o", "vendor/gpt-4o-2024-08-06"} {
		if got := ResolveContextLength(ctx, st, name); got == nil || *got != 128000 {
			t.Errorf("ResolveContextLength(%q) = %v, want 128000", name, got)
		}
		if src := ContextLengthSource(ctx, st, name); src != "catalog" {
			t.Errorf("ContextLengthSource(%q) = %q, want catalog", name, src)
		}
	}

	// Dotted catalog slug must match the hyphenated provider id and vice versa.
	for _, name := range []string{"claude-sonnet-4-5", "anthropic/claude-sonnet-4-5", "claude-sonnet-4.5"} {
		if got := ResolveContextLength(ctx, st, name); got == nil || *got != 200000 {
			t.Errorf("ResolveContextLength(%q) = %v, want 200000 (dot/hyphen normalization)", name, got)
		}
	}
}

// TestParseCatalog covers both upstream payload shapes.
func TestParseCatalog(t *testing.T) {
	flat := []byte(`{"data":[
		{"id":"anthropic/claude-opus-4.7-fast","context_length":1000000,
		 "architecture":{"input_modalities":["text","image","file"]}},
		{"id":"perceptron/mk1","context_length":32768,
		 "architecture":{"input_modalities":["text","image","video"]}}
	]}`)
	entries, err := ParseCatalog(flat)
	if err != nil {
		t.Fatalf("flat parse: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("flat entries = %d, want 2", len(entries))
	}
	byID := map[string]CatalogEntry{}
	for _, e := range entries {
		byID[e.ModelID] = e
	}
	if e := byID["anthropic/claude-opus-4.7-fast"]; e.ContextLength != 1000000 || e.ShortID != "claude-opus-4.7-fast" {
		t.Errorf("flat entry = %+v", e)
	}
	// "file" is not a relay-detectable media type and must be dropped.
	if e := byID["anthropic/claude-opus-4.7-fast"]; e.InputTypes != "text,image" {
		t.Errorf("flat input types = %q, want text,image", e.InputTypes)
	}
	if e := byID["perceptron/mk1"]; e.InputTypes != "text,image,video" {
		t.Errorf("flat video input types = %q", e.InputTypes)
	}

	providerMap := []byte(`{"anthropic":{"models":{
		"claude-sonnet-4-5":{"limit":{"context":200000},"modalities":{"input":["text","image","pdf"]}}
	}}}`)
	entries, err = ParseCatalog(providerMap)
	if err != nil {
		t.Fatalf("provider-map parse: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("provider-map entries = %d, want 1", len(entries))
	}
	if e := entries[0]; e.ModelID != "anthropic/claude-sonnet-4-5" || e.ContextLength != 200000 || e.InputTypes != "text,image" {
		t.Errorf("provider-map entry = %+v", e)
	}
}

func TestStripDateSuffix(t *testing.T) {
	tests := map[string]string{
		"gpt-4o-2024-08-06": "gpt-4o",
		"gpt-4o-20240806":   "gpt-4o",
		"claude-3-opus":     "claude-3-opus",
		"gpt-4o":            "gpt-4o",
	}
	for in, want := range tests {
		if got := stripDateSuffix(in); got != want {
			t.Errorf("stripDateSuffix(%q) = %q, want %q", in, got, want)
		}
	}
}

// TestCatalogMissingFile verifies a missing data file is a valid state: no
// baseline data, and rules still apply.
func TestCatalogMissingFile(t *testing.T) {
	ctx := context.Background()
	st := newRuleTestStore(t)
	useCatalogFile(t, "") // path exists in config but the file was not written
	insertRule(t, st, "^hy", "image", 4096, "2026-01-01")
	InvalidateRuleCache()

	input, ctxLen := ResolveModelConfig(ctx, st, "hy4")
	if input != "text,image" || ctxLen == nil || *ctxLen != 4096 {
		t.Errorf("rules must still apply without a data file: input=%q ctx=%v", input, ctxLen)
	}
	if got := ContextLengthSource(ctx, st, "hy4"); got != "rule" {
		t.Errorf("source = %q, want rule", got)
	}
}

// TestCatalogSpecialSuffixFallback 覆盖“特殊后缀”回退：-preview/-free 是渠道
// 标记，不算模型名——先按全名匹配目录，匹配不到再剥掉后缀回退到基础模型。
func TestCatalogSpecialSuffixFallback(t *testing.T) {
	ctx := context.Background()
	st := newRuleTestStore(t)

	// 目录里只有基础模型 hy4，没有带 -preview/-free 的条目。
	useCatalogFile(t, `{"data":[
		{"id":"yyy/hy4","context_length":200000,"architecture":{"input_modalities":["text","image"]}}
	]}`)
	for _, name := range []string{"qoder/hy4-preview", "qoder/hy4-free", "hy4-preview", "hy4-free"} {
		got := ResolveContextLength(ctx, st, name)
		if got == nil || *got != 200000 {
			t.Errorf("ResolveContextLength(%q) = %v, want 200000 (strip special suffix)", name, got)
		}
	}
	// 无关模型不受影响。
	if got := ResolveContextLength(ctx, st, "qoder/other-preview"); got != nil {
		t.Errorf("unrelated model = %v, want nil", got)
	}

	// 目录里同时有具体后缀条目与基础条目时，具体条目优先。
	useCatalogFile(t, `{"data":[
		{"id":"zhipu/hy4-preview","context_length":128000,"architecture":{"input_modalities":["text"]}},
		{"id":"yyy/hy4","context_length":200000,"architecture":{"input_modalities":["text","image"]}}
	]}`)
	got := ResolveContextLength(ctx, st, "qoder/hy4-preview")
	if got == nil || *got != 128000 {
		t.Errorf("specific variant = %v, want 128000 (exact suffix match wins)", got)
	}
	if src := ContextLengthSource(ctx, st, "qoder/hy4-preview"); src != "catalog" {
		t.Errorf("source = %q, want catalog", src)
	}

	// 目录里两个 hy4 都没有 → 依旧匹配不到。
	useCatalogFile(t, `{"data":[
		{"id":"yyy/other-model","context_length":64000,"architecture":{"input_modalities":["text"]}}
	]}`)
	if got := ResolveContextLength(ctx, st, "qoder/hy4-preview"); got != nil {
		t.Errorf("no candidate = %v, want nil", got)
	}
}

// TestStripSpecialSuffix 校验逐层剥离：一次只去一层特殊后缀。
func TestStripSpecialSuffix(t *testing.T) {
	tests := map[string]string{
		"hy4-preview":          "hy4",
		"hy4-free":             "hy4",
		"hy4-preview-free":     "hy4-preview",
		"model-beta":           "model",
		"model-exp":            "model",
		"model-experimental":   "model",
		"hy4-preview-20250930": "hy4-preview-20250930", // 日期由 stripDateSuffix 负责
		"hy4":                  "hy4",
		"gpt-4o-mini":          "gpt-4o-mini",
		"claude-3-5-sonnet":    "claude-3-5-sonnet",
	}
	for in, want := range tests {
		if got := stripSpecialSuffix(in); got != want {
			t.Errorf("stripSpecialSuffix(%q) = %q, want %q", in, got, want)
		}
	}
}

// TestReplaceAPIModelsPreservesGroupMemberships covers the provider-refresh path:
// source='api' channel-model rows are deleted and re-inserted with new ids, so any
// group membership pointing at the old ids must be re-pointed by model name —
// otherwise one channel refresh would silently empty every model group.
func TestReplaceAPIModelsPreservesGroupMemberships(t *testing.T) {
	st := newRuleTestStore(t)
	ctx := context.Background()

	if _, err := st.Exec(ctx, `CREATE TABLE model_channel_rels (
		id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER NOT NULL,
		channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
		reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
		enabled INTEGER DEFAULT 1, created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Exec(ctx, `CREATE TABLE model_group_members (
		id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL,
		channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
		reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
		enabled INTEGER DEFAULT 1, created_at TEXT)`); err != nil {
		t.Fatal(err)
	}

	// An api-sourced channel model that is both directly related and a group member.
	oldID, err := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, source, input) VALUES (1, 'gpt-x', 1, 'api', 'text')")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.Exec(ctx,
		"INSERT INTO model_channel_rels (model_id, channel_model_id, weight, sort_order) VALUES (5, ?, 3, 2)", oldID); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Exec(ctx,
		"INSERT INTO model_group_members (group_id, channel_model_id, weight, reasoning_effort, sort_order) VALUES (9, ?, 4, 'high', 1)", oldID); err != nil {
		t.Fatal(err)
	}

	ReplaceAPIModels(ctx, st, 1, []ModelPair{{Name: "gpt-x", Display: "GPT-X"}})

	// The recreated row must have a new id, and the membership must follow it.
	newRow := st.QueryOneOrZero(ctx, "SELECT id FROM channel_models WHERE channel_id = 1 AND model_name = 'gpt-x'")
	if newRow == nil {
		t.Fatal("replaced channel model missing")
	}
	newID := newRow.I64("id", 0)
	if newID == oldID {
		t.Fatal("expected the refresh to recreate the row with a new id; test premise is wrong")
	}

	member := st.QueryOneOrZero(ctx, "SELECT group_id, weight, reasoning_effort, sort_order FROM model_group_members WHERE channel_model_id = ?", newID)
	if member == nil {
		t.Fatal("group membership was lost across the channel model refresh")
	}
	if member.I64("group_id", 0) != 9 {
		t.Errorf("membership moved to group %d, want 9", member.I64("group_id", 0))
	}
	if member.Int("weight", 0) != 4 {
		t.Errorf("member weight = %d, want 4 (weight must survive the refresh)", member.Int("weight", 0))
	}
	if member.Str("reasoning_effort") != "high" {
		t.Errorf("member reasoning_effort = %q, want high", member.Str("reasoning_effort"))
	}
	if member.Int("sort_order", 0) != 1 {
		t.Errorf("member sort_order = %d, want 1", member.Int("sort_order", 0))
	}

	// The direct relation must be preserved too (pre-existing behaviour).
	if rel := st.QueryOneOrZero(ctx, "SELECT model_id, weight FROM model_channel_rels WHERE channel_model_id = ?", newID); rel == nil {
		t.Error("direct model relation was lost across the refresh")
	} else if rel.Int("weight", 0) != 3 {
		t.Errorf("relation weight = %d, want 3", rel.Int("weight", 0))
	}

	// The stale row must be gone (no orphans pointing at the deleted id).
	if stale := st.QueryOneOrZero(ctx, "SELECT id FROM model_group_members WHERE channel_model_id = ?", oldID); stale != nil {
		t.Error("a membership still points at the deleted channel model id")
	}
}

// TestReplaceAPIModelsDropsMembershipForRemovedModel proves a membership whose
// channel model disappeared upstream is dropped rather than left dangling.
func TestReplaceAPIModelsDropsMembershipForRemovedModel(t *testing.T) {
	st := newRuleTestStore(t)
	ctx := context.Background()
	if _, err := st.Exec(ctx, `CREATE TABLE model_channel_rels (
		id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER NOT NULL,
		channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
		reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
		enabled INTEGER DEFAULT 1, created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Exec(ctx, `CREATE TABLE model_group_members (
		id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL,
		channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
		reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
		enabled INTEGER DEFAULT 1, created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	oldID, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, source, input) VALUES (2, 'gone', 1, 'api', 'text')")
	if _, err := st.Exec(ctx,
		"INSERT INTO model_group_members (group_id, channel_model_id) VALUES (9, ?)", oldID); err != nil {
		t.Fatal(err)
	}

	// Upstream no longer lists 'gone'.
	ReplaceAPIModels(ctx, st, 2, []ModelPair{{Name: "still-here"}})

	if n := st.QueryOneOrZero(ctx, "SELECT COUNT(*) c FROM model_group_members"); n.Int("c", 0) != 0 {
		t.Errorf("%d memberships remain after their channel model disappeared upstream", n.Int("c", 0))
	}
}

// TestReplaceAPIModelsSkipsManualModelsForGroups proves a manual channel model
// (source != 'api') keeps its membership untouched by the refresh path.
func TestReplaceAPIModelsSkipsManualModelsForGroups(t *testing.T) {
	st := newRuleTestStore(t)
	ctx := context.Background()
	if _, err := st.Exec(ctx, `CREATE TABLE model_channel_rels (
		id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER NOT NULL,
		channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
		reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
		enabled INTEGER DEFAULT 1, created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Exec(ctx, `CREATE TABLE model_group_members (
		id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL,
		channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
		reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
		enabled INTEGER DEFAULT 1, created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	manualID, err := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, source, input) VALUES (3, 'manual-model', 1, 'manual', 'text')")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.Exec(ctx,
		"INSERT INTO model_group_members (group_id, channel_model_id, weight) VALUES (9, ?, 6)", manualID); err != nil {
		t.Fatal(err)
	}

	// Upstream also lists the same name, but the manual row must win and stay put.
	ReplaceAPIModels(ctx, st, 3, []ModelPair{{Name: "manual-model"}})

	member := st.QueryOneOrZero(ctx, "SELECT weight FROM model_group_members WHERE channel_model_id = ?", manualID)
	if member == nil {
		t.Fatal("manual channel model lost its group membership")
	}
	if member.Int("weight", 0) != 6 {
		t.Errorf("manual member weight = %d, want 6", member.Int("weight", 0))
	}
}
