// Package relay implements the core AI gateway request relay engine.
// This file contains the RouteResolver and RelayService definitions.
package relay

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"sync"
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
type RoutingContext struct {
	ModelID     int64
	Strategy    string
	MaxAttempts int
	RetryCount  int
}

// ResolveModelRouting mirrors RouteResolver.resolveModelRouting: a missing model
// yields the defaultEmpty context (maxAttempts = 1), and a missing breaker config
// row is created with the Java defaults so retryCount becomes 3.
func (r *RouteResolver) ResolveModelRouting(ctx context.Context, modelName string) RoutingContext {
	row, _ := r.Store.QueryOne(ctx, "SELECT id, strategy FROM models WHERE model_name = ?", modelName)
	if row == nil {
		return RoutingContext{MaxAttempts: 1, Strategy: "failover"}
	}
	strategy := row.Str("strategy")
	if strings.TrimSpace(strategy) == "" {
		strategy = "failover"
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
		Strategy:    strategy,
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
func (r *RouteResolver) BuildCandidates(ctx context.Context, req *InternalRequest) []RoutingCandidate {
	modelID := r.ResolveModelID(ctx, req.Model)
	if modelID == 0 {
		slog.Warn("找不到自定义模型", "model", req.Model)
		return nil
	}
	rels := r.resolveRels(ctx, modelID, make(map[int64]bool))
	var candidates []RoutingCandidate
	for _, rel := range rels {
		cm, _ := r.Store.QueryOne(ctx, "SELECT id, channel_id, channel_api_key_id, model_name, enabled, input FROM channel_models WHERE id = ?", rel.ChannelModelID)
		if cm == nil || cm.Int("enabled", 1) != 1 {
			continue
		}
		ch, _ := r.Store.QueryOne(ctx, "SELECT id, name, channel_type, base_url, enabled, custom_headers FROM channels WHERE id = ?", cm.I64("channel_id", 0))
		if ch == nil || ch.Int("enabled", 1) != 1 {
			continue
		}
		var specKeyID *int64
		if v := cm.I64Ptr("channel_api_key_id"); v != nil {
			specKeyID = v
		}
		keys := getKeys(ctx, r.Store, ch.I64("id", 0), specKeyID)
		for _, key := range keys {
			if key.Enabled != 1 {
				continue
			}
			candidates = append(candidates, RoutingCandidate{
				RelID:           rel.ID,
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
				SortOrder:       rel.SortOrder,
				ReasoningEffort: rel.ReasoningEffort,
				Input:           cm.Str("input"),
			})
		}
	}
	return candidates
}

func (r *RouteResolver) resolveRels(ctx context.Context, modelID int64, visited map[int64]bool) []relRow {
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
			return r.resolveRels(ctx, *v, visited)
		}
	}
	rows, _ := r.Store.Query(ctx, "SELECT id, model_id, channel_model_id, sort_order, reasoning_effort, enabled FROM model_channel_rels WHERE model_id = ? ORDER BY sort_order ASC, created_at ASC", modelID)
	var out []relRow
	for _, row := range rows {
		if row.Int("enabled", 1) != 1 {
			continue
		}
		out = append(out, relRow{
			ID:              row.I64("id", 0),
			ChannelModelID:  row.I64("channel_model_id", 0),
			SortOrder:       row.Int("sort_order", 0),
			ReasoningEffort: row.StrPtr("reasoning_effort"),
		})
	}
	return out
}

type relRow struct {
	ID              int64
	ChannelModelID  int64
	SortOrder       int
	ReasoningEffort *string
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

// balancerFactory is the process-wide singleton that keeps round-robin counters
// alive across requests, exactly like the Java Spring singleton.
type balancerFactory struct {
	mu       sync.Mutex
	balancer map[string]Balancer
}

var defaultBalancerFactory = &balancerFactory{balancer: map[string]Balancer{}}

// NewBalancerFactory returns a strategy → singleton-balancer lookup, mirroring
// LoadBalancerFactory.getBalancer.
func NewBalancerFactory() func(string) Balancer {
	return func(strategy string) Balancer {
		defaultBalancerFactory.mu.Lock()
		defer defaultBalancerFactory.mu.Unlock()
		if b, ok := defaultBalancerFactory.balancer[strategy]; ok {
			return b
		}
		b := NewBalancer(strategy)
		defaultBalancerFactory.balancer[strategy] = b
		return b
	}
}

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
