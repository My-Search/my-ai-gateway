package logsvc

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// PendingRequestData holds raw request headers/body for lazy persistence,
// matching the Java RequestLogService.PendingRequestData behaviour.
type PendingRequestData struct {
	Headers  string
	Body     string
	HasRetry bool
}

// requestDataSaveLevel mirrors AdminConfigService KEY_REQUEST_DATA_SAVE_LEVEL.
type requestDataSaveLevel int

const (
	saveLevelInfo  requestDataSaveLevel = iota // always persist
	saveLevelWarn                              // persist only on retry or final failure
	saveLevelError                             // persist only on final failure
	saveLevelNone                              // never persist
)

// LogWriter writes request_log rows and broadcasts them via LogSseService.
type LogWriter struct {
	store      *store.Store
	sseService *LogSseService
	configSvc  ConfigReader // optional — may be nil (defaults to "info")

	// mu guards pending. The LogWriter is a single instance shared by every
	// concurrent request goroutine, so the map must never be touched unlocked.
	mu      sync.Mutex
	pending map[string]*PendingRequestData
}

// ConfigReader is the minimal interface LogWriter needs from ConfigService.
type ConfigReader interface {
	GetValue(ctx context.Context, key, def string) string
}

// NewLogWriter creates a LogWriter.  configSvc may be nil.
func NewLogWriter(st *store.Store, sse *LogSseService, cfg ConfigReader) *LogWriter {
	return &LogWriter{
		store:      st,
		sseService: sse,
		configSvc:  cfg,
		pending:    make(map[string]*PendingRequestData),
	}
}

// ---------------------------------------------------------------------------
// Public write methods (mirror RequestLogService)
// ---------------------------------------------------------------------------

// WriteStart records the initial "请求开始" entry and stashes raw request data
// in memory for later persistence according to the save level.
// api_key_name is left NULL (the gateway key id is stored instead), matching
// Java's logStartWithReasoningEffort(traceId, null, gatewayApiKeyId, ...).
func (w *LogWriter) WriteStart(ctx context.Context, traceID, modelName string, gwKeyID int64, headers, body, reasoningEffort string) {
	w.mu.Lock()
	w.pending[traceID] = &PendingRequestData{Headers: headers, Body: body}
	w.mu.Unlock()

	var gwID *int64
	if gwKeyID > 0 {
		gwID = &gwKeyID
	}

	rec := &models.RequestLog{
		TraceID:         traceID,
		ModelName:       models.Str(modelName),
		GatewayAPIKeyID: gwID,
		Phase:           "start",
		Status:          models.Str("pending"),
		Message:         models.Str("请求开始"),
		RetryIndex:      models.Int(0),
		ReasoningEffort: models.Str(reasoningEffort),
		CreatedAt:       nowAPITime(),
	}
	w.insertAndPublish(ctx, rec)
}

// WriteCandidatePhase records a candidate-level phase (start / skip / retry),
// mirroring RelayLogger.logPhase.  apiKeyName is the *channel* API key name.
// responseTimeMs may be nil (most phases carry no duration).
func (w *LogWriter) WriteCandidatePhase(ctx context.Context, traceID, apiKeyName, modelName,
	channelModelName, channelName string, gwKeyID int64,
	phase, status, msg string, retryIndex int, responseTimeMs *int64, reasoningEffort string) {

	var gwID *int64
	if gwKeyID > 0 {
		gwID = &gwKeyID
	}
	var keyName *string
	if apiKeyName != "" {
		keyName = &apiKeyName
	}

	rec := &models.RequestLog{
		TraceID:          traceID,
		APIKeyName:       keyName,
		ModelName:        models.Str(modelName),
		ChannelModelName: models.Str(channelModelName),
		ChannelName:      models.Str(channelName),
		GatewayAPIKeyID:  gwID,
		Phase:            phase,
		Status:           models.Str(status),
		Message:          models.Str(msg),
		RetryIndex:       models.Int(retryIndex),
		ReasoningEffort:  models.Str(reasoningEffort),
		CreatedAt:        nowAPITime(),
	}
	if responseTimeMs != nil {
		rt := int(*responseTimeMs)
		rec.ResponseTimeMs = &rt
	}
	w.insertAndPublish(ctx, rec)
	w.markRetryIfNeeded(traceID, phase)
}

// WriteSuccess records the final success entry with channel info and token usage.
// apiKeyName is the channel API key name; message is "请求成功" (non-stream) or
// "流式请求成功" / "流式请求成功（用量为估算值）" (stream).
// firstByteMs may be nil when no byte was observed.
func (w *LogWriter) WriteSuccess(ctx context.Context, traceID, apiKeyName, modelName,
	channelModelName, channelName string, gwKeyID int64, msg string,
	responseTimeMs int64, firstByteMs *int64, retryIndex, promptTokens, completionTokens, totalTokens int) {

	var gwID *int64
	if gwKeyID > 0 {
		gwID = &gwKeyID
	}

	rt := int(responseTimeMs)
	rec := &models.RequestLog{
		TraceID:          traceID,
		APIKeyName:       models.Str(apiKeyName),
		ModelName:        models.Str(modelName),
		ChannelModelName: models.Str(channelModelName),
		ChannelName:      models.Str(channelName),
		GatewayAPIKeyID:  gwID,
		Phase:            "success",
		Status:           models.Str("success"),
		Message:          models.Str(msg),
		RetryIndex:       models.Int(retryIndex),
		ResponseTimeMs:   models.Int(rt),
		PromptTokens:     models.Int(promptTokens),
		CompletionTokens: models.Int(completionTokens),
		TotalTokens:      models.Int(totalTokens),
		CreatedAt:        nowAPITime(),
	}
	if firstByteMs != nil {
		fb := int(*firstByteMs)
		rec.FirstByteMs = &fb
	}
	w.insertAndPublish(ctx, rec)
	w.flushPendingRequestData(ctx, traceID, "success")
}

// WriteFail records the final failure entry.  Channel info and api_key_name are
// left NULL, matching Java's final logComplete(..., null, null, "fail", "error", ...).
func (w *LogWriter) WriteFail(ctx context.Context, traceID, modelName string, gwKeyID int64,
	status, msg string, responseTimeMs int64, firstByteMs *int64, retryIndex int) {

	var gwID *int64
	if gwKeyID > 0 {
		gwID = &gwKeyID
	}

	rt := int(responseTimeMs)
	rec := &models.RequestLog{
		TraceID:         traceID,
		ModelName:       models.Str(modelName),
		GatewayAPIKeyID: gwID,
		Phase:           "fail",
		Status:          models.Str(status),
		Message:         models.Str(msg),
		RetryIndex:      models.Int(retryIndex),
		ResponseTimeMs:  models.Int(rt),
		CreatedAt:       nowAPITime(),
	}
	if firstByteMs != nil {
		fb := int(*firstByteMs)
		rec.FirstByteMs = &fb
	}
	w.insertAndPublish(ctx, rec)
	w.flushPendingRequestData(ctx, traceID, "fail")
}

// ---------------------------------------------------------------------------
// internal helpers
// ---------------------------------------------------------------------------

// insertAndPublish writes a log row to the DB and broadcasts via SSE.
func (w *LogWriter) insertAndPublish(ctx context.Context, log *models.RequestLog) {
	// Detach from the request context: a client disconnect cancels it, and the
	// terminal log row must still be written rather than silently dropped.
	ctx = context.WithoutCancel(ctx)

	// Use the same format as logSimple for consistent SQLite query comparisons.
	now := time.Now().UTC().Format("2006-01-02T15:04:05.000000000")
	var gwID any
	if log.GatewayAPIKeyID != nil {
		gwID = *log.GatewayAPIKeyID
	}
	var rt any
	if log.ResponseTimeMs != nil {
		rt = *log.ResponseTimeMs
	}
	var fb any
	if log.FirstByteMs != nil {
		fb = *log.FirstByteMs
	}
	var apiKeyName any
	if log.APIKeyName != nil {
		apiKeyName = *log.APIKeyName
	}
	id, err := w.store.Insert(ctx,
		`INSERT INTO request_logs
		(trace_id, api_key_name, gateway_api_key_id, model_name, channel_model_name, channel_name,
		 phase, status, message, retry_index,
		 response_time_ms, first_byte_ms,
		 prompt_tokens, completion_tokens, total_tokens,
		 reasoning_effort, created_at)
		VALUES (?, ?, ?, ?, ?, ?,
		        ?, ?, ?, ?,
		        ?, ?,
		        ?, ?, ?,
		        ?, ?)`,
		log.TraceID,
		apiKeyName, gwID,
		models.DerefStr(log.ModelName, ""),
		models.DerefStr(log.ChannelModelName, ""), models.DerefStr(log.ChannelName, ""),
		log.Phase, models.DerefStr(log.Status, ""), models.DerefStr(log.Message, ""),
		models.DerefInt(log.RetryIndex, 0),
		rt, fb,
		models.DerefInt(log.PromptTokens, 0), models.DerefInt(log.CompletionTokens, 0),
		models.DerefInt(log.TotalTokens, 0),
		models.DerefStr(log.ReasoningEffort, ""), now)

	if err != nil {
		slog.Warn("写入请求日志失败", "traceId", log.TraceID, "phase", log.Phase, "error", err)
		return
	}

	// Fill in the auto-generated ID so the frontend can deduplicate properly.
	log.ID = id

	// Broadcast to SSE subscribers (non-blocking).
	if w.sseService != nil {
		w.sseService.Publish(log)
	}
}

// markRetryIfNeeded flags the trace as having seen a real retry (phase="retry"),
// mirroring Java markRetryIfNeeded so the "warn" save level keeps request data.
func (w *LogWriter) markRetryIfNeeded(traceID, phase string) {
	if phase != "retry" {
		return
	}
	w.mu.Lock()
	if data, ok := w.pending[traceID]; ok {
		data.HasRetry = true
	}
	w.mu.Unlock()
}

// flushPendingRequestData persists raw request headers/body to the start record
// based on the configured save level, mirroring Java's saveRequestDataIfNeeded.
func (w *LogWriter) flushPendingRequestData(ctx context.Context, traceID, finalPhase string) {
	// Detach from the request context so a client disconnect (which cancels ctx
	// and is common for streams) cannot make the persist silently vanish.
	ctx = context.WithoutCancel(ctx)

	w.mu.Lock()
	pending, ok := w.pending[traceID]
	if !ok {
		w.mu.Unlock()
		return
	}
	delete(w.pending, traceID)
	w.mu.Unlock()

	level := saveLevelInfo
	if w.configSvc != nil {
		level = parseSaveLevel(w.configSvc.GetValue(ctx, "request_data_save_level", "info"))
	}

	failed := finalPhase == "fail"
	shouldKeep := false
	switch level {
	case saveLevelNone:
		shouldKeep = false
	case saveLevelError:
		shouldKeep = failed
	case saveLevelWarn:
		shouldKeep = failed || pending.HasRetry
	default: // info
		shouldKeep = true
	}

	if !shouldKeep {
		return
	}

	// Build a dynamic UPDATE for whichever fields are set.
	var sets []string
	var args []any
	if pending.Headers != "" {
		sets = append(sets, "request_headers = ?")
		args = append(args, pending.Headers)
	}
	if pending.Body != "" {
		sets = append(sets, "request_body = ?")
		args = append(args, pending.Body)
	}
	if len(sets) == 0 {
		return
	}
	args = append(args, traceID)

	q := "UPDATE request_logs SET "
	for i, s := range sets {
		if i > 0 {
			q += ", "
		}
		q += s
	}
	q += " WHERE trace_id = ? AND phase = 'start'"
	if _, err := w.store.Exec(ctx, q, args...); err != nil {
		slog.Warn("保存原始请求数据失败", "traceId", traceID, "saveLevel", level, "error", err)
	}
}

func parseSaveLevel(v string) requestDataSaveLevel {
	switch v {
	case "none":
		return saveLevelNone
	case "error":
		return saveLevelError
	case "warn":
		return saveLevelWarn
	default:
		return saveLevelInfo
	}
}

func nowAPITime() jtime.APITime { return jtime.NewAPITime(time.Now().UTC()) }
