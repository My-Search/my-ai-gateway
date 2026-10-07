// Package relay implements the core AI gateway request relay engine.
// This file contains the RouteResolver and RelayService definitions.
package relay

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// RouteResolver resolves model context and builds candidates.
type RouteResolver struct {
	Store DataStore
}

func NewRouteResolver(store DataStore) *RouteResolver {
	return &RouteResolver{Store: store}
}

// RoutingContext holds per-request routing metadata.
//
// The entry model's strategy is not carried here: candidate ordering (including the
// entry-model strategy) is decided by BuildCandidates, which resolves it alongside
// the relations it needs anyway.
type RoutingContext struct {
	ModelID     int64
	MaxAttempts int
	RetryCount  int
}

// ResolveModelRouting mirrors RouteResolver.resolveModelRouting: a missing model
// yields the defaultEmpty context (maxAttempts = 1), and a missing breaker config
// row is created with the Java defaults so retryCount becomes 3.
func (r *RouteResolver) ResolveModelRouting(ctx context.Context, modelName string) RoutingContext {
	row, _ := r.Store.QueryOne(ctx, "SELECT id FROM models WHERE model_name = ?", modelName)
	if row == nil {
		return RoutingContext{MaxAttempts: 1}
	}
	modelID := row.I64("id", 0)
	retryCount := 0
	cfg, _ := r.Store.QueryOne(ctx, "SELECT retry_count, enabled FROM circuit_breaker_configs WHERE model_id = ?", modelID)
	if cfg == nil {
		// Java CircuitBreakerConfigManager.getCircuitBreakerConfig inserts the
		// default row (retry_count=3, enabled=1) when absent.
		r.insertDefaultCircuitConfig(ctx, modelID)
		cfg, _ = r.Store.QueryOne(ctx, "SELECT retry_count, enabled FROM circuit_breaker_configs WHERE model_id = ?", modelID)
	}
	if cfg != nil && cfg.Int("enabled", 0) == 1 {
		if rc := cfg.Int("retry_count", 0); rc > 0 {
			retryCount = rc
		}
	}
	return RoutingContext{
		ModelID:     modelID,
		RetryCount:  retryCount,
		MaxAttempts: maxInt(1, retryCount+1),
	}
}

func (r *RouteResolver) insertDefaultCircuitConfig(ctx context.Context, modelID int64) {
	now := store.NowApp()
	_, _ = r.Store.Exec(ctx,
		"INSERT OR IGNORE INTO circuit_breaker_configs (model_id, retry_count, circuit_break_duration, circuit_break_scope, enabled, created_at, updated_at) VALUES (?, 3, 60, 'model', 1, ?, ?)",
		modelID, now, now)
}

// ResolveModelID mirrors RouteResolver.resolveModelId.
func (r *RouteResolver) ResolveModelID(ctx context.Context, modelName string) int64 {
	row, _ := r.Store.QueryOne(ctx, "SELECT id FROM models WHERE model_name = ?", modelName)
	if row == nil {
		return 0
	}
	return row.I64("id", 0)
}

// BuildCandidates mirrors RouteResolver.getAvailableCandidates. Only enabled
// filters are applied here; circuit-broken candidates still enter the list and
// are skipped by the routing loop so each skip is visible in the request log.
//
// 候选来源有两类，按 sort_order 合并成一条队列：
//   - 直接关联的渠道模型（model_channel_rels）
//   - 关联的模型小组（model_group_rels）：组内成员按小组自己的策略排序后
//     展平到该位置，使小组成为入口模型的一层子路由
//
// 展平后候选顺序即为尝试顺序，入口模型的 balancer 不再二次打乱（见 core 循环）。
func (r *RouteResolver) BuildCandidates(ctx context.Context, req *InternalRequest) []RoutingCandidate {
	modelID, strategy := r.resolveEntryModel(ctx, req.Model)
	if modelID == 0 {
		slog.Warn("找不到自定义模型", "model", req.Model)
		return nil
	}
	entries := r.resolveRoutingEntries(ctx, modelID, make(map[int64]bool))
	if len(entries) == 0 {
		return nil
	}
	// 入口模型策略作用于「条目」（直连关联 / 小组整体），小组在队列中保持连续。
	// 注意继承模式下策略取自入口模型本身（与候选来源的继承无关）。
	entries = orderEntries(strategy, entries, modelID)

	// stickyKey 只在确有粘性小组时计算（避免每个请求都做前缀哈希）。
	stickyKey := ""
	stickyComputed := false

	var candidates []RoutingCandidate
	for _, e := range entries {
		if e.isGroup {
			groupCandidates, weights := r.groupMemberCandidates(ctx, e.groupID)
			if len(groupCandidates) == 0 {
				continue
			}
			if e.sticky && !stickyComputed {
				stickyKey = StickyKey(req)
				stickyComputed = true
			}
			ordered := orderGroupCandidates(groupOrdering{
				candidates: groupCandidates,
				weights:    weights,
				strategy:   e.groupStrategy,
				sticky:     e.sticky,
				stickyKey:  stickyKey,
				groupID:    e.groupID,
			})
			// 小组来源信息只用于请求日志标注（小组名/策略/粘性命中），不参与路由。
			for i := range ordered {
				ordered[i].GroupID = e.groupID
				ordered[i].GroupName = e.groupName
				ordered[i].GroupStrategy = e.groupStrategy
			}
			// 组内排序结果作为「入口模型候选顺序」的一段整体拼接，组内失败会先
			// 尝试组内下一个成员，再回到入口模型的下一条关联。
			candidates = append(candidates, ordered...)
			continue
		}
		candidates = append(candidates, e.candidates...)
	}
	return candidates
}

// resolveEntryModel resolves an entry model name to its id plus routing strategy
// in one query (blank strategy → failover). Returns 0 when the model is unknown.
func (r *RouteResolver) resolveEntryModel(ctx context.Context, modelName string) (int64, string) {
	row, _ := r.Store.QueryOne(ctx, "SELECT id, strategy FROM models WHERE model_name = ?", modelName)
	if row == nil {
		return 0, "failover"
	}
	strategy := strings.TrimSpace(row.Str("strategy"))
	if strategy == "" {
		strategy = "failover"
	}
	return row.I64("id", 0), strategy
}

// groupMemberCandidates loads one group's enabled members as routing candidates,
// returning them alongside their weights (parallel slices).
func (r *RouteResolver) groupMemberCandidates(ctx context.Context, groupID int64) ([]RoutingCandidate, []int) {
	rows, _ := r.Store.Query(ctx,
		`SELECT m.id, m.channel_model_id, m.weight, m.reasoning_effort, m.sort_order
		   FROM model_group_members m
		  WHERE m.group_id = ? AND m.enabled = 1
		  ORDER BY m.sort_order ASC, m.id ASC`, groupID)
	if len(rows) == 0 {
		return nil, nil
	}
	var candidates []RoutingCandidate
	var weights []int
	for _, row := range rows {
		expanded, weight := r.expandChannelModel(ctx, channelModelRef{
			ChannelModelID:  row.I64("channel_model_id", 0),
			SortOrder:       row.Int("sort_order", 0),
			ReasoningEffort: row.StrPtr("reasoning_effort"),
			Weight:          row.Int("weight", 1),
			GroupMemberID:   row.I64("id", 0),
		})
		if len(expanded) == 0 {
			continue
		}
		candidates = append(candidates, expanded...)
		for range expanded {
			weights = append(weights, weight)
		}
	}
	return candidates, weights
}

// channelModelRef is one channel-model reference (from an entry-model relation or
// a group membership) that still needs its channel + API keys expanded.
type channelModelRef struct {
	ChannelModelID  int64
	SortOrder       int
	ReasoningEffort *string
	Weight          int
	GroupMemberID   int64
}

// expandChannelModel resolves one channel-model reference into concrete routing
// candidates (one per usable API key). It returns nil when the channel model, its
// channel or every key is disabled.
func (r *RouteResolver) expandChannelModel(ctx context.Context, ref channelModelRef) ([]RoutingCandidate, int) {
	weight := normalizeWeight(ref.Weight)
	cm, _ := r.Store.QueryOne(ctx, "SELECT id, channel_id, channel_api_key_id, model_name, enabled, input, context_length FROM channel_models WHERE id = ?", ref.ChannelModelID)
	if cm == nil || cm.Int("enabled", 1) != 1 {
		return nil, weight
	}
	ch, _ := r.Store.QueryOne(ctx, "SELECT id, name, channel_type, base_url, enabled, custom_headers FROM channels WHERE id = ?", cm.I64("channel_id", 0))
	if ch == nil || ch.Int("enabled", 1) != 1 {
		return nil, weight
	}
	var specKeyID *int64
	if v := cm.I64Ptr("channel_api_key_id"); v != nil {
		specKeyID = v
	}
	keys := getKeys(ctx, r.Store, ch.I64("id", 0), specKeyID)
	out := make([]RoutingCandidate, 0, len(keys))
	for _, key := range keys {
		if key.Enabled != 1 {
			continue
		}
		out = append(out, RoutingCandidate{
			ChannelModelID:  cm.I64("id", 0),
			ChannelID:       ch.I64("id", 0),
			ChannelName:     ch.Str("name"),
			ChannelType:     ch.Str("channel_type"),
			ModelName:       cm.Str("model_name"),
			APIKey:          key.APIKey,
			APIKeyID:        key.ID,
			APIKeyName:      key.KeyName,
			BaseURL:         ch.Str("base_url"),
			CustomHeaders:   ch.Str("custom_headers"),
			SortOrder:       ref.SortOrder,
			ReasoningEffort: ref.ReasoningEffort,
			Input:           cm.Str("input"),
			ContextLength:   cm.I64("context_length", 0),
			Weight:          weight,
			GroupMemberID:   ref.GroupMemberID,
		})
	}
	return out, weight
}

// routingEntry is one slot in the entry model's candidate queue: either a single
// channel model relation or a whole group (to be flattened at that position).
type routingEntry struct {
	isGroup       bool
	groupID       int64
	groupName     string
	groupStrategy string
	sticky        bool
	sortOrder     int
	candidates    []RoutingCandidate
}

// resolveRoutingEntries merges model_channel_rels and model_group_rels of one
// entry model into a single sort_order-ordered queue, expanding inheritance the
// same way resolveRels did. Group entries are flattened afterwards by
// BuildCandidates (they need the request for the sticky prefix hash).
func (r *RouteResolver) resolveRoutingEntries(ctx context.Context, modelID int64, visited map[int64]bool) []routingEntry {
	if modelID == 0 || visited[modelID] {
		return nil
	}
	visited[modelID] = true
	md, _ := r.Store.QueryOne(ctx, "SELECT id, rel_mode, inherit_from_model_id FROM models WHERE id = ?", modelID)
	if md == nil {
		return nil
	}
	if md.Str("rel_mode") == "inherit" {
		if v := md.I64Ptr("inherit_from_model_id"); v != nil {
			return r.resolveRoutingEntries(ctx, *v, visited)
		}
	}

	var entries []routingEntry
	rows, _ := r.Store.Query(ctx, "SELECT id, model_id, channel_model_id, sort_order, reasoning_effort, enabled FROM model_channel_rels WHERE model_id = ? ORDER BY sort_order ASC, created_at ASC", modelID)
	for _, row := range rows {
		if row.Int("enabled", 1) != 1 {
			continue
		}
		expanded, _ := r.expandChannelModel(ctx, channelModelRef{
			ChannelModelID:  row.I64("channel_model_id", 0),
			SortOrder:       row.Int("sort_order", 0),
			ReasoningEffort: row.StrPtr("reasoning_effort"),
			Weight:          row.Int("weight", 1),
		})
		if len(expanded) == 0 {
			continue
		}
		entries = append(entries, routingEntry{
			sortOrder:  row.Int("sort_order", 0),
			candidates: expanded,
		})
	}

	groupRows, _ := r.Store.Query(ctx,
		`SELECT rel.sort_order, g.id, g.name, g.strategy, g.sticky, g.enabled
		   FROM model_group_rels rel
		   JOIN model_groups g ON g.id = rel.group_id
		  WHERE rel.model_id = ? AND rel.enabled = 1
		  ORDER BY rel.sort_order ASC, rel.created_at ASC`, modelID)
	for _, row := range groupRows {
		if row.Int("enabled", 1) != 1 {
			continue
		}
		entries = append(entries, routingEntry{
			isGroup:       true,
			groupID:       row.I64("id", 0),
			groupName:     row.Str("name"),
			groupStrategy: normalizeGroupStrategy(row.Str("strategy")),
			sticky:        row.Int("sticky", 0) == 1,
			sortOrder:     row.Int("sort_order", 0),
		})
	}

	sortEntriesByOrder(entries)
	return entries
}

// normalizeGroupStrategy clamps an unknown/blank strategy to the group default.
func normalizeGroupStrategy(s string) string {
	switch strings.TrimSpace(s) {
	case GroupStrategyFailover:
		return GroupStrategyFailover
	case GroupStrategyRoundRobin:
		return GroupStrategyRoundRobin
	default:
		return GroupStrategyRandom
	}
}

type apiKeyRow struct {
	ID        int64
	KeyName   string
	APIKey    string
	Enabled   int
	SortOrder int
}

func getKeys(ctx context.Context, store DataStore, channelID int64, specKeyID *int64) []apiKeyRow {
	if specKeyID != nil {
		r, err := store.QueryOne(ctx, "SELECT id, key_name, api_key, enabled, sort_order FROM channel_api_keys WHERE id = ? AND enabled = 1", *specKeyID)
		if err != nil {
			return nil
		}
		return []apiKeyRow{{ID: r.I64("id", 0), KeyName: r.Str("key_name"), APIKey: r.Str("api_key"), Enabled: r.Int("enabled", 1)}}
	}
	rows, _ := store.Query(ctx, "SELECT id, key_name, api_key, enabled, sort_order FROM channel_api_keys WHERE channel_id = ? AND enabled = 1 ORDER BY sort_order", channelID)
	var keys []apiKeyRow
	for _, row := range rows {
		keys = append(keys, apiKeyRow{ID: row.I64("id", 0), KeyName: row.Str("key_name"), APIKey: row.Str("api_key"), Enabled: row.Int("enabled", 1)})
	}
	return keys
}

// ---------------------------------------------------------------------------
// Balancers (Java LoadBalancerFactory)
// ---------------------------------------------------------------------------

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

var (
	_ = fmt.Sprintf
	_ = time.Second
)
