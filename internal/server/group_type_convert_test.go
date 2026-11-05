package server

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"

	"github.com/gin-gonic/gin"
	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// newGroupConvertStore 建立小组类型互转测试所需的最小 schema：
// model_groups 带 type/pattern 列，成员与渠道模型/渠道表供 groups.SyncOne 物化使用。
func newGroupConvertStore(t *testing.T) *store.Store {
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

// putGroup 向 PUT /admin/api/model-groups/{id} 发送 JSON 并解析响应信封。
func putGroup(t *testing.T, r *gin.Engine, id int64, payload string) map[string]any {
	t.Helper()
	w := httptest.NewRecorder()
	req := httptest.NewRequest("PUT", "/admin/api/model-groups/"+strconv.FormatInt(id, 10), bytes.NewBufferString(payload))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", w.Code, w.Body.String())
	}
	var out map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode: %v; body = %s", err, w.Body.String())
	}
	return out
}

func groupRow(t *testing.T, st *store.Store, id int64) (string, string) {
	t.Helper()
	row, _ := st.QueryOne(context.Background(), "SELECT type, pattern FROM model_groups WHERE id = ?", id)
	if row == nil {
		t.Fatal("group row missing")
	}
	return row.Str("type"), row.Str("pattern")
}

func groupMemberIDs(t *testing.T, st *store.Store, groupID int64) []int64 {
	t.Helper()
	rows, _ := st.Query(context.Background(),
		"SELECT channel_model_id FROM model_group_members WHERE group_id = ? ORDER BY sort_order ASC, id ASC", groupID)
	out := make([]int64, 0, len(rows))
	for _, r := range rows {
		out = append(out, r.I64("channel_model_id", 0))
	}
	return out
}

func idContains(ids []int64, want int64) bool {
	for _, id := range ids {
		if id == want {
			return true
		}
	}
	return false
}

// TestGroupTypeConversion 覆盖小组类型双向互转（编辑态）：
//   - 手动小组编辑时请求携带 pattern 键不再被拒（前端表单总是整体提交）；
//   - manual → rule：表达式生效，成员按规则物化（未命中的现有成员移除）；
//   - rule → manual：成员原地保留，表达式作为休眠值保留；
//   - manual → rule 且请求不带表达式：恢复休眠表达式并物化；
//   - 无表达式（库中也没有）时转动态被拒绝。
func TestGroupTypeConversion(t *testing.T) {
	st := newGroupConvertStore(t)
	ctx := context.Background()

	workbuddy, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('workbuddy', 'openai', 1)")
	deepseek, _ := st.Insert(ctx, "INSERT INTO channel_models (channel_id, model_name, enabled) VALUES (?, 'deepseek-v4.1', 1)", workbuddy)
	openai, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('openai', 'openai', 1)")
	gpt, _ := st.Insert(ctx, "INSERT INTO channel_models (channel_id, model_name, enabled) VALUES (?, 'gpt-4o', 1)", openai)

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerModelGroupRoutes(r.Group("/admin/api"), Deps{Store: st})

	groupID, err := st.Insert(ctx,
		"INSERT INTO model_groups (name, type, strategy) VALUES ('g', 'manual', 'sticky')")
	if err != nil {
		t.Fatal(err)
	}
	// 手动成员：gpt + deepseek。
	for i, cm := range []int64{gpt, deepseek} {
		if _, err := st.Insert(ctx,
			"INSERT INTO model_group_members (group_id, channel_model_id, enabled, sort_order) VALUES (?, ?, 1, ?)",
			groupID, cm, i); err != nil {
			t.Fatal(err)
		}
	}

	// 1) 编辑手动小组：表单整体提交会带 pattern 键（空串）——不允许再被拒绝。
	if out := putGroup(t, r, groupID, `{"name":"g","pattern":""}`); out["success"] != true {
		t.Fatalf("manual edit with empty pattern key rejected: %v", out)
	}
	if tp, _ := groupRow(t, st, groupID); tp != "manual" {
		t.Fatalf("type = %q, want manual", tp)
	}

	// 2) manual → rule（带表达式）：成员按规则物化，未命中的 gpt 被移除。
	if out := putGroup(t, r, groupID, `{"type":"rule","pattern":"deepseek"}`); out["success"] != true {
		t.Fatalf("manual->rule rejected: %v", out)
	}
	tp, pattern := groupRow(t, st, groupID)
	if tp != "rule" || pattern != "deepseek" {
		t.Fatalf("after convert: type=%q pattern=%q, want rule/deepseek", tp, pattern)
	}
	members := groupMemberIDs(t, st, groupID)
	if len(members) != 1 || members[0] != deepseek {
		t.Fatalf("members after convert = %v, want [deepseek] (gpt removed by rule)", members)
	}

	// 3) rule → manual：成员原地保留，表达式休眠保留。
	if out := putGroup(t, r, groupID, `{"type":"manual"}`); out["success"] != true {
		t.Fatalf("rule->manual rejected: %v", out)
	}
	tp, pattern = groupRow(t, st, groupID)
	if tp != "manual" || pattern != "deepseek" {
		t.Fatalf("after back-convert: type=%q pattern=%q, want manual/deepseek (dormant)", tp, pattern)
	}
	members = groupMemberIDs(t, st, groupID)
	if len(members) != 1 || members[0] != deepseek {
		t.Fatalf("members must survive rule->manual, got %v", members)
	}

	// 4) manual → rule 不带表达式：恢复休眠表达式并重新物化（gpt 仍不入组）。
	if out := putGroup(t, r, groupID, `{"type":"rule"}`); out["success"] != true {
		t.Fatalf("manual->rule without body pattern (dormant restore) rejected: %v", out)
	}
	members = groupMemberIDs(t, st, groupID)
	if len(members) != 1 || !idContains(members, deepseek) || idContains(members, gpt) {
		t.Fatalf("members after dormant restore = %v, want [deepseek]", members)
	}

	// 5) 库中休眠表达式为空时转动态被拒绝。
	if _, err := st.Exec(ctx, "UPDATE model_groups SET type = 'manual', pattern = '' WHERE id = ?", groupID); err != nil {
		t.Fatal(err)
	}
	out := putGroup(t, r, groupID, `{"type":"rule","pattern":""}`)
	if out["success"] != false {
		t.Fatalf("manual->rule without any pattern must fail, got %v", out)
	}
}

// TestGroupCreateManualWithDormantPattern 覆盖创建路径：手动小组允许携带
// pattern（休眠存储），动态小组仍要求非空表达式。
func TestGroupCreateManualWithDormantPattern(t *testing.T) {
	st := newGroupConvertStore(t)

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerModelGroupRoutes(r.Group("/admin/api"), Deps{Store: st})

	post := func(payload string) map[string]any {
		w := httptest.NewRecorder()
		req := httptest.NewRequest("POST", "/admin/api/model-groups", bytes.NewBufferString(payload))
		req.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, req)
		var out map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
			t.Fatalf("decode: %v; body = %s", err, w.Body.String())
		}
		return out
	}

	if out := post(`{"name":"m","strategy":"sticky","type":"manual","pattern":"/glm/"}`); out["success"] != true {
		t.Fatalf("manual create with dormant pattern rejected: %v", out)
	}
	row, _ := st.QueryOne(context.Background(), "SELECT type, pattern FROM model_groups WHERE name = 'm'")
	if row == nil || row.Str("type") != "manual" || row.Str("pattern") != "/glm/" {
		t.Fatalf("dormant pattern not stored: %+v", row)
	}

	if out := post(`{"name":"r","strategy":"sticky","type":"rule","pattern":""}`); out["success"] != false {
		t.Fatalf("rule create with empty pattern must fail, got %v", out)
	}
}

// TestDynamicMemberAttributesAllowed 覆盖动态成员的开放边界：权重 / 思考强度 / 排序
// 与普通小组一样可调（同步不触碰已有成员行的属性与相对顺序），而成员集合仍由规则
// 维护——手动添加与删除成员被拒绝。
func TestDynamicMemberAttributesAllowed(t *testing.T) {
	st := newGroupConvertStore(t)
	ctx := context.Background()

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerModelGroupRoutes(r.Group("/admin/api"), Deps{Store: st})

	groupID, err := st.Insert(ctx,
		"INSERT INTO model_groups (name, type, pattern, strategy) VALUES ('ds', 'rule', 'deepseek', 'sticky')")
	if err != nil {
		t.Fatal(err)
	}
	m1, err := st.Insert(ctx,
		"INSERT INTO model_group_members (group_id, channel_model_id, enabled, sort_order) VALUES (?, 11, 1, 0)", groupID)
	if err != nil {
		t.Fatal(err)
	}
	m2, err := st.Insert(ctx,
		"INSERT INTO model_group_members (group_id, channel_model_id, enabled, sort_order) VALUES (?, 12, 1, 1)", groupID)
	if err != nil {
		t.Fatal(err)
	}

	// 权重与思考强度可设置。
	w := httptest.NewRecorder()
	req := httptest.NewRequest("PUT", "/admin/api/model-groups/members/"+strconv.FormatInt(m1, 10),
		bytes.NewBufferString(`{"weight":5,"reasoningEffort":"high"}`))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)
	var out map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil || out["success"] != true {
		t.Fatalf("member weight/effort update rejected: %v (body=%s)", out, w.Body.String())
	}
	row, _ := st.QueryOne(ctx, "SELECT weight, reasoning_effort FROM model_group_members WHERE id = ?", m1)
	if row.Int("weight", 0) != 5 || row.Str("reasoning_effort") != "high" {
		t.Fatalf("weight/effort = %d/%q, want 5/high", row.Int("weight", 0), row.Str("reasoning_effort"))
	}

	// 拖拽排序可提交（同步语义保证已有成员相对顺序在后续同步中保持不变）。
	w = httptest.NewRecorder()
	req = httptest.NewRequest("PUT", "/admin/api/model-groups/members/sort",
		bytes.NewBufferString(`{"sortedMemberIds":[`+strconv.FormatInt(m2, 10)+`,`+strconv.FormatInt(m1, 10)+`]}`))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil || out["success"] != true {
		t.Fatalf("member sort rejected: %v", out)
	}
	row, _ = st.QueryOne(ctx, "SELECT sort_order FROM model_group_members WHERE id = ?", m2)
	if row.Int("sort_order", 0) != 0 {
		t.Fatalf("sort_order = %d, want 0 (m2 dragged to front)", row.Int("sort_order", 0))
	}

	// 成员集合仍由规则维护：删除与手动添加被拒绝。
	w = httptest.NewRecorder()
	req = httptest.NewRequest("DELETE", "/admin/api/model-groups/members/"+strconv.FormatInt(m1, 10), nil)
	r.ServeHTTP(w, req)
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil || out["success"] != false {
		t.Fatalf("dynamic member delete must be denied, got %v", out)
	}
	w = httptest.NewRecorder()
	req = httptest.NewRequest("POST", "/admin/api/model-groups/"+strconv.FormatInt(groupID, 10)+"/members",
		bytes.NewBufferString(`{"channelModelIds":[13]}`))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil || out["success"] != false {
		t.Fatalf("dynamic member add must be denied, got %v", out)
	}
}
