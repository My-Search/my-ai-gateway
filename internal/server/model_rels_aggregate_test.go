package server

import (
	"context"
	"database/sql"
	"testing"

	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// newAggregateStore 建立入口模型聚合（aggregateModelRels）所需的最小 schema。
func newAggregateStore(t *testing.T) *store.Store {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=busy_timeout(30000)")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	for _, stmt := range []string{
		`CREATE TABLE models (
			id INTEGER PRIMARY KEY AUTOINCREMENT, model_name TEXT NOT NULL,
			rel_mode TEXT DEFAULT 'self_add', inherit_from_model_id INTEGER)`,
		`CREATE TABLE channels (
			id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, channel_type TEXT,
			base_url TEXT, enabled INTEGER DEFAULT 1, custom_headers TEXT)`,
		`CREATE TABLE channel_api_keys (
			id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER,
			key_name TEXT, api_key TEXT, enabled INTEGER DEFAULT 1,
			sort_order INTEGER DEFAULT 0)`,
		`CREATE TABLE channel_models (
			id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER NOT NULL,
			model_name TEXT NOT NULL, display_name TEXT, enabled INTEGER DEFAULT 1,
			source TEXT, input TEXT DEFAULT 'text', context_length INTEGER,
			channel_api_key_id INTEGER, created_at TEXT)`,
		`CREATE TABLE model_channel_rels (
			id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER NOT NULL,
			channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
			reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
			enabled INTEGER DEFAULT 1, created_at TEXT)`,
		`CREATE TABLE model_groups (
			id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL,
			description TEXT DEFAULT '', strategy TEXT DEFAULT 'random',
			sticky INTEGER DEFAULT 1, enabled INTEGER DEFAULT 1,
			created_at TEXT, updated_at TEXT)`,
		`CREATE TABLE model_group_rels (
			id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER NOT NULL,
			group_id INTEGER NOT NULL, sort_order INTEGER DEFAULT 0,
			reasoning_effort TEXT, weight INTEGER DEFAULT 1,
			enabled INTEGER DEFAULT 1, created_at TEXT)`,
		`CREATE TABLE model_group_members (
			id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL,
			channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
			reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
			enabled INTEGER DEFAULT 1, created_at TEXT)`,
		`CREATE TABLE model_rule_rels (
			id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER NOT NULL,
			name TEXT NOT NULL DEFAULT '', pattern TEXT NOT NULL DEFAULT '',
			weight INTEGER DEFAULT 1, enabled INTEGER DEFAULT 1,
			sort_order INTEGER DEFAULT 0, created_at TEXT)`,
	} {
		if _, err := db.Exec(stmt); err != nil {
			t.Fatalf("schema: %v", err)
		}
	}
	return store.New(db, nil)
}

// TestAggregateModelRelsCopiesSnapshot 覆盖入口模型聚合的复制口径：
// 沿继承链解析、权重/思考强度带入、禁用行与已删渠道模型跳过、
// 与目标已有关联及同请求内重复源去重、序号接在目标现有最大值之后。
func TestAggregateModelRelsCopiesSnapshot(t *testing.T) {
	st := newAggregateStore(t)
	ctx := context.Background()

	ch1, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch1', 'openai', 1)")
	cm1, _ := st.Insert(ctx, "INSERT INTO channel_models (channel_id, model_name, enabled) VALUES (?, 'm1', 1)", ch1)
	ch2, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch2', 'openai', 1)")
	cm2, _ := st.Insert(ctx, "INSERT INTO channel_models (channel_id, model_name, enabled) VALUES (?, 'm2', 1)", ch2)
	ch3, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch3', 'openai', 1)")
	cm3, _ := st.Insert(ctx, "INSERT INTO channel_models (channel_id, model_name, enabled) VALUES (?, 'm3', 1)", ch3)

	groupID, _ := st.Insert(ctx, "INSERT INTO model_groups (name, strategy) VALUES ('G', 'failover')")

	// 源模型 S1：cm1（权重 7 + 思考强度 high）、cm3（禁用行，不带入）、
	// 指向不存在渠道模型的悬空行（不带入）、小组 G（思考强度 low）。
	s1, _ := st.Insert(ctx, "INSERT INTO models (model_name) VALUES ('src1')")
	for _, rel := range []struct {
		cm      int64
		weight  int
		effort  string
		sort    int
		enabled int
	}{{cm1, 7, "high", 0, 1}, {cm3, 1, "", 1, 0}} {
		if _, err := st.Exec(ctx,
			"INSERT INTO model_channel_rels (model_id, channel_model_id, weight, reasoning_effort, sort_order, enabled, created_at) VALUES (?, ?, ?, ?, ?, ?, '2026-01-01 00:00:00')",
			s1, rel.cm, rel.weight, rel.effort, rel.sort, rel.enabled); err != nil {
			t.Fatal(err)
		}
	}
	// 悬空渠道模型关联（channel_models 里没有 9999）
	if _, err := st.Exec(ctx,
		"INSERT INTO model_channel_rels (model_id, channel_model_id, sort_order, enabled, created_at) VALUES (?, 9999, 2, 1, '2026-01-01 00:00:00')", s1); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Exec(ctx,
		"INSERT INTO model_group_rels (model_id, group_id, sort_order, weight, reasoning_effort, enabled, created_at) VALUES (?, ?, 3, 250, 'low', 1, '2026-01-01 00:00:00')", s1, groupID); err != nil {
		t.Fatal(err)
	}

	// 中间模型 M 继承 S1：聚合 M 应解析到 S1 的关联。
	mid, _ := st.Insert(ctx, "INSERT INTO models (model_name, rel_mode, inherit_from_model_id) VALUES ('mid', 'inherit', ?)", s1)
	// cm2 是 M 直接关联（继承模式下自身关联不参与路由/复制，仅用于验证不误带入）
	if _, err := st.Exec(ctx,
		"INSERT INTO model_channel_rels (model_id, channel_model_id, sort_order, created_at) VALUES (?, ?, 9, '2026-01-01 00:00:00')", mid, cm2); err != nil {
		t.Fatal(err)
	}

	// 目标 T：已有一条 cm1 关联（sort_order=0），聚合后应保留且不重复。
	target, _ := st.Insert(ctx, "INSERT INTO models (model_name) VALUES ('target')")
	if _, err := st.Exec(ctx,
		"INSERT INTO model_channel_rels (model_id, channel_model_id, sort_order, created_at) VALUES (?, ?, 0, '2026-01-01 00:00:00')", target, cm1); err != nil {
		t.Fatal(err)
	}

	nextSort := int64(1)
	added := aggregateModelRels(ctx, st, target, []int64{mid, target, s1, 424242}, &nextSort, "2026-01-02 00:00:00")

	// mid 与 s1 指向同一份关联：第一遍带入小组 G（cm1 重复跳过、禁用/悬空跳过），
	// 第二遍全部命中去重；target 自身与不存在的 424242 均跳过。
	if added != 1 {
		t.Fatalf("added = %d, want 1", added)
	}
	if nextSort != 2 {
		t.Errorf("nextSort = %d, want 2", nextSort)
	}

	// 目标最终只剩原有 cm1 + 新增小组 G。
	relRows, _ := st.Query(ctx, "SELECT channel_model_id, weight, reasoning_effort, sort_order FROM model_channel_rels WHERE model_id=? ORDER BY id", target)
	if len(relRows) != 1 || relRows[0].I64("channel_model_id", 0) != cm1 {
		t.Fatalf("target channel rels = %d rows, want exactly the pre-existing cm1", len(relRows))
	}
	groupRels, _ := st.Query(ctx, "SELECT group_id, weight, reasoning_effort, sort_order FROM model_group_rels WHERE model_id=?", target)
	if len(groupRels) != 1 {
		t.Fatalf("target group rels = %d, want 1", len(groupRels))
	}
	if groupRels[0].I64("group_id", 0) != groupID {
		t.Errorf("group rel group_id = %d, want %d", groupRels[0].I64("group_id", 0), groupID)
	}
	if groupRels[0].Int("weight", 0) != 250 {
		t.Errorf("copied group weight = %d, want 250", groupRels[0].Int("weight", 0))
	}
	if groupRels[0].Str("reasoning_effort") != "low" {
		t.Errorf("group rel reasoning_effort = %q, want low", groupRels[0].Str("reasoning_effort"))
	}
	if groupRels[0].I64("sort_order", 0) != 1 {
		t.Errorf("group rel sort_order = %d, want 1（接在目标现有最大序号之后）", groupRels[0].I64("sort_order", 0))
	}

	// 权重与思考强度随快照带入：清掉目标的渠道模型关联（小组关联保留）后重聚合。
	if _, err := st.Exec(ctx, "DELETE FROM model_channel_rels WHERE model_id=?", target); err != nil {
		t.Fatal(err)
	}
	nextSort = 0
	added = aggregateModelRels(ctx, st, target, []int64{s1}, &nextSort, "2026-01-03 00:00:00")
	if added != 1 { // 仅 cm1（小组关联仍在 → 去重跳过；cm3 禁用、悬空跳过）
		t.Fatalf("second added = %d, want 1", added)
	}
	cmRel, _ := st.QueryOne(ctx, "SELECT weight, reasoning_effort, sort_order FROM model_channel_rels WHERE model_id=? AND channel_model_id=?", target, cm1)
	if cmRel == nil {
		t.Fatal("cm1 rel missing after second aggregation")
	}
	if cmRel.Int("weight", 0) != 7 {
		t.Errorf("copied weight = %d, want 7", cmRel.Int("weight", 0))
	}
	if cmRel.Str("reasoning_effort") != "high" {
		t.Errorf("copied reasoning_effort = %q, want high", cmRel.Str("reasoning_effort"))
	}
	if cmRel.I64("sort_order", 0) != 0 {
		t.Errorf("copied sort_order = %d, want 0", cmRel.I64("sort_order", 0))
	}
}
