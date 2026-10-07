package server

import (
	"context"
	"database/sql"
	"testing"
	"time"

	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// newGroupSummaryStore 建立聚合摘要所需的最小 schema（与 v1.43.0 迁移一致）。
func newGroupSummaryStore(t *testing.T) *store.Store {
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
			description TEXT DEFAULT '', strategy TEXT DEFAULT 'random',
			sticky INTEGER DEFAULT 1, enabled INTEGER DEFAULT 1,
			created_at TEXT, updated_at TEXT)`,
		`CREATE TABLE model_group_members (
			id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL,
			channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
			reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
			enabled INTEGER DEFAULT 1, created_at TEXT)`,
		`CREATE TABLE channel_models (
			id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER NOT NULL,
			model_name TEXT NOT NULL, display_name TEXT, enabled INTEGER DEFAULT 1,
			source TEXT, input TEXT DEFAULT 'text', context_length INTEGER,
			channel_api_key_id INTEGER, created_at TEXT)`,
		`CREATE TABLE channels (
			id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, channel_type TEXT,
			base_url TEXT, enabled INTEGER DEFAULT 1, custom_headers TEXT)`,
		`CREATE TABLE channel_api_keys (
			id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER,
			key_name TEXT, api_key TEXT, enabled INTEGER DEFAULT 1,
			sort_order INTEGER DEFAULT 0)`,
		// request_logs / circuit_breaker_states 用于覆盖小组行的性能聚合与熔断聚合。
		// 列集与 v1.43.0 迁移后的真实表一致：request_logs 没有 channel_model_id，
		// 性能样本按「渠道名 + 渠道模型名」归属。
		`CREATE TABLE request_logs (
			id INTEGER PRIMARY KEY AUTOINCREMENT, trace_id TEXT NOT NULL,
			model_name TEXT DEFAULT '', channel_model_name TEXT DEFAULT '',
			channel_name TEXT DEFAULT '', phase TEXT NOT NULL,
			response_time_ms INTEGER DEFAULT 0, first_byte_ms INTEGER,
			completion_tokens INTEGER DEFAULT 0, created_at TEXT)`,
		`CREATE TABLE circuit_breaker_states (
			id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER,
			channel_model_id INTEGER, channel_api_key_id INTEGER,
			is_open INTEGER DEFAULT 0, fail_count INTEGER DEFAULT 0,
			opened_at TEXT, expire_at TEXT, protocol TEXT,
			last_probe_at TEXT, last_probe_status INTEGER, last_probe_detail TEXT)`,
	} {
		if _, err := db.Exec(stmt); err != nil {
			t.Fatalf("schema: %v", err)
		}
	}
	return store.New(db, nil)
}

// TestGroupMemberSummaryRoutableAggregation 覆盖静态可路由口径：
// 成员启用 + 渠道模型启用 + 渠道启用 + 有可用 Key（绑定/未绑定两分支），
// 且聚合输入与最大上下文只统计可路由成员。
func TestGroupMemberSummaryRoutableAggregation(t *testing.T) {
	st := newGroupSummaryStore(t)
	ctx := context.Background()

	// ch1：未绑定 Key，有一枚启用 Key → 成员可路由（含 image/video/1M）。
	ch1, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch1', 'openai', 1)")
	if _, err := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k', 'sk', 1)", ch1); err != nil {
		t.Fatal(err)
	}
	cm1, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'm1', 1, 'text,image', 1000000)", ch1)

	// ch2：绑定 Key 且启用 → 成员可路由（audio/2M）。
	ch2, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch2', 'openai', 1)")
	k2, _ := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k', 'sk', 1)", ch2)
	cm2, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, 'm2', 1, 'text,audio', 2000000, ?)", ch2, k2)

	// 不可路由成员（不应计入聚合）：
	// ch3：绑定 Key 被禁用；ch4：渠道禁用；ch5：渠道模型禁用；ch6：渠道下无任何启用 Key。
	ch3, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch3', 'openai', 1)")
	k3, _ := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k', 'sk', 0)", ch3)
	cm3, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, 'm3', 1, 'video', 9000000, ?)", ch3, k3)

	ch4, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch4', 'openai', 0)")
	cm4, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'm4', 1, 'video', 8000000)", ch4)

	ch5, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch5', 'openai', 1)")
	cm5, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'm5', 0, 'video', 7000000)", ch5)

	ch6, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch6', 'openai', 1)")
	cm6, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'm6', 1, 'video', 6000000)", ch6)

	// ch7：渠道模型禁用（成员本身启用）——成员不可路由。
	ch7, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch7', 'openai', 1)")
	cm7, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'm7', 0, 'video', 5000000)", ch7)
	_ = cm4
	_ = cm6

	groupID, err := st.Insert(ctx, "INSERT INTO model_groups (name, strategy) VALUES ('g', 'random')")
	if err != nil {
		t.Fatal(err)
	}
	// 成员顺序故意打乱，验证 text 优先的稳定输出。
	members := []struct{ cmID, sort int64 }{
		{cm3, 5}, {cm2, 4}, {cm1, 3}, {cm5, 2}, {cm4, 1}, {cm6, 0}, {cm7, 6},
	}
	for _, m := range members {
		if _, err := st.Insert(ctx,
			"INSERT INTO model_group_members (group_id, channel_model_id, enabled, sort_order) VALUES (?, ?, 1, ?)",
			groupID, m.cmID, m.sort); err != nil {
			t.Fatal(err)
		}
	}

	s := groupMemberSummaryOf(ctx, st, groupID)
	if s.total != 7 {
		t.Errorf("total = %d, want 7 (all enabled members)", s.total)
	}
	if s.routable != 2 {
		t.Errorf("routable = %d, want 2 (only ch1/ch2 members pass member+model+channel+key checks)", s.routable)
	}
	if s.input == nil || *s.input != "text,image,audio" {
		t.Errorf("input = %v, want text,image,audio (union of routable members, text first)", s.input)
	}
	if s.maxContext == nil || *s.maxContext != 2000000 {
		t.Errorf("maxContext = %v, want 2000000 (max of routable members)", s.maxContext)
	}
}

// TestGroupMemberSummaryUnknownContextAndDisabledMember 覆盖边界：
// 上下文全未知（NULL/0）→ maxContext 为 nil；成员被禁用 → 完全不参与统计。
func TestGroupMemberSummaryUnknownContextAndDisabledMember(t *testing.T) {
	st := newGroupSummaryStore(t)
	ctx := context.Background()

	ch, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch', 'openai', 1)")
	if _, err := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k', 'sk', 1)", ch); err != nil {
		t.Fatal(err)
	}
	cmKnown, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'known', 1, 'text,image', 300000)", ch)
	cmUnknown, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'unknown', 1, 'text', NULL)", ch)
	cmZero, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'zero', 1, 'text', 0)", ch)
	cmDisabled, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'disabled', 1, 'video', 9999999)", ch)
	_ = cmUnknown
	_ = cmZero

	groupID, _ := st.Insert(ctx, "INSERT INTO model_groups (name, strategy) VALUES ('g', 'random')")
	for _, m := range []struct {
		cmID    int64
		enabled int
	}{
		{cmKnown, 1}, {cmDisabled, 0},
	} {
		if _, err := st.Insert(ctx,
			"INSERT INTO model_group_members (group_id, channel_model_id, enabled) VALUES (?, ?, ?)",
			groupID, m.cmID, m.enabled); err != nil {
			t.Fatal(err)
		}
	}

	// 禁用成员不计入 total（total 口径 = m.enabled=1）。
	s := groupMemberSummaryOf(ctx, st, groupID)
	if s.total != 1 {
		t.Errorf("total = %d, want 1 (disabled member excluded)", s.total)
	}
	if s.routable != 1 {
		t.Errorf("routable = %d, want 1", s.routable)
	}
	if s.maxContext == nil || *s.maxContext != 300000 {
		t.Errorf("maxContext = %v, want 300000", s.maxContext)
	}
}

// TestGroupMemberSummaryEmptyGroup 覆盖空小组：全部为零值、无输入聚合。
func TestGroupMemberSummaryEmptyGroup(t *testing.T) {
	st := newGroupSummaryStore(t)
	ctx := context.Background()
	groupID, _ := st.Insert(ctx, "INSERT INTO model_groups (name, strategy) VALUES ('empty', 'random')")

	s := groupMemberSummaryOf(ctx, st, groupID)
	if s.total != 0 || s.routable != 0 || s.input != nil || s.maxContext != nil {
		t.Errorf("empty group summary = %+v, want zero values", s)
	}
}

// TestGroupMemberSummaryPerfStats 覆盖成员性能聚合。回归要点：request_logs 没有
// channel_model_id 列，性能样本只能按「渠道名 + 渠道模型名」归属；早前的实现查了
// 该列，错误被吞掉后小组行永远显示「暂无数据」。
func TestGroupMemberSummaryPerfStats(t *testing.T) {
	st := newGroupSummaryStore(t)
	ctx := context.Background()

	ch1, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch-perf-1', 'openai', 1)")
	if _, err := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k', 'sk', 1)", ch1); err != nil {
		t.Fatal(err)
	}
	cm1, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'perf-m1', 1, 'text', 100000)", ch1)

	ch2, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch-perf-2', 'openai', 1)")
	if _, err := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k', 'sk', 1)", ch2); err != nil {
		t.Fatal(err)
	}
	cm2, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length) VALUES (?, 'perf-m2', 1, 'text', 100000)", ch2)

	groupID, _ := st.Insert(ctx, "INSERT INTO model_groups (name, strategy) VALUES ('perf', 'random')")
	for _, cm := range []int64{cm1, cm2} {
		if _, err := st.Insert(ctx,
			"INSERT INTO model_group_members (group_id, channel_model_id, enabled) VALUES (?, ?, 1)",
			groupID, cm); err != nil {
			t.Fatal(err)
		}
	}

	recent := jtime.FormatApp(time.Now().UTC().Add(-time.Hour))
	log := func(channelName, modelName string, firstByteMs int64, completion, responseMs int) {
		t.Helper()
		if _, err := st.Insert(ctx,
			`INSERT INTO request_logs (trace_id, channel_name, channel_model_name, phase, first_byte_ms, completion_tokens, response_time_ms, created_at)
			 VALUES ('t', ?, ?, 'success', ?, ?, ?, ?)`,
			channelName, modelName, firstByteMs, completion, responseMs, recent); err != nil {
			t.Fatal(err)
		}
	}
	// 成员 1：TTFT 1000ms；成员 2：TTFT 3000ms → 总体平均 2000ms。
	log("ch-perf-1", "perf-m1", 1000, 100, 1000)
	log("ch-perf-2", "perf-m2", 3000, 100, 1000)
	// 24h 之外的样本与其它渠道的样本都不应计入。
	if _, err := st.Insert(ctx,
		`INSERT INTO request_logs (trace_id, channel_name, channel_model_name, phase, first_byte_ms, completion_tokens, response_time_ms, created_at)
		 VALUES ('t', 'ch-perf-1', 'perf-m1', 'success', 99999, 1, 1, ?)`,
		jtime.FormatApp(time.Now().UTC().Add(-48*time.Hour))); err != nil {
		t.Fatal(err)
	}
	log("ch-other", "other-m", 7777, 100, 1000)

	s := groupMemberSummaryOf(ctx, st, groupID)
	if s.ttftMs == nil {
		t.Fatal("ttftMs = nil; group member performance samples were not aggregated")
	}
	if *s.ttftMs != 2000 {
		t.Errorf("ttftMs = %d, want 2000 (mean of the two routable members)", *s.ttftMs)
	}
	if s.sampleCount == nil || *s.sampleCount != 2 {
		t.Errorf("sampleCount = %v, want 2", s.sampleCount)
	}
	if s.outputSpeed == nil {
		t.Error("outputSpeed = nil, want the member mean")
	}
}

// TestGroupMemberSummaryBreakerAggregate 覆盖熔断聚合：仅当全部可路由成员都熔断时
// 小组才算「熔断中」，部分熔断不告警。
func TestGroupMemberSummaryBreakerAggregate(t *testing.T) {
	st := newGroupSummaryStore(t)
	ctx := context.Background()

	var cms []int64
	for i, name := range []string{"brk-1", "brk-2"} {
		ch, _ := st.Insert(ctx,
			"INSERT INTO channels (name, channel_type, enabled) VALUES (?, 'openai', 1)", "ch-"+name)
		if _, err := st.Insert(ctx,
			"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k', 'sk', 1)", ch); err != nil {
			t.Fatal(err)
		}
		cm, _ := st.Insert(ctx,
			"INSERT INTO channel_models (channel_id, model_name, enabled, input) VALUES (?, ?, 1, 'text')", ch, name)
		cms = append(cms, cm)
		_ = i
	}
	groupID, _ := st.Insert(ctx, "INSERT INTO model_groups (name, strategy) VALUES ('brk', 'random')")
	for _, cm := range cms {
		if _, err := st.Insert(ctx,
			"INSERT INTO model_group_members (group_id, channel_model_id, enabled) VALUES (?, ?, 1)",
			groupID, cm); err != nil {
			t.Fatal(err)
		}
	}

	// 部分熔断：只有成员 1 的渠道级熔断打开 → 不显示「熔断中」。
	ch1, _ := st.QueryOne(ctx, "SELECT id FROM channels WHERE name = 'ch-brk-1'")
	if _, err := st.Insert(ctx,
		`INSERT INTO circuit_breaker_states (channel_id, channel_model_id, is_open, fail_count)
		 VALUES (?, ?, 1, 3)`, ch1.I64("id", 0), cms[0]); err != nil {
		t.Fatal(err)
	}
	if s := groupMemberSummaryOf(ctx, st, groupID); s.breakerAggregate != "partial" {
		t.Errorf("breakerAggregate = %q, want partial when only some members are broken", s.breakerAggregate)
	}

	// 全部熔断 → "all"；两个成员都是模型级熔断（state 带 channel_model_id），
	// 聚合级别应为 model。
	ch2, _ := st.QueryOne(ctx, "SELECT id FROM channels WHERE name = 'ch-brk-2'")
	if _, err := st.Insert(ctx,
		`INSERT INTO circuit_breaker_states (channel_id, channel_model_id, is_open, fail_count)
		 VALUES (?, ?, 1, 3)`, ch2.I64("id", 0), cms[1]); err != nil {
		t.Fatal(err)
	}
	s := groupMemberSummaryOf(ctx, st, groupID)
	if s.breakerAggregate != "all" {
		t.Errorf("breakerAggregate = %q, want all when every routable member is broken", s.breakerAggregate)
	}
	if s.breakerScope != "model" {
		t.Errorf("breakerScope = %q, want model for model-level member breakers", s.breakerScope)
	}
}

// TestGroupMemberSummaryBreakerScopeMerge 覆盖熔断级别聚合：成员级别混合时取
// 包含关系最广的一档（both > channel > model），单个渠道级成员即可把小组
// 从「模型级」提升为「渠道级」——影响面不能被低报。
func TestGroupMemberSummaryBreakerScopeMerge(t *testing.T) {
	st := newGroupSummaryStore(t)
	ctx := context.Background()

	var cms []int64
	for _, name := range []string{"ms-1", "ms-2"} {
		ch, _ := st.Insert(ctx,
			"INSERT INTO channels (name, channel_type, enabled) VALUES (?, 'openai', 1)", "ch-"+name)
		if _, err := st.Insert(ctx,
			"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k', 'sk', 1)", ch); err != nil {
			t.Fatal(err)
		}
		cm, _ := st.Insert(ctx,
			"INSERT INTO channel_models (channel_id, model_name, enabled, input) VALUES (?, ?, 1, 'text')", ch, name)
		cms = append(cms, cm)
	}
	groupID, _ := st.Insert(ctx, "INSERT INTO model_groups (name, strategy) VALUES ('ms', 'random')")
	for _, cm := range cms {
		if _, err := st.Insert(ctx,
			"INSERT INTO model_group_members (group_id, channel_model_id, enabled) VALUES (?, ?, 1)",
			groupID, cm); err != nil {
			t.Fatal(err)
		}
	}

	// 成员 1：模型级熔断（带 channel_model_id）。
	ch1, _ := st.QueryOne(ctx, "SELECT id FROM channels WHERE name = 'ch-ms-1'")
	if _, err := st.Insert(ctx,
		`INSERT INTO circuit_breaker_states (channel_id, channel_model_id, is_open, fail_count)
		 VALUES (?, ?, 1, 3)`, ch1.I64("id", 0), cms[0]); err != nil {
		t.Fatal(err)
	}
	// 成员 2：渠道级熔断（channel_model_id 为 NULL）→ 合并结果应为 channel。
	ch2, _ := st.QueryOne(ctx, "SELECT id FROM channels WHERE name = 'ch-ms-2'")
	if _, err := st.Insert(ctx,
		`INSERT INTO circuit_breaker_states (channel_id, channel_model_id, is_open, fail_count)
		 VALUES (?, NULL, 1, 3)`, ch2.I64("id", 0)); err != nil {
		t.Fatal(err)
	}

	s := groupMemberSummaryOf(ctx, st, groupID)
	if s.breakerAggregate != "all" {
		t.Fatalf("breakerAggregate = %q, want all", s.breakerAggregate)
	}
	if s.breakerScope != "channel" {
		t.Errorf("breakerScope = %q, want channel (widest scope wins over model)", s.breakerScope)
	}
}

// TestMergeBreakerScopes 直接覆盖级别合并的优先级。
func TestMergeBreakerScopes(t *testing.T) {
	cases := []struct {
		in   []string
		want string
	}{
		{nil, ""},
		{[]string{"model"}, "model"},
		{[]string{"channel"}, "channel"},
		{[]string{"both"}, "both"},
		{[]string{"model", "channel"}, "channel"},
		{[]string{"model", "both"}, "both"},
		{[]string{"channel", "both"}, "both"},
		{[]string{"model", "channel", "both"}, "both"},
	}
	for _, c := range cases {
		set := map[string]bool{}
		for _, s := range c.in {
			set[s] = true
		}
		if got := mergeBreakerScopes(set); got != c.want {
			t.Errorf("mergeBreakerScopes(%v) = %q, want %q", c.in, got, c.want)
		}
	}
}

// TestGroupMemberSummaryNoLogsStaysEmpty 覆盖无日志时的降级：不报错、值为 nil。
func TestGroupMemberSummaryNoLogsStaysEmpty(t *testing.T) {
	st := newGroupSummaryStore(t)
	ctx := context.Background()
	ch, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch-nolog', 'openai', 1)")
	if _, err := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k', 'sk', 1)", ch); err != nil {
		t.Fatal(err)
	}
	cm, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input) VALUES (?, 'nolog', 1, 'text')", ch)
	groupID, _ := st.Insert(ctx, "INSERT INTO model_groups (name, strategy) VALUES ('nolog', 'random')")
	if _, err := st.Insert(ctx,
		"INSERT INTO model_group_members (group_id, channel_model_id, enabled) VALUES (?, ?, 1)", groupID, cm); err != nil {
		t.Fatal(err)
	}
	s := groupMemberSummaryOf(ctx, st, groupID)
	if s.ttftMs != nil || s.sampleCount != nil || s.outputSpeed != nil {
		t.Errorf("summary without logs = %+v, want nil performance fields", s)
	}
	if s.breakerAggregate != "" {
		t.Errorf("breakerAggregate = %q, want empty when nothing is broken", s.breakerAggregate)
	}
}

// TestOrderedInputTypes 验证模态顺序规范：text 优先，image/video/audio 按固定次序，
// 未知模态按字母序殿后。
func TestOrderedInputTypes(t *testing.T) {
	got := orderedInputTypes(map[string]bool{"audio": true, "text": true, "zzz": true, "video": true, "image": true, "abc": true})
	want := []string{"text", "image", "video", "audio", "abc", "zzz"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v, want %v", got, want)
		}
	}
}
