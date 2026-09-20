// Package circuit implements the circuit breaker state machine.
//
// Behaviour mirrors the Java services CircuitCheck / CircuitTrigger / CircuitGate
// / CircuitBreakerConfigManager / CircuitMark and the relay CircuitBreakerProbeService.
//
// Two load-bearing semantics of the original are reproduced here:
//
//   - Circuit records never expire on their own. is_open=1 means "broken";
//     expire_at only marks when a probe becomes due. Only a successful probe
//     (or a manual recovery) deletes the record.
//   - Two-level model: channel level is identified by (channelId, channelApiKeyId)
//     and model level by (channelId, channelApiKeyId, channelModelId).
package circuit

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/store"
)

const (
	defaultRetryCount           = 3
	defaultBreakDurationSeconds = 60
	defaultScope                = "model"
	defaultEnabled              = 1
	FullScanMark                = int64(-1)
	// ProbeTimeoutSeconds is the total connect+response timeout of a probe request.
	ProbeTimeoutSeconds         = 30
	defaultProbeIntervalMinutes = 30
	defaultProbeThrottleSeconds = 6
)

// probeDetailMaxRunes caps the stored probe response detail (raw body or
// error text) so a misbehaving upstream cannot bloat every breaker row.
const probeDetailMaxRunes = 2000

// ConfigManager reads/writes circuit_breaker_configs.
type ConfigManager struct {
	Store *store.Store
}

// GetConfig returns the model's breaker config, creating the default row when
// absent (Java CircuitBreakerConfigManager.getCircuitBreakerConfig).
func (m *ConfigManager) GetConfig(ctx context.Context, modelID int64) *CircuitBreakerConfig {
	row, err := m.Store.QueryOne(ctx, "SELECT * FROM circuit_breaker_configs WHERE model_id = ?", modelID)
	if err != nil {
		// Insert default
		now := jtime.FormatApp(time.Now().UTC())
		m.Store.Insert(ctx,
			"INSERT OR IGNORE INTO circuit_breaker_configs (model_id, retry_count, circuit_break_duration, circuit_break_scope, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)",
			modelID, defaultRetryCount, defaultBreakDurationSeconds, defaultScope, now, now)
		row, _ = m.Store.QueryOne(ctx, "SELECT * FROM circuit_breaker_configs WHERE model_id = ?", modelID)
	}
	if row == nil {
		return &CircuitBreakerConfig{
			ModelID:              modelID,
			RetryCount:           defaultRetryCount,
			CircuitBreakDuration: defaultBreakDurationSeconds,
			CircuitBreakScope:    defaultScope,
			Enabled:              defaultEnabled,
		}
	}
	cfg := &CircuitBreakerConfig{
		ModelID:           modelID,
		RetryCount:        row.Int("retry_count", defaultRetryCount),
		Enabled:           row.Int("enabled", defaultEnabled),
		CircuitBreakScope: row.Str("circuit_break_scope"),
	}
	if d := row.IntPtr("circuit_break_duration"); d != nil {
		cfg.CircuitBreakDuration = *d
	} else {
		cfg.CircuitBreakDuration = defaultBreakDurationSeconds
	}
	if cfg.CircuitBreakScope == "" {
		cfg.CircuitBreakScope = defaultScope
	}
	return cfg
}

// GetDurationByChannelModelID reverse-looks-up the break duration via the rel's model.
func (m *ConfigManager) GetDurationByChannelModelID(ctx context.Context, channelModelID int64) int {
	row, _ := m.Store.QueryOne(ctx,
		"SELECT cfg.circuit_break_duration FROM model_channel_rels r JOIN circuit_breaker_configs cfg ON cfg.model_id = r.model_id WHERE r.channel_model_id = ? LIMIT 1",
		channelModelID)
	if row != nil {
		d := row.Int("circuit_break_duration", 0)
		if d > 0 {
			return d
		}
	}
	return defaultBreakDurationSeconds
}

// Trigger creates new breaker states.
type Trigger struct {
	Store        *store.Store
	ConfigMgr    *ConfigManager
	APIKeyMoveFn func(ctx context.Context, channelID, apiKeyID int64)
	// StateCache invalidated on every trigger so the routing loop picks up the
	// new broken state within the next cache lookup (Java CandidateRouter
	// listens to CircuitBreakerStateChangedEvent).
	StateCache *StateCache
}

// TriggerBreak mirrors CircuitTrigger.triggerCircuitBreak.
func (t *Trigger) TriggerBreak(ctx context.Context, modelID, channelID int64, channelAPIKeyID *int64, channelModelID int64) {
	cfg := t.ConfigMgr.GetConfig(ctx, modelID)
	if cfg == nil || cfg.Enabled != 1 {
		return
	}
	now := time.Now().UTC()
	expireAt := now.Add(time.Duration(cfg.CircuitBreakDuration) * time.Second)

	// Java wraps trigger + insert in one @Transactional block.
	_ = t.Store.Tx(ctx, func(tx *sql.Tx) error {
		if cfg.CircuitBreakScope == "channel" {
			if channelAPIKeyID != nil {
				_, _ = tx.ExecContext(ctx, "DELETE FROM circuit_breaker_states WHERE channel_id = ? AND channel_api_key_id = ?", channelID, *channelAPIKeyID)
			} else {
				_, _ = tx.ExecContext(ctx, "DELETE FROM circuit_breaker_states WHERE channel_id = ?", channelID)
			}
			_, err := tx.ExecContext(ctx,
				"INSERT INTO circuit_breaker_states (channel_id, channel_api_key_id, is_open, fail_count, opened_at, expire_at, created_at, updated_at) VALUES (?, ?, 1, 1, ?, ?, ?, ?)",
				channelID, channelAPIKeyID, jtime.FormatApp(now), jtime.FormatApp(expireAt), jtime.FormatApp(now), jtime.FormatApp(now))
			return err
		}
		if channelAPIKeyID != nil {
			_, _ = tx.ExecContext(ctx, "DELETE FROM circuit_breaker_states WHERE channel_model_id = ? AND channel_api_key_id = ?", channelModelID, *channelAPIKeyID)
		} else {
			_, _ = tx.ExecContext(ctx, "DELETE FROM circuit_breaker_states WHERE channel_model_id = ?", channelModelID)
		}
		_, err := tx.ExecContext(ctx,
			"INSERT INTO circuit_breaker_states (channel_id, channel_api_key_id, channel_model_id, is_open, fail_count, opened_at, expire_at, created_at, updated_at) VALUES (?, ?, ?, 1, 1, ?, ?, ?, ?)",
			channelID, channelAPIKeyID, channelModelID, jtime.FormatApp(now), jtime.FormatApp(expireAt), jtime.FormatApp(now), jtime.FormatApp(now))
		return err
	})

	// 熔断后将 API Key 移到排序末尾（隐式排序）
	if channelAPIKeyID != nil && t.APIKeyMoveFn != nil {
		t.APIKeyMoveFn(ctx, channelID, *channelAPIKeyID)
	}

	// 失效熔断状态缓存，使后续请求立即感知新的熔断状态
	// Java: 触发 CircuitBreakerStateChangedEvent → CandidateRouter 监听并失效缓存
	if t.StateCache != nil {
		t.StateCache.Invalidate(&channelID, &channelModelID, channelAPIKeyID)
	}
}

// Check queries circuit breaker states.
type Check struct {
	Store *store.Store
}

// IsChannelBroken mirrors isChannelCircuitBroken(channelId): legacy whole-channel
// records only (channel_api_key_id IS NULL).
func (c *Check) IsChannelBroken(ctx context.Context, channelID int64) bool {
	row, _ := c.Store.QueryOne(ctx, "SELECT COUNT(*) cnt FROM circuit_breaker_states WHERE channel_id=? AND channel_api_key_id IS NULL AND is_open=1", channelID)
	return row != nil && row.I64("cnt", 0) > 0
}

// IsChannelBrokenByKey mirrors isChannelCircuitBroken(channelId, apiKeyId):
// channel-level records, excluding model-level rows.
func (c *Check) IsChannelBrokenByKey(ctx context.Context, channelID int64, apiKeyID *int64) bool {
	if apiKeyID == nil {
		// Java: eq(channelApiKeyId != null, ...) plus channelModelId IS NULL;
		// a null key here means "whole channel" and is covered by IsChannelBroken.
		return false
	}
	row, _ := c.Store.QueryOne(ctx, "SELECT COUNT(*) cnt FROM circuit_breaker_states WHERE channel_id=? AND channel_api_key_id=? AND channel_model_id IS NULL AND is_open=1", channelID, *apiKeyID)
	return row != nil && row.I64("cnt", 0) > 0
}

// IsModelBroken mirrors isModelCircuitBroken(channelModelId, apiKeyId).
// Legacy rows with channel_api_key_id IS NULL cover every key.
func (c *Check) IsModelBroken(ctx context.Context, channelModelID int64, apiKeyID *int64) bool {
	if channelModelID == 0 {
		return false
	}
	if apiKeyID != nil {
		row, _ := c.Store.QueryOne(ctx, "SELECT COUNT(*) cnt FROM circuit_breaker_states WHERE channel_model_id=? AND (channel_api_key_id=? OR channel_api_key_id IS NULL) AND is_open=1", channelModelID, *apiKeyID)
		return row != nil && row.I64("cnt", 0) > 0
	}
	row, _ := c.Store.QueryOne(ctx, "SELECT COUNT(*) cnt FROM circuit_breaker_states WHERE channel_model_id=? AND is_open=1", channelModelID)
	return row != nil && row.I64("cnt", 0) > 0
}

// IsAvailable mirrors CircuitBreakerService.isAvailable's three-layer judgement.
func (c *Check) IsAvailable(ctx context.Context, channelModelID, channelID int64, apiKeyID *int64) bool {
	if c.IsChannelBroken(ctx, channelID) {
		return false
	}
	if apiKeyID != nil && c.IsChannelBrokenByKey(ctx, channelID, apiKeyID) {
		return false
	}
	if c.IsModelBroken(ctx, channelModelID, apiKeyID) {
		return false
	}
	return true
}

// ListOpenStatesByChannelIDs mirrors listOpenStatesByChannelIds.
// is_open=1 is returned regardless of expire_at.
func (c *Check) ListOpenStatesByChannelIDs(ctx context.Context, ids []int64) []CircuitBreakerState {
	if len(ids) == 0 {
		return nil
	}
	q := "SELECT * FROM circuit_breaker_states WHERE channel_id IN ("
	args := make([]any, 0, len(ids))
	for i, id := range ids {
		if i > 0 {
			q += ","
		}
		q += "?"
		args = append(args, id)
	}
	q += ") AND is_open = 1"
	rows, _ := c.Store.Query(ctx, q, args...)
	out := make([]CircuitBreakerState, 0, len(rows))
	for _, r := range rows {
		out = append(out, rowToCircuitBreakerState(r))
	}
	return out
}

// GetActiveBrokenStates mirrors CircuitCheck.getActiveBrokenStates:
// the model-level rows plus the channel-level rows matching the key scope.
func (c *Check) GetActiveBrokenStates(ctx context.Context, channelModelID, channelID *int64, apiKeyID *int64) []CircuitBreakerState {
	var result []CircuitBreakerState
	if channelModelID != nil {
		if apiKeyID != nil {
			rows, _ := c.Store.Query(ctx,
				"SELECT * FROM circuit_breaker_states WHERE channel_model_id=? AND (channel_api_key_id=? OR channel_api_key_id IS NULL) AND is_open=1",
				*channelModelID, *apiKeyID)
			for _, r := range rows {
				result = append(result, rowToCircuitBreakerState(r))
			}
		} else {
			rows, _ := c.Store.Query(ctx, "SELECT * FROM circuit_breaker_states WHERE channel_model_id=? AND is_open=1", *channelModelID)
			for _, r := range rows {
				result = append(result, rowToCircuitBreakerState(r))
			}
		}
	}
	if channelID != nil {
		if apiKeyID != nil {
			rows, _ := c.Store.Query(ctx,
				"SELECT * FROM circuit_breaker_states WHERE channel_id=? AND channel_model_id IS NULL AND (channel_api_key_id=? OR channel_api_key_id IS NULL) AND is_open=1",
				*channelID, *apiKeyID)
			for _, r := range rows {
				result = append(result, rowToCircuitBreakerState(r))
			}
		} else {
			rows, _ := c.Store.Query(ctx, "SELECT * FROM circuit_breaker_states WHERE channel_id=? AND channel_model_id IS NULL AND is_open=1", *channelID)
			for _, r := range rows {
				result = append(result, rowToCircuitBreakerState(r))
			}
		}
	}
	return result
}

// Gate performs mutations on circuit_breaker_states.
type Gate struct {
	Store *store.Store
	// StateCache invalidated on every mutable operation so the routing loop
	// picks up the new state within the next cache lookup.
	StateCache *StateCache
}

// ListExpiredStates returns records whose probe is due (Java LIMIT 50).
//
// Comparison is normalised with datetime() because the live database mixes
// "YYYY-MM-DD HH:MM:SS" (migration defaults) and "YYYY-MM-DDTHH:MM:SS.fffffffff"
// (application writes); a raw string comparison would never see the T-format rows.
func (g *Gate) ListExpiredStates(ctx context.Context) []CircuitBreakerState {
	rows, _ := g.Store.Query(ctx,
		"SELECT * FROM circuit_breaker_states WHERE is_open=1 AND datetime(expire_at) <= datetime('now') ORDER BY expire_at ASC LIMIT 50")
	out := make([]CircuitBreakerState, 0, len(rows))
	for _, r := range rows {
		out = append(out, rowToCircuitBreakerState(r))
	}
	return out
}

// ListExpiredStatesByChannel mirrors listExpiredStatesByChannel.
func (g *Gate) ListExpiredStatesByChannel(ctx context.Context, channelID int64) []CircuitBreakerState {
	if channelID == 0 {
		return nil
	}
	rows, _ := g.Store.Query(ctx,
		"SELECT * FROM circuit_breaker_states WHERE channel_id=? AND is_open=1 AND datetime(expire_at) <= datetime('now') ORDER BY expire_at ASC", channelID)
	out := make([]CircuitBreakerState, 0, len(rows))
	for _, r := range rows {
		out = append(out, rowToCircuitBreakerState(r))
	}
	return out
}

// RecoverModelState mirrors CircuitGate.recoverModelState: open the model gate and
// cascade to the channel-level gate of the same key that opened no later.
func (g *Gate) RecoverModelState(ctx context.Context, state CircuitBreakerState) {
	n, _ := g.Store.Exec(ctx, "DELETE FROM circuit_breaker_states WHERE id = ?", state.ID)
	if n == 0 {
		// Record already removed by a concurrent flow; the cascade no longer applies.
		return
	}
	slog.Info("模型级门打开", "channelModelId", ptrStr(state.ChannelModelID),
		"channelId", state.ChannelID, "channelApiKeyId", ptrStr(state.ChannelAPIKeyID))

	if state.ChannelID != 0 && state.ChannelAPIKeyID != nil && state.OpenedAt != nil {
		g.Store.Exec(ctx,
			"DELETE FROM circuit_breaker_states WHERE channel_id=? AND channel_api_key_id=? AND channel_model_id IS NULL AND datetime(opened_at) <= datetime(?)",
			state.ChannelID, *state.ChannelAPIKeyID, jtime.FormatApp(*state.OpenedAt))
	}

	// 失效缓存：模型级状态已删除，可能还有渠道级级联删除
	if g.StateCache != nil {
		g.StateCache.InvalidateAll()
	}
}

// RenewState mirrors CircuitGate.renewState: keep the gate closed, extend expiry.
// When the caller stamped a probe outcome (state.LastProbeAt set by
// withProbeOutcome), the probe time/status/detail are persisted as well so
// the admin UI can show the most recent probe result.
func (g *Gate) RenewState(ctx context.Context, state CircuitBreakerState, durationSeconds int) {
	if durationSeconds < 1 {
		durationSeconds = 1
	}
	now := time.Now().UTC()
	failCount := state.FailCount + 1
	expireAt := jtime.FormatApp(now.Add(time.Duration(durationSeconds) * time.Second))
	if state.LastProbeAt != nil {
		g.Store.Exec(ctx, "UPDATE circuit_breaker_states SET expire_at=?, fail_count=?, last_probe_at=?, last_probe_status=?, last_probe_detail=?, updated_at=? WHERE id=?",
			expireAt, failCount, jtime.FormatApp(*state.LastProbeAt), state.LastProbeStatus, state.LastProbeDetail, jtime.FormatApp(now), state.ID)
	} else {
		g.Store.Exec(ctx, "UPDATE circuit_breaker_states SET expire_at=?, fail_count=?, updated_at=? WHERE id=?",
			expireAt, failCount, jtime.FormatApp(now), state.ID)
	}

	// 续期后缓存中的 expire_at 已过时，整体失效确保一致性
	if g.StateCache != nil {
		g.StateCache.InvalidateAll()
	}
}

// withProbeOutcome stamps a failed probe's outcome onto the state so that
// RenewState persists last_probe_at/status/detail for the admin UI bubble.
func withProbeOutcome(state CircuitBreakerState, result ProbeResult) CircuitBreakerState {
	at := time.Now().UTC()
	state.LastProbeAt = &at
	state.LastProbeStatus = nil
	if result.StatusCode > 0 {
		status := result.StatusCode
		state.LastProbeStatus = &status
	}
	state.LastProbeDetail = nil
	if detail := clampProbeDetail(result.Detail); detail != "" {
		state.LastProbeDetail = &detail
	}
	return state
}

// clampProbeDetail trims the raw probe response/error to a storable size,
// appending an ellipsis when the content had to be cut.
func clampProbeDetail(detail string) string {
	detail = strings.TrimSpace(detail)
	if detail == "" {
		return ""
	}
	runes := []rune(detail)
	if len(runes) > probeDetailMaxRunes {
		return string(runes[:probeDetailMaxRunes]) + "…"
	}
	return detail
}

// RemoveState deletes the record (opens the gate).
func (g *Gate) RemoveState(ctx context.Context, id int64) {
	g.Store.Exec(ctx, "DELETE FROM circuit_breaker_states WHERE id = ?", id)

	// 失效缓存：一条记录变为不熔断
	if g.StateCache != nil {
		g.StateCache.InvalidateAll()
	}
}

// ManualRecover mirrors CircuitMark.manualRecover: delete every active record
// visible to this (channelModelId, channelId, channelApiKeyId) scope.
func (g *Gate) ManualRecover(ctx context.Context, channelModelID, channelID int64, apiKeyID *int64) int {
	check := &Check{Store: g.Store}
	var cmID *int64
	if channelModelID != 0 {
		cmID = &channelModelID
	}
	var chID *int64
	if channelID != 0 {
		chID = &channelID
	}
	states := check.GetActiveBrokenStates(ctx, cmID, chID, apiKeyID)
	count := 0
	for _, s := range states {
		g.RemoveState(ctx, s.ID)
		count++
	}

	// 手动恢复可能导致多条状态记录被删除，整体失效缓存确保一致性
	if g.StateCache != nil && count > 0 {
		g.StateCache.InvalidateAll()
	}
	return count
}

func ptrStr(p *int64) string {
	if p == nil {
		return "<nil>"
	}
	return fmt.Sprint(*p)
}

type CircuitBreakerConfig struct {
	ModelID              int64
	RetryCount           int
	CircuitBreakDuration int
	CircuitBreakScope    string
	Enabled              int
}

type CircuitBreakerState struct {
	ID              int64
	ChannelID       int64
	ChannelAPIKeyID *int64
	ChannelModelID  *int64
	IsOpen          int
	FailCount       int
	OpenedAt        *time.Time
	ExpireAt        *time.Time
	// 最近一次探测信息：探测失败续期时写入；探测成功时记录即被删除。
	LastProbeAt     *time.Time
	LastProbeStatus *int
	LastProbeDetail *string
}

func rowToCircuitBreakerState(r store.Row) CircuitBreakerState {
	return CircuitBreakerState{
		ID:              r.I64("id", 0),
		ChannelID:       r.I64("channel_id", 0),
		ChannelAPIKeyID: r.I64Ptr("channel_api_key_id"),
		ChannelModelID:  r.I64Ptr("channel_model_id"),
		IsOpen:          r.Int("is_open", 0),
		FailCount:       r.Int("fail_count", 0),
		OpenedAt:        jtime.ScanTime(r["opened_at"]),
		ExpireAt:        jtime.ScanTime(r["expire_at"]),
		LastProbeAt:     jtime.ScanTime(r["last_probe_at"]),
		LastProbeStatus: r.IntPtr("last_probe_status"),
		LastProbeDetail: r.StrPtr("last_probe_detail"),
	}
}

// ---------------------------------------------------------------------------
// Probe / recovery
// ---------------------------------------------------------------------------

// ProbeTarget is the fully resolved set of entities needed to probe.
type ProbeTarget struct {
	ChannelID      int64
	ChannelName    string
	ChannelType    string
	BaseURL        string
	CustomHeaders  string
	ModelName      string
	APIKey         string
	APIKeyName     string
	ChannelModelID int64
}

// ProbeResult is the outcome of one probe request. Alive means the upstream
// answered 2xx; StatusCode is the HTTP status (0 when no response arrived) and
// Detail carries the raw failure explanation (response body or network error).
type ProbeResult struct {
	Alive      bool
	StatusCode int
	Detail     string
}

// ProbeService probes a channel's health with a minimal chat request
// (Java CircuitBreakerProbeService: max_tokens=1, 5s total timeout, 2xx = alive).
type ProbeService struct {
	Store *store.Store
	// HTTPDo performs the probe POST; injected so the relay package can own the client.
	HTTPDo func(ctx context.Context, target ProbeTarget, endpoint string, headers map[string]string, body string) ProbeResult

	// TimeoutFn returns the timeout in milliseconds for probing a specific model.
	// When nil, ProbeTimeoutSeconds is used as the default.
	TimeoutFn func(channelID, channelModelID int64) int64
}

// BuildEndpoint mirrors CandidateRouter.buildEndpoint.
func BuildEndpoint(channelType, baseURL string, fallbackType string) string {
	provider := channelType
	if strings.TrimSpace(provider) == "" {
		provider = fallbackType
	}
	base := strings.TrimSpace(baseURL)
	if base == "" {
		if provider == "anthropic" {
			base = "https://api.anthropic.com/v1"
		} else {
			base = "https://api.openai.com/v1"
		}
	}
	base = strings.TrimRight(base, "/")
	if provider == "azure" {
		return base
	}
	if provider == "anthropic" {
		return base + "/messages"
	}
	return base + "/chat/completions"
}

// BuildProviderHeaders mirrors the probe's buildProviderHeaders (channel type only).
func BuildProviderHeaders(channelType, apiKey, customHeadersJSON string) map[string]string {
	h := map[string]string{"Content-Type": "application/json"}
	key := strings.TrimSpace(apiKey)
	switch channelType {
	case "azure":
		h["api-key"] = key
	case "anthropic":
		h["x-api-key"] = key
		h["anthropic-version"] = "2023-06-01"
	default:
		h["Authorization"] = "Bearer " + key
	}
	if customHeadersJSON != "" {
		if extra := ParseCustomHeaders(customHeadersJSON); extra != nil {
			for k, v := range extra {
				h[k] = v
			}
		}
	}
	return h
}

// Probe sends the minimal request. A missing HTTPDo or model name counts as failure.
func (s *ProbeService) Probe(ctx context.Context, target ProbeTarget) ProbeResult {
	if s.HTTPDo == nil || target.ModelName == "" {
		return ProbeResult{Detail: "探测配置无效（缺少模型名或 HTTP 客户端）"}
	}
	endpoint := BuildEndpoint(target.ChannelType, target.BaseURL, target.ChannelType)
	headers := BuildProviderHeaders(target.ChannelType, target.APIKey, target.CustomHeaders)
	body := `{"model":` + jsonString(target.ModelName) + `,"max_tokens":1,"messages":[{"role":"user","content":"ping"}]}`
	timeoutMs := int64(ProbeTimeoutSeconds * 1000)
	if s.TimeoutFn != nil {
		if t := s.TimeoutFn(target.ChannelID, target.ChannelModelID); t > 0 {
			timeoutMs = t
		}
	}
	probeCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	return s.HTTPDo(probeCtx, target, endpoint, headers, body)
}

func jsonString(s string) string {
	b := make([]byte, 0, len(s)+2)
	b = append(b, '"')
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch c {
		case '"', '\\':
			b = append(b, '\\', c)
		case '\n':
			b = append(b, '\\', 'n')
		case '\r':
			b = append(b, '\\', 'r')
		case '\t':
			b = append(b, '\\', 't')
		default:
			b = append(b, c)
		}
	}
	return string(append(b, '"'))
}

// Resolution is the entity lookup surface the recovery loop needs.
// It is satisfied by an adapter in the server package, keeping this package
// free of relay imports (Java had ModelService / ChannelApiKeyService).
type Resolution interface {
	// ChannelEnabled returns enabled, channelType, baseURL, customHeaders, name, keyID, found.
	ChannelEnabled(ctx context.Context, channelID int64) (bool, string, string, string, string, string, bool)
	// ChannelModelEnabled returns enabled, channelID, modelName, apiKeyID, found.
	ChannelModelEnabled(ctx context.Context, channelModelID int64) (bool, int64, string, string, bool)
	// FirstEnabledChannelModel returns modelName, enabled, channelModelID, found.
	FirstEnabledChannelModel(ctx context.Context, channelID int64) (string, bool, int64, bool)
	// APIKeyEnabled returns apiKey, keyName, enabled, found.
	APIKeyEnabled(ctx context.Context, apiKeyID int64) (string, string, bool, bool)
	// FirstEnabledAPIKey returns apiKey, keyName, apiKeyID, found.
	FirstEnabledAPIKey(ctx context.Context, channelID int64) (string, string, int64, bool)
}

// RecoveryService drives the probe-recovery loop (Java CircuitBreakerRecoveryService).
type RecoveryService struct {
	Queue     chan int64
	Probe     *ProbeService
	Check     *Check
	Gate      *Gate
	ConfigMgr *ConfigManager
	Resolver  Resolution
	// ThrottleFn returns the probe throttle in seconds (0 disables throttling).
	ThrottleFn func(ctx context.Context) float64

	lastProbeAt  sync.Map // channelID -> int64 (unix millis)
	lastFullScan time.Time
	mu           sync.Mutex

	stopOnce sync.Once
	stop     chan struct{}
}

func NewRecoveryService() *RecoveryService {
	return &RecoveryService{
		Queue: make(chan int64, 10000),
		stop:  make(chan struct{}),
	}
}

// Start launches the single daemon worker goroutine (Java cb-probe-worker thread).
func (s *RecoveryService) Start() {
	go s.workerLoop()
}

// Stop terminates the worker.
func (s *RecoveryService) Stop() {
	s.stopOnce.Do(func() { close(s.stop) })
}

func (s *RecoveryService) workerLoop() {
	slog.Info("熔断探测工作线程启动")
	for {
		select {
		case <-s.stop:
			slog.Info("熔断探测工作线程退出")
			return
		case task := <-s.Queue:
			if task == FullScanMark {
				s.doFullScan()
			} else {
				s.maybeProbeChannel(task)
			}
		}
	}
}

// TriggerProbeByChannel offers a channel id, dropping the signal when the queue is full.
func (s *RecoveryService) TriggerProbeByChannel(channelID int64) {
	if channelID == 0 {
		return
	}
	select {
	case s.Queue <- channelID:
	default:
	}
}

// ScanExpiredGates enqueues the full-scan marker.
func (s *RecoveryService) ScanExpiredGates() {
	select {
	case s.Queue <- FullScanMark:
	default:
	}
}

func (s *RecoveryService) throttleSeconds(ctx context.Context) float64 {
	if s.ThrottleFn != nil {
		return s.ThrottleFn(ctx)
	}
	return defaultProbeThrottleSeconds
}

func (s *RecoveryService) maybeProbeChannel(channelID int64) {
	ctx := context.Background()
	throttleMs := int64(s.throttleSeconds(ctx) * 1000)
	now := time.Now().UnixMilli()
	if prev, ok := s.lastProbeAt.Load(channelID); ok && throttleMs > 0 {
		if now-prev.(int64) < throttleMs {
			return
		}
	}
	s.lastProbeAt.Store(channelID, now)

	expired := s.Gate.ListExpiredStatesByChannel(ctx, channelID)
	if len(expired) == 0 {
		return
	}
	slog.Info("调用触发熔断探测", "channelId", channelID, "到期记录", len(expired))
	for _, state := range expired {
		s.processExpiredState(ctx, state)
	}
}

func (s *RecoveryService) doFullScan() {
	ctx := context.Background()
	expired := s.Gate.ListExpiredStates(ctx)
	if len(expired) == 0 {
		return
	}
	slog.Info("熔断恢复全量扫描", "到期待处理", len(expired))
	for _, state := range expired {
		s.processExpiredState(ctx, state)
	}
}

func (s *RecoveryService) processExpiredState(ctx context.Context, state CircuitBreakerState) {
	if state.ID == 0 {
		return
	}
	if state.ChannelModelID != nil {
		s.handleModelGate(ctx, state)
	} else {
		s.handleChannelGate(ctx, state)
	}
}

// handleChannelGate mirrors CircuitBreakerRecoveryService.handleChannelGate.
func (s *RecoveryService) handleChannelGate(ctx context.Context, state CircuitBreakerState) {
	if state.ChannelID == 0 {
		s.Gate.RemoveState(ctx, state.ID)
		return
	}
	enabled, chType, baseURL, customHeaders, chName, _, ok := s.Resolver.ChannelEnabled(ctx, state.ChannelID)
	if !ok || !enabled {
		slog.Info("渠道已删除或禁用，直接清理熔断记录", "channelId", state.ChannelID)
		s.Gate.RemoveState(ctx, state.ID)
		return
	}
	// Probe key: the bound key, or any enabled key for a whole-channel gate.
	var apiKey, keyName string
	if state.ChannelAPIKeyID != nil {
		var exists bool
		apiKey, keyName, enabled, exists = s.Resolver.APIKeyEnabled(ctx, *state.ChannelAPIKeyID)
		if !exists || !enabled {
			slog.Info("无可用 API Key 可探测，直接清理熔断记录", "channelId", state.ChannelID)
			s.Gate.RemoveState(ctx, state.ID)
			return
		}
	} else {
		var kID int64
		var exists bool
		apiKey, keyName, kID, exists = s.Resolver.FirstEnabledAPIKey(ctx, state.ChannelID)
		if !exists || kID == 0 {
			slog.Info("无可用 API Key 可探测，直接清理熔断记录", "channelId", state.ChannelID)
			s.Gate.RemoveState(ctx, state.ID)
			return
		}
	}
	modelName, cmEnabled, cmID, cmOK := s.Resolver.FirstEnabledChannelModel(ctx, state.ChannelID)
	if !cmOK || !cmEnabled {
		slog.Info("无可用渠道模型可探测，直接清理熔断记录", "channelId", state.ChannelID)
		s.Gate.RemoveState(ctx, state.ID)
		return
	}
	result := s.Probe.Probe(ctx, ProbeTarget{
		ChannelID: state.ChannelID, ChannelName: chName, ChannelType: chType,
		BaseURL: baseURL, CustomHeaders: customHeaders,
		ModelName: modelName, APIKey: apiKey, APIKeyName: keyName, ChannelModelID: cmID,
	})
	if result.Alive {
		s.Gate.RemoveState(ctx, state.ID)
	} else {
		duration := s.ConfigMgr.GetDurationByChannelModelID(ctx, cmID)
		s.Gate.RenewState(ctx, withProbeOutcome(state, result), duration)
		slog.Warn("渠道级探测失败，门保持关闭并续期", "channel", chName, "key", keyName, "duration", duration)
	}
}

// handleModelGate mirrors CircuitBreakerRecoveryService.handleModelGate.
func (s *RecoveryService) handleModelGate(ctx context.Context, state CircuitBreakerState) {
	channelModelID := *state.ChannelModelID

	enabled, channelID, modelName, _, ok := s.Resolver.ChannelModelEnabled(ctx, channelModelID)
	if !ok || !enabled {
		slog.Info("渠道模型已删除或禁用，直接清理熔断记录", "channelModelId", channelModelID)
		s.Gate.RemoveState(ctx, state.ID)
		return
	}
	chEnabled, chType, baseURL, customHeaders, chName, _, chOK := s.Resolver.ChannelEnabled(ctx, channelID)
	if !chOK || !chEnabled {
		slog.Info("渠道已删除或禁用，直接清理熔断记录", "channelId", channelID)
		s.Gate.RemoveState(ctx, state.ID)
		return
	}
	var apiKey, keyName string
	if state.ChannelAPIKeyID != nil {
		var keyEnabled, exists bool
		apiKey, keyName, keyEnabled, exists = s.Resolver.APIKeyEnabled(ctx, *state.ChannelAPIKeyID)
		if !exists || !keyEnabled {
			slog.Info("无可用 API Key 可探测，直接清理熔断记录", "channelId", channelID, "channelModelId", channelModelID)
			s.Gate.RemoveState(ctx, state.ID)
			return
		}
	} else {
		var kID int64
		var exists bool
		apiKey, keyName, kID, exists = s.Resolver.FirstEnabledAPIKey(ctx, channelID)
		if !exists || kID == 0 {
			slog.Info("无可用 API Key 可探测，直接清理熔断记录", "channelId", channelID, "channelModelId", channelModelID)
			s.Gate.RemoveState(ctx, state.ID)
			return
		}
	}
	result := s.Probe.Probe(ctx, ProbeTarget{
		ChannelID: channelID, ChannelName: chName, ChannelType: chType,
		BaseURL: baseURL, CustomHeaders: customHeaders,
		ModelName: modelName, APIKey: apiKey, APIKeyName: keyName, ChannelModelID: channelModelID,
	})
	if result.Alive {
		s.Gate.RecoverModelState(ctx, state)
	} else {
		duration := s.ConfigMgr.GetDurationByChannelModelID(ctx, channelModelID)
		s.Gate.RenewState(ctx, withProbeOutcome(state, result), duration)
		slog.Warn("熔断探测失败，门保持关闭并续期", "channel", chName, "model", modelName, "key", keyName, "duration", duration)
	}
}

// RelBrokenMark is the display mark for one rel (Java CircuitBreakerService.RelBrokenMark).
type RelBrokenMark struct {
	Scope    string     // "channel" | "model" | "both"
	ExpireAt *time.Time // earliest expiry = next probe time
	// 最近一次探测信息：取熔断范围内探测时间最新的一条记录；从未探测时为 nil。
	LastProbeAt     *time.Time
	LastProbeStatus *int
	LastProbeDetail *string
}

// PathAvailable mirrors CircuitCheck.isPathAvailable over pre-grouped states.
func PathAvailable(channelID, keyID int64, modelStates, channelStates []CircuitBreakerState) bool {
	for _, s := range channelStates {
		if s.ChannelAPIKeyID == nil {
			return false
		}
	}
	for _, s := range channelStates {
		if s.ChannelAPIKeyID != nil && *s.ChannelAPIKeyID == keyID {
			return false
		}
	}
	for _, s := range modelStates {
		if s.ChannelAPIKeyID == nil || *s.ChannelAPIKeyID == keyID {
			return false
		}
	}
	return true
}

// EvaluateRelBroken mirrors CircuitMark.evaluateRelBroken over preloaded data.
// Returns nil when the rel is not fully broken (or cannot be judged).
func EvaluateRelBroken(channelModelID, channelID int64, relKeyID *int64,
	states []CircuitBreakerState,
	enabledKeys []KeyRef, keysByID map[int64]KeyRef) *RelBrokenMark {

	var modelStates, channelStates []CircuitBreakerState
	for _, s := range states {
		if s.ChannelID != channelID {
			continue
		}
		if s.ChannelModelID == nil {
			if relKeyID == nil || (s.ChannelAPIKeyID != nil && *s.ChannelAPIKeyID == *relKeyID) || s.ChannelAPIKeyID == nil {
				channelStates = append(channelStates, s)
			}
		} else if *s.ChannelModelID == channelModelID {
			if relKeyID == nil || (s.ChannelAPIKeyID != nil && *s.ChannelAPIKeyID == *relKeyID) || s.ChannelAPIKeyID == nil {
				modelStates = append(modelStates, s)
			}
		}
	}

	var paths []KeyRef
	if relKeyID != nil {
		bound, ok := keysByID[*relKeyID]
		if !ok || !bound.Enabled {
			return nil // 绑定 Key 缺失/禁用：配置问题而非熔断
		}
		paths = []KeyRef{bound}
	} else {
		paths = enabledKeys
	}
	if len(paths) == 0 {
		return nil
	}
	for _, key := range paths {
		if PathAvailable(channelID, key.ID, modelStates, channelStates) {
			return nil
		}
	}
	mark := &RelBrokenMark{}
	hasChannel := len(channelStates) > 0
	hasModel := len(modelStates) > 0
	switch {
	case hasChannel && hasModel:
		mark.Scope = "both"
	case hasChannel:
		mark.Scope = "channel"
	default:
		mark.Scope = "model"
	}
	combined := append(append([]CircuitBreakerState{}, channelStates...), modelStates...)
	for _, s := range combined {
		if s.ExpireAt != nil && (mark.ExpireAt == nil || s.ExpireAt.Before(*mark.ExpireAt)) {
			t := *s.ExpireAt
			mark.ExpireAt = &t
		}
	}
	// 最近探测信息：多条记录时取探测时间最新的一条（状态码/详情与其成组）
	for _, s := range combined {
		if s.LastProbeAt != nil && (mark.LastProbeAt == nil || s.LastProbeAt.After(*mark.LastProbeAt)) {
			t := *s.LastProbeAt
			mark.LastProbeAt = &t
			mark.LastProbeStatus = nil
			if s.LastProbeStatus != nil {
				v := *s.LastProbeStatus
				mark.LastProbeStatus = &v
			}
			mark.LastProbeDetail = nil
			if s.LastProbeDetail != nil {
				v := *s.LastProbeDetail
				mark.LastProbeDetail = &v
			}
		}
	}
	return mark
}

// KeyRef is a minimal API key reference for mark computation.
type KeyRef struct {
	ID      int64
	Enabled bool
}

// ParseCustomHeaders decodes the custom_headers JSON object.
func ParseCustomHeaders(raw string) map[string]string {
	raw = strings.TrimSpace(raw)
	if raw == "" || raw == "null" {
		return nil
	}
	out := make(map[string]string)
	if err := json.Unmarshal([]byte(raw), &out); err != nil {
		return nil
	}
	return out
}
