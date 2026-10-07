package server

import (
	"context"
	"testing"
	"time"

	"github.com/my-search/my-ai-gateway/internal/jtime"
)

// TestFillGroupMemberDisplayPerfAndProbe 覆盖小组成员行的展示字段：
// 成员行与入口模型关联行必须显示同一批信息——24h 性能样本（TTFT/速度/样本数）、
// 熔断状态与级别，以及最近一次探测的时间/状态码/详情与熔断协议。
func TestFillGroupMemberDisplayPerfAndProbe(t *testing.T) {
	st := newGroupSummaryStore(t)
	ctx := context.Background()

	ch, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch-member', 'openai', 1)")
	if _, err := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'master', 'sk', 1)", ch); err != nil {
		t.Fatal(err)
	}
	cm, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input) VALUES (?, 'member-model', 1, 'text')", ch)
	groupID, _ := st.Insert(ctx, "INSERT INTO model_groups (name, strategy) VALUES ('member', 'random')")
	memberID, _ := st.Insert(ctx,
		"INSERT INTO model_group_members (group_id, channel_model_id, enabled) VALUES (?, ?, 1)", groupID, cm)

	// 24h 性能样本：TTFT 1200ms、100 tokens / 1000ms → 100 tokens/s。
	recent := jtime.FormatApp(time.Now().UTC().Add(-time.Hour))
	if _, err := st.Insert(ctx,
		`INSERT INTO request_logs (trace_id, channel_name, channel_model_name, phase, first_byte_ms, completion_tokens, response_time_ms, created_at)
		 VALUES ('t', 'ch-member', 'member-model', 'success', 1200, 100, 1000, ?)`, recent); err != nil {
		t.Fatal(err)
	}

	// 模型级熔断，带最近一次探测结果与协议。
	now := time.Now().UTC()
	if _, err := st.Insert(ctx,
		`INSERT INTO circuit_breaker_states
		   (channel_id, channel_model_id, is_open, fail_count, opened_at, expire_at, protocol, last_probe_at, last_probe_status, last_probe_detail)
		 VALUES (?, ?, 1, 5, ?, ?, 'anthropic-messages', ?, 429, 'rate limited')`,
		ch, cm, jtime.FormatApp(now), jtime.FormatApp(now.Add(time.Hour)), jtime.FormatApp(now)); err != nil {
		t.Fatal(err)
	}

	members := loadGroupMembers(ctx, st, groupID)
	if len(members) != 1 {
		t.Fatalf("members = %d, want 1", len(members))
	}
	m := members[0]
	if m.ID != memberID {
		t.Errorf("member id = %d, want %d", m.ID, memberID)
	}

	// 性能字段：口径与渠道模型行一致。
	if m.TTFTMs == nil || *m.TTFTMs != 1200 {
		t.Errorf("ttftMs = %v, want 1200", m.TTFTMs)
	}
	if m.SampleCount == nil || *m.SampleCount != 1 {
		t.Errorf("sampleCount = %v, want 1", m.SampleCount)
	}
	if m.OutputSpeed == nil || *m.OutputSpeed != 100 {
		t.Errorf("outputSpeed = %v, want 100", m.OutputSpeed)
	}

	// 熔断字段：状态 + 级别。
	if m.CircuitBroken == nil || *m.CircuitBroken != 1 {
		t.Errorf("circuitBroken = %v, want 1", m.CircuitBroken)
	}
	if m.CircuitBrokenScope == nil || *m.CircuitBrokenScope != "model" {
		t.Errorf("circuitBrokenScope = %v, want model", m.CircuitBrokenScope)
	}

	// 探测字段：时间 / 状态码 / 详情，与入口模型关联行同一读法。
	if m.LastProbeAt.IsZero() {
		t.Error("circuitBrokenLastProbeAt is zero; probe time was not attached")
	}
	if m.LastProbeStatus == nil || *m.LastProbeStatus != 429 {
		t.Errorf("lastProbeStatus = %v, want 429", m.LastProbeStatus)
	}
	if m.LastProbeDetail == nil || *m.LastProbeDetail != "rate limited" {
		t.Errorf("lastProbeDetail = %v, want 'rate limited'", m.LastProbeDetail)
	}
	if len(m.CircuitBrokenProtocols) != 1 || m.CircuitBrokenProtocols[0].Protocol != "anthropic-messages" {
		t.Errorf("circuitBrokenProtocols = %+v, want one anthropic-messages entry", m.CircuitBrokenProtocols)
	}
}

// TestClearChannelModelBreaker 覆盖按渠道模型解除熔断：绑定 Key 时只清该 Key 的
// 状态，未绑定 Key 时清该渠道模型下的全部状态。
func TestClearChannelModelBreaker(t *testing.T) {
	st := newGroupSummaryStore(t)
	ctx := context.Background()

	ch, _ := st.Insert(ctx, "INSERT INTO channels (name, channel_type, enabled) VALUES ('ch-recover', 'openai', 1)")
	k1, _ := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k1', 'sk', 1)", ch)
	k2, _ := st.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled) VALUES (?, 'k2', 'sk', 1)", ch)

	// 未绑定 Key 的渠道模型：两条 Key 级状态都应被清除。
	cmUnbound, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled) VALUES (?, 'unbound', 1)", ch)
	for _, k := range []int64{k1, k2} {
		if _, err := st.Insert(ctx,
			"INSERT INTO circuit_breaker_states (channel_id, channel_model_id, channel_api_key_id, is_open, fail_count) VALUES (?, ?, ?, 1, 3)",
			ch, cmUnbound, k); err != nil {
			t.Fatal(err)
		}
	}
	if got := clearChannelModelBreaker(ctx, st, cmUnbound); got != 2 {
		t.Errorf("recovered = %d, want 2 (every open state on the unbound channel model)", got)
	}
	if rows, _ := st.Query(ctx, "SELECT id FROM circuit_breaker_states WHERE channel_model_id = ?", cmUnbound); len(rows) != 0 {
		t.Errorf("states left = %d, want 0", len(rows))
	}

	// 绑定 Key 的渠道模型：只清绑定 Key 的状态，别的 Key 保持不动。
	cmBound, _ := st.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, channel_api_key_id) VALUES (?, 'bound', 1, ?)", ch, k1)
	for _, k := range []int64{k1, k2} {
		if _, err := st.Insert(ctx,
			"INSERT INTO circuit_breaker_states (channel_id, channel_model_id, channel_api_key_id, is_open, fail_count) VALUES (?, ?, ?, 1, 3)",
			ch, cmBound, k); err != nil {
			t.Fatal(err)
		}
	}
	if got := clearChannelModelBreaker(ctx, st, cmBound); got != 1 {
		t.Errorf("recovered = %d, want 1 (only the bound key)", got)
	}
	rows, _ := st.Query(ctx, "SELECT channel_api_key_id FROM circuit_breaker_states WHERE channel_model_id = ?", cmBound)
	if len(rows) != 1 || rows[0].I64("channel_api_key_id", 0) != k2 {
		t.Errorf("remaining states = %+v, want only key %d", rows, k2)
	}

	// 不存在的渠道模型：无状态可清，返回 0 且不报错。
	if got := clearChannelModelBreaker(ctx, st, 999999); got != 0 {
		t.Errorf("recovered = %d for a missing channel model, want 0", got)
	}
}
