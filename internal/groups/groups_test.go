package groups

import (
	"context"
	"database/sql"
	"testing"

	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// newSyncStore 建立动态小组成员同步所需的最小 schema。
func newSyncStore(t *testing.T) *store.Store {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=busy_timeout(30000)")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	for _, stmt := range []string{
		`CREATE TABLE model_groups (
			id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL,
			type TEXT DEFAULT 'manual', pattern TEXT DEFAULT '',
			strategy TEXT DEFAULT 'sticky', sticky INTEGER DEFAULT 1,
			enabled INTEGER DEFAULT 1, created_at TEXT, updated_at TEXT)`,
		`CREATE TABLE model_group_members (
			id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL,
			channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
			reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
			enabled INTEGER DEFAULT 1, created_at TEXT)`,
		`CREATE TABLE channels (
			id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, channel_type TEXT,
			base_url TEXT, enabled INTEGER DEFAULT 1, custom_headers TEXT)`,
		`CREATE TABLE channel_models (
			id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER NOT NULL,
			model_name TEXT NOT NULL, display_name TEXT, enabled INTEGER DEFAULT 1,
			source TEXT, input TEXT DEFAULT 'text', context_length INTEGER,
			channel_api_key_id INTEGER, created_at TEXT)`,
	} {
		if _, err := db.Exec(stmt); err != nil {
			t.Fatalf("schema: %v", err)
		}
	}
	return store.New(db, nil)
}

// seedSyncModel 建一个启用的渠道模型，返回其 id。
func seedSyncModel(t *testing.T, st *store.Store, modelName, channelName string) int64 {
	t.Helper()
	ctx := context.Background()
	chID, err := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES (?, 'openai', 1)", channelName)
	if err != nil {
		t.Fatal(err)
	}
	cmID, err := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled) VALUES (?, ?, 1)", chID, modelName)
	if err != nil {
		t.Fatal(err)
	}
	return cmID
}

func memberOrder(t *testing.T, st *store.Store, groupID int64) []int64 {
	t.Helper()
	rows, _ := st.Query(context.Background(),
		"SELECT channel_model_id FROM model_group_members WHERE group_id = ? ORDER BY sort_order ASC, id ASC", groupID)
	out := make([]int64, 0, len(rows))
	for _, r := range rows {
		out = append(out, r.I64("channel_model_id", 0))
	}
	return out
}

// TestSyncAllMaterializesMembers 覆盖动态小组的核心语义：
// 新命中追加到成员表末尾；不再命中的成员行删除；重新出现时按新加入处理（排最后）。
func TestSyncAllMaterializesMembers(t *testing.T) {
	st := newSyncStore(t)
	ctx := context.Background()

	glm := seedSyncModel(t, st, "deepseek-v4.1-flash", "workbuddy")
	gpt := seedSyncModel(t, st, "gpt-4o", "openai")

	groupID, err := st.Insert(ctx,
		"INSERT INTO model_groups (name, type, pattern, strategy) VALUES ('ds', 'rule', 'deepseek', 'failover')")
	if err != nil {
		t.Fatal(err)
	}

	if _, err := SyncAll(ctx, st); err != nil {
		t.Fatal(err)
	}
	if got := memberOrder(t, st, groupID); len(got) != 1 || got[0] != glm {
		t.Fatalf("members = %v, want [glm]", got)
	}

	// 新增命中模型 → 追加到末尾，已有成员顺序不变。
	flash := seedSyncModel(t, st, "deepseek-4.1-flash", "trae")
	if _, err := SyncAll(ctx, st); err != nil {
		t.Fatal(err)
	}
	if got := memberOrder(t, st, groupID); len(got) != 2 || got[0] != glm || got[1] != flash {
		t.Fatalf("members after new match = %v, want [glm flash]", got)
	}

	// 渠道模型被禁用 → 移出成员表；重新启用 → 按新成员追加到最后。
	if _, err := st.Exec(ctx, "UPDATE channel_models SET enabled = 0 WHERE id = ?", glm); err != nil {
		t.Fatal(err)
	}
	if _, err := SyncAll(ctx, st); err != nil {
		t.Fatal(err)
	}
	if got := memberOrder(t, st, groupID); len(got) != 1 || got[0] != flash {
		t.Fatalf("members after disable = %v, want [flash]", got)
	}
	if _, err := st.Exec(ctx, "UPDATE channel_models SET enabled = 1 WHERE id = ?", glm); err != nil {
		t.Fatal(err)
	}
	if _, err := SyncAll(ctx, st); err != nil {
		t.Fatal(err)
	}
	if got := memberOrder(t, st, groupID); len(got) != 2 || got[0] != flash || got[1] != glm {
		t.Fatalf("members after re-enable = %v, want [flash glm]（重新出现按新加入排最后）", got)
	}

	// 非命中的模型（gpt）始终不入组。
	for _, id := range memberOrder(t, st, groupID) {
		if id == gpt {
			t.Fatal("non-matching model joined the dynamic group")
		}
	}
}

// TestSyncOneGuards 覆盖 SyncOne 的守卫：手动小组 / 禁用的动态小组 / 表达式为空
// 都不做任何成员变更。
func TestSyncOneGuards(t *testing.T) {
	st := newSyncStore(t)
	ctx := context.Background()

	seedSyncModel(t, st, "deepseek-v4.1-flash", "workbuddy")
	manual, _ := st.Insert(ctx, "INSERT INTO model_groups (name, type, pattern) VALUES ('m', 'manual', 'deepseek')")
	disabled, _ := st.Insert(ctx, "INSERT INTO model_groups (name, type, pattern, enabled) VALUES ('d', 'rule', 'deepseek', 0)")
	blank, _ := st.Insert(ctx, "INSERT INTO model_groups (name, type, pattern) VALUES ('b', 'rule', '  ')")

	for _, id := range []int64{manual, disabled, blank} {
		n, err := SyncOne(ctx, st, id)
		if err != nil {
			t.Fatalf("SyncOne(%d): %v", id, err)
		}
		if n != 0 {
			t.Errorf("SyncOne(%d) changed %d members, want 0", id, n)
		}
	}
	rows, _ := st.Query(ctx, "SELECT COUNT(*) cnt FROM model_group_members")
	if rows[0].I64("cnt", 0) != 0 {
		t.Fatalf("members were materialized for guarded groups")
	}
}
