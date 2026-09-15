// Background wiring: circuit breaker hooks, the three Java @Scheduled tasks and
// the relay engine's optional callbacks.
//
// Java counterparts: CircuitBreakerService/CircuitTrigger/CircuitGate wiring in
// CandidateRouter, ChannelModelRefreshTask, CircuitBreakerRecoveryTask and
// LogCleanupTask.
package server

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/my-search/my-ai-gateway/internal/channelload"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/metrics"
	"github.com/my-search/my-ai-gateway/internal/relay"
	"github.com/my-search/my-ai-gateway/internal/relay/circuit"
	"github.com/my-search/my-ai-gateway/internal/service"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// WireRelayRuntime connects the circuit breaker, latency config, request
// preprocessing, metrics and last-used bookkeeping to the relay engine.
func WireRelayRuntime(core *relay.RelayCore, st *store.Store, cfgSvc *service.ConfigService, m *metrics.Registry) *CircuitBundle {
	// 创建熔断状态本地缓存（Java LocalCacheService + NS_CIRCUIT_STATE_BY_SCOPE, 1s TTL）
	stateCache := circuit.NewStateCache()

	configMgr := &circuit.ConfigManager{Store: st}
	check := &circuit.Check{Store: st}
	gate := &circuit.Gate{Store: st, StateCache: stateCache}

	trigger := &circuit.Trigger{
		Store:     st,
		ConfigMgr: configMgr,
		StateCache: stateCache,
		APIKeyMoveFn: func(ctx context.Context, channelID, apiKeyID int64) {
			moveAPIKeyToEnd(ctx, st, channelID, apiKeyID)
		},
	}

	probe := &circuit.ProbeService{
		Store: st,
		HTTPDo: func(ctx context.Context, target circuit.ProbeTarget, endpoint string, headers map[string]string, body string) bool {
			req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(body))
			if err != nil {
				return false
			}
			for k, v := range headers {
				req.Header.Set(k, v)
			}
			resp, err := probeHTTPClient.Do(req)
			if err != nil {
				slog.Warn("熔断探测失败", "channel", target.ChannelName, "model", target.ModelName, "error", err)
				return false
			}
			defer resp.Body.Close()
			buf := make([]byte, 4096)
			for i := 0; i < 16; i++ {
				if _, err := resp.Body.Read(buf); err != nil {
					break
				}
			}
			return resp.StatusCode >= 200 && resp.StatusCode < 300
		},
	}

	recovery := circuit.NewRecoveryService()
	recovery.Probe = probe
	recovery.Check = check
	recovery.Gate = gate
	recovery.ConfigMgr = configMgr
	recovery.Resolver = &resolution{st: st}
	recovery.ThrottleFn = func(ctx context.Context) float64 {
		return float64(cfgSvc.IntValue(ctx, service.KeyCircuitProbeThrottleSeconds, 6))
	}

	// 探测成功/失败后也失效缓存，使下一请求立即感知状态变化
	oldHTTPDo := probe.HTTPDo
	probe.HTTPDo = func(ctx context.Context, target circuit.ProbeTarget, endpoint string, headers map[string]string, body string) bool {
		alive := oldHTTPDo(ctx, target, endpoint, headers, body)
		// 探测操作后失效一次整个缓存（Java CircuitBreakerRecoveryService 成功后
		// 删除/续期状态记录，CandidateRouter 事件监听器失效整缓存）
		stateCache.InvalidateAll()
		return alive
	}

	// --- CandidateRouter hooks (with state cache) --------------------------------
	// Java 行为：circuitBreakScope 先查本地缓存（1s TTL），命中则直接返回；
	// 未命中则查 DB 写入缓存（含 __healthy__ 哨兵），等下一次路由该候选时命中。
	core.CircuitCheckFn = func(ctx context.Context, candidate relay.RoutingCandidate) (bool, string) {
		apiKeyID := candidate.APIKeyID

		// 1. 尝试本地缓存
		if broken, scope, ok := stateCache.Get(candidate.ChannelID, candidate.ChannelModelID, apiKeyID); ok {
			return broken, scope
		}

		// 2. 缓存未命中，查 DB
		if check.IsChannelBroken(ctx, candidate.ChannelID) ||
			check.IsChannelBrokenByKey(ctx, candidate.ChannelID, &apiKeyID) {
			stateCache.Set(candidate.ChannelID, candidate.ChannelModelID, apiKeyID, true, "渠道级熔断")
			return true, "渠道级熔断"
		}
		if check.IsModelBroken(ctx, candidate.ChannelModelID, &apiKeyID) {
			stateCache.Set(candidate.ChannelID, candidate.ChannelModelID, apiKeyID, true, "模型级熔断")
			return true, "模型级熔断"
		}

		// 3. 未熔断：写入 __healthy__ 哨兵（Java CIRCUIT_HEALTHY_SENTINEL），
		//    使最常见路径下次也命中缓存，避免反复查询 DB
		stateCache.Set(candidate.ChannelID, candidate.ChannelModelID, apiKeyID, false, "")
		return false, ""
	}

	core.CircuitTripFn = func(ctx context.Context, modelID, channelID, channelModelID int64, apiKeyID *int64) {
		defer func() {
			if rec := recover(); rec != nil {
				// 熔断写入失败不阻断主流程（Java CandidateRouter.handleFailure）
				slog.Error("触发熔断写入失败", "error", rec)
			}
		}()
		trigger.TriggerBreak(ctx, modelID, channelID, apiKeyID, channelModelID)
		// TriggerBreak 已通过 StateCache.Invalidate 失效缓存，
		// 此处无需重复失效
	}
	core.TriggerProbeFn = func(channelID int64) { recovery.TriggerProbeByChannel(channelID) }

	// RelayMetrics (Java CandidateRouter.recordRouteMetric / recordCircuitBreakSkip).
	if m != nil {
		core.MetricsFn = func(model, channel, result string, latencyMs int64) {
			m.RecordRoute(model, channel, result, latencyMs)
		}
		core.CircuitSkipFn = func(scope string) {
			m.RecordCircuitBreakSkip(scope)
		}
	}

	core.ChannelModelLastUsedFn = func(ctx context.Context, channelModelID int64) {
		_, _ = st.Exec(ctx, "UPDATE channel_models SET last_used_at = ? WHERE id = ?",
			jtime.FormatApp(time.Now().UTC()), channelModelID)
	}
	core.GatewayKeyLastUsedFn = func(ctx context.Context, authHeader string) {
		key := strings.TrimSpace(authHeader)
		key = strings.TrimPrefix(key, "Bearer ")
		key = strings.TrimSpace(key)
		if key == "" {
			return
		}
		_, _ = st.Exec(ctx, "UPDATE api_keys SET last_used_at = ? WHERE key_value = ?",
			jtime.FormatApp(time.Now().UTC()), key)
	}

	// LatencyTracker reads timeout_min_seconds / timeout_max_seconds live
	// (Java LatencyTracker reads AdminConfigService on every call).
	core.LatencyTracker = relay.NewLatencyTracker(
		func() int64 {
			return int64(cfgSvc.IntValue(context.Background(), service.KeyTimeoutMinSeconds, 20)) * 1000
		},
		func() int64 {
			return int64(cfgSvc.IntValue(context.Background(), service.KeyTimeoutMaxSeconds, 60)) * 1000
		},
	)

	// Request preprocessing (RequestPreprocessor dependencies).
	core.PreprocessDeps = relay.PreprocessDeps{
		ModelID: func(ctx context.Context, modelName string) int64 {
			return core.RouteResolver.ResolveModelID(ctx, modelName)
		},
		PromptInjections: func(ctx context.Context, modelID int64) []relay.PromptInjectionRule {
			rows, _ := st.Query(ctx,
				"SELECT name, inject_role, inject_position, content FROM prompt_injections WHERE model_id = ? AND enabled = 1 ORDER BY priority ASC, id ASC",
				modelID)
			out := make([]relay.PromptInjectionRule, 0, len(rows))
			for _, row := range rows {
				out = append(out, relay.PromptInjectionRule{
					Name:           row.Str("name"),
					InjectRole:     row.Str("inject_role"),
					InjectPosition: row.Str("inject_position"),
					Content:        row.Str("content"),
				})
			}
			return out
		},
		ModelFlags: func(ctx context.Context, modelID int64) (int, int, int, bool) {
			row, err := st.QueryOne(ctx,
				"SELECT image_invalidate_count, video_invalidate_count, audio_invalidate_count, force_override_reasoning_effort FROM models WHERE id = ?",
				modelID)
			if err != nil {
				return 0, 0, 0, false
			}
			return row.Int("image_invalidate_count", 0), row.Int("video_invalidate_count", 0),
				row.Int("audio_invalidate_count", 0), row.Int("force_override_reasoning_effort", 0) == 1
		},
	}

	return &CircuitBundle{
		ConfigMgr: configMgr, Trigger: trigger, Check: check, Gate: gate,
		Probe: probe, Recovery: recovery, StateCache: stateCache,
	}
}

var probeHTTPClient = &http.Client{Timeout: (circuit.ProbeTimeoutSeconds + 1) * time.Second}

// CircuitBundle groups the breaker components shared with the recovery task.
type CircuitBundle struct {
	ConfigMgr  *circuit.ConfigManager
	Trigger    *circuit.Trigger
	Check      *circuit.Check
	Gate       *circuit.Gate
	Probe      *circuit.ProbeService
	Recovery   *circuit.RecoveryService
	StateCache *circuit.StateCache
}

// resolution adapts store lookups to circuit.Resolution.
type resolution struct {
	st *store.Store
}

func (r *resolution) ChannelEnabled(ctx context.Context, channelID int64) (bool, string, string, string, string, string, bool) {
	row, err := r.st.QueryOne(ctx, "SELECT name, channel_type, base_url, custom_headers, enabled FROM channels WHERE id = ?", channelID)
	if err != nil {
		return false, "", "", "", "", "", false
	}
	return row.Int("enabled", 0) == 1, row.Str("channel_type"), row.Str("base_url"),
		row.Str("custom_headers"), row.Str("name"), "", true
}

func (r *resolution) ChannelModelEnabled(ctx context.Context, channelModelID int64) (bool, int64, string, string, bool) {
	row, err := r.st.QueryOne(ctx, "SELECT model_name, enabled, channel_id, channel_api_key_id FROM channel_models WHERE id = ?", channelModelID)
	if err != nil {
		return false, 0, "", "", false
	}
	return row.Int("enabled", 0) == 1, row.I64("channel_id", 0), row.Str("model_name"), row.Str("channel_api_key_id"), true
}

func (r *resolution) FirstEnabledChannelModel(ctx context.Context, channelID int64) (string, bool, int64, bool) {
	row, err := r.st.QueryOne(ctx, "SELECT id, model_name, enabled FROM channel_models WHERE channel_id = ? AND enabled = 1 ORDER BY id LIMIT 1", channelID)
	if err != nil {
		return "", false, 0, false
	}
	return row.Str("model_name"), row.Int("enabled", 0) == 1, row.I64("id", 0), true
}

func (r *resolution) APIKeyEnabled(ctx context.Context, apiKeyID int64) (string, string, bool, bool) {
	row, err := r.st.QueryOne(ctx, "SELECT api_key, key_name, enabled FROM channel_api_keys WHERE id = ?", apiKeyID)
	if err != nil {
		return "", "", false, false
	}
	return row.Str("api_key"), row.Str("key_name"), row.Int("enabled", 0) == 1, true
}

func (r *resolution) FirstEnabledAPIKey(ctx context.Context, channelID int64) (string, string, int64, bool) {
	row, err := r.st.QueryOne(ctx, "SELECT id, api_key, key_name FROM channel_api_keys WHERE channel_id = ? AND enabled = 1 ORDER BY sort_order LIMIT 1", channelID)
	if err != nil {
		return "", "", 0, false
	}
	return row.Str("api_key"), row.Str("key_name"), row.I64("id", 0), true
}

// moveAPIKeyToEnd mirrors ChannelApiKeyService.moveToEnd.
func moveAPIKeyToEnd(ctx context.Context, st *store.Store, channelID, apiKeyID int64) {
	rows, _ := st.Query(ctx, "SELECT id, sort_order FROM channel_api_keys WHERE channel_id = ? AND enabled = 1", channelID)
	if len(rows) == 0 {
		return
	}
	maxOrder := 0
	for _, row := range rows {
		if o := row.Int("sort_order", 0); o > maxOrder {
			maxOrder = o
		}
	}
	for _, row := range rows {
		if row.I64("id", 0) == apiKeyID {
			if row.Int("sort_order", 0) < maxOrder {
				_, _ = st.Exec(ctx, "UPDATE channel_api_keys SET sort_order = ? WHERE id = ?", maxOrder+1, apiKeyID)
				slog.Info("API Key 已移至渠道最后", "channelId", channelID, "apiKeyId", apiKeyID)
			}
			return
		}
	}
}

// ---------------------------------------------------------------------------
// Scheduled tasks (Java schedule package)
// ---------------------------------------------------------------------------

// StartBackgroundTasks launches the three Java @Scheduled equivalents and the
// circuit recovery worker. The returned stop function shuts them down.
func StartBackgroundTasks(core *relay.RelayCore, st *store.Store, cfgSvc *service.ConfigService, bundle *CircuitBundle) func() {
	ctx, cancel := context.WithCancel(context.Background())
	var wg sync.WaitGroup

	// CircuitBreakerRecoveryTask worker: consumes probe signals.
	bundle.Recovery.Start()

	// ChannelModelRefreshTask: 60s tick, interval from system config, immediate
	// first run (Java lastFullRefreshAt starts null).
	wg.Add(1)
	go func() {
		defer wg.Done()
		var lastFullRefresh time.Time
		ticker := time.NewTicker(60 * time.Second)
		defer ticker.Stop()
		for {
			intervalMinutes := cfgSvc.IntValue(ctx, service.KeyChannelModelRefreshIntervalMins, 30)
			now := time.Now()
			if lastFullRefresh.IsZero() || now.Sub(lastFullRefresh).Minutes() >= float64(intervalMinutes) {
				lastFullRefresh = now
				refreshAutoChannels(ctx, st)
			}
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()

	// CircuitBreakerRecoveryTask: 60s tick, full-scan interval from system
	// config, immediate first scan.
	wg.Add(1)
	go func() {
		defer wg.Done()
		var lastFullScan time.Time
		ticker := time.NewTicker(60 * time.Second)
		defer ticker.Stop()
		for {
			intervalMinutes := cfgSvc.IntValue(ctx, service.KeyCircuitProbeIntervalMinutes, 30)
			now := time.Now()
			if lastFullScan.IsZero() || now.Sub(lastFullScan).Minutes() >= float64(intervalMinutes) {
				lastFullScan = now
				slog.Info("熔断全量探测触发", "intervalMinutes", intervalMinutes)
				bundle.Recovery.ScanExpiredGates()
			}
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()

	// LogCleanupTask: cron "0 0/30 * * * ?".
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			next := nextHalfHour(time.Now().UTC())
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Until(next)):
				runLogCleanup(ctx, st, cfgSvc)
			}
		}
	}()

	return func() {
		cancel()
		bundle.Recovery.Stop()
		wg.Wait()
	}
}

func nextHalfHour(now time.Time) time.Time {
	return now.Truncate(30 * time.Minute).Add(30 * time.Minute)
}

// refreshAutoChannels mirrors ChannelModelRefreshTask.refreshAll.
func refreshAutoChannels(ctx context.Context, st *store.Store) {
	rows, _ := st.Query(ctx,
		`SELECT id, name FROM channels WHERE enabled = 1 AND model_refresh_enabled = 1
		   AND EXISTS (SELECT 1 FROM channel_api_keys k WHERE k.channel_id = channels.id AND k.enabled = 1)`)
	if len(rows) == 0 {
		return
	}
	slog.Info("渠道模型自动刷新开始", "count", len(rows))
	success := 0
	for _, row := range rows {
		if err := channelload.LoadModelsByID(ctx, st, row.I64("id", 0)); err != nil {
			slog.Warn("渠道模型自动刷新失败", "channel", row.Str("name"), "error", err)
			continue
		}
		success++
	}
	slog.Info("渠道模型自动刷新完成", "success", success, "total", len(rows))
}

// runLogCleanup mirrors LogCleanupTask.cleanExpiredData.
func runLogCleanup(ctx context.Context, st *store.Store, cfgSvc *service.ConfigService) {
	if cfgSvc.GetValue(ctx, service.KeyLogCleanupEnabled, "1") != "1" {
		return
	}
	retentionDays := cfgSvc.IntValue(ctx, service.KeyLogRetentionDays, 7)
	if retentionDays <= 0 {
		slog.Warn("日志保留天数配置无效，使用默认值 7 天", "value", retentionDays)
		retentionDays = 7
	}

	// 1. Delete whole log rows past the retention window.
	if n, err := st.Exec(ctx, "DELETE FROM request_logs WHERE datetime(created_at) < datetime('now', ?)",
		"-"+itoa64(int64(retentionDays))+" days"); err == nil && n > 0 {
		slog.Info("清理过期日志", "days", retentionDays, "rows", n)
	}

	// 2. Raw request data TTL (0 = follow log retention).
	ttlHours := cfgSvc.IntValue(ctx, service.KeyRequestBodyTTLHours, 4)
	if ttlHours < 0 {
		ttlHours = 0
	}
	if ttlHours == 0 {
		ttlHours = retentionDays * 24
	}
	retryFailTTL := ttlHours
	if raw := strings.TrimSpace(cfgSvc.GetValue(ctx, service.KeyRetryFailTTLHours, "")); raw != "" {
		if n := cfgSvc.IntValue(ctx, service.KeyRetryFailTTLHours, 0); n < 0 {
			retryFailTTL = 0
		} else {
			retryFailTTL = n
		}
		if retryFailTTL == 0 {
			retryFailTTL = retentionDays * 24
		}
	}
	if n1, n2 := cleanExpiredRequestData(ctx, st, ttlHours, retryFailTTL); n1+n2 > 0 {
		slog.Info("清理原始请求数据", "normalTtlHours", ttlHours, "retryFailTtlHours", retryFailTTL, "rows", n1+n2)
	}
}

// cleanExpiredRequestData mirrors RequestLogService.cleanExpiredRequestData.
func cleanExpiredRequestData(ctx context.Context, st *store.Store, ttlHours, retryFailTTLHours int) (int64, int64) {
	n1, _ := st.Exec(ctx,
		`UPDATE request_logs SET request_headers = NULL, request_body = NULL
		  WHERE (request_headers IS NOT NULL OR request_body IS NOT NULL)
		    AND EXISTS (SELECT 1 FROM request_logs b WHERE b.trace_id = request_logs.trace_id AND b.phase IN ('retry','fail'))
		    AND datetime(created_at) < datetime('now', ?)`,
		"-"+itoa64(int64(retryFailTTLHours))+" hours")
	n2, _ := st.Exec(ctx,
		`UPDATE request_logs SET request_headers = NULL, request_body = NULL
		  WHERE (request_headers IS NOT NULL OR request_body IS NOT NULL)
		    AND NOT EXISTS (SELECT 1 FROM request_logs b WHERE b.trace_id = request_logs.trace_id AND b.phase IN ('retry','fail'))
		    AND datetime(created_at) < datetime('now', ?)`,
		"-"+itoa64(int64(ttlHours))+" hours")
	return n1, n2
}

func itoa64(n int64) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [24]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}