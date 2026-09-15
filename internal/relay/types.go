// Package relay implements the core AI gateway request relay engine.
package relay

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Protocol constants.
const (
	ProtoOpenAI    = "openai"
	ProtoAnthropic = "anthropic"
	ProtoAzure     = "azure"
)

// Phase/Status values for request_logs.
const (
	PhaseStart        = "start"
	PhaseRetry        = "retry"
	PhaseSkip         = "skip"
	PhaseSuccess      = "success"
	PhaseFail         = "fail"
	StatusPending     = "pending"
	StatusSuccess     = "success"
	StatusError       = "error"
	StatusInterrupted = "interrupted"
	StatusAuth        = "auth"
	StatusTimeout     = "timeout"
)

// Timeout constants.
const (
	NonStreamMinTimeoutMs = 30000
	MaxTotalTimeoutMs     = 600000
	StreamIdleTimeoutMs   = 60000
	DefaultMinTimeoutMs   = 20000
	DefaultMaxTimeoutMs   = 60000
)

// SseEvent is the SSE event record.
type SseEvent struct {
	Event string `json:"event,omitempty"`
	Data  string `json:"data"`
}

// NonRetryableProviderError mirrors the Java NonRetryableProviderException.
type NonRetryableProviderError struct {
	HTTPStatus int
	Message    string
}

func (e *NonRetryableProviderError) Error() string { return e.Message }

func NewNonRetryableError(status int, body string) *NonRetryableProviderError {
	return &NonRetryableProviderError{
		HTTPStatus: status,
		Message:    "Provider client error: " + itoa(status) + " body: " + body,
	}
}

// InternalRequest is the unified internal request representation.
type InternalRequest struct {
	Model              string
	Messages           []InternalMessage
	SystemPrompt       string
	MaxTokens          *int
	Temperature        *float64
	TopP               *float64
	Stream             bool
	Stop               []string
	Tools              []map[string]any
	ToolChoice         any
	StreamOptions      any
	ExtraParams        map[string]any
	OriginalRequestRaw json.RawMessage
	ClientAPIFormat    string
	ContextRetry       bool
	ReasoningEffort    *string
	DetectedMediaTypes []string
}

// InternalMessage is the unified internal message representation.
type InternalMessage struct {
	Role             string
	Content          string
	ContentParts     []map[string]any
	ToolCalls        []map[string]any
	ToolCallID       string
	Name             string
	ReasoningContent *string
}

// RoutingCandidate describes one possible destination for a request.
type RoutingCandidate struct {
	RelID           int64
	ChannelModelID  int64
	ChannelID       int64
	ChannelName     string
	ChannelType     string
	ModelName       string
	APIKey          string
	APIKeyID        int64
	APIKeyName      string
	BaseURL         string
	CustomHeaders   string
	SortOrder       int
	ReasoningEffort *string
	// Input is the channel model's supported media types ("text", "text,image", ...),
	// consumed by the media-type routing skip (RequestPreprocessor.skipIfMediaTypeUnsupported).
	Input string
}

// LatencyTracker provides adaptive timeouts — same logic as Java LatencyTracker.
type LatencyTracker struct {
	mu      sync.Mutex
	samples map[string][]int64
	minFn   func() int64
	maxFn   func() int64
}

// NewLatencyTracker creates a tracker. minFn/maxFn are evaluated on every
// GetTimeout call so that the timeout_min_seconds / timeout_max_seconds system
// configuration takes effect immediately (Java reads AdminConfigService live).
func NewLatencyTracker(minFn, maxFn func() int64) *LatencyTracker {
	return &LatencyTracker{
		samples: make(map[string][]int64),
		minFn:   minFn,
		maxFn:   maxFn,
	}
}

// NewLatencyTrackerFixed is a convenience constructor with constant bounds.
func NewLatencyTrackerFixed(minMs, maxMs int64) *LatencyTracker {
	return NewLatencyTracker(func() int64 { return minMs }, func() int64 { return maxMs })
}

func (t *LatencyTracker) Record(channelID, channelModelID int64, ttftMs int64) {
	if ttftMs <= 0 {
		return
	}
	key := formatKey(channelID, channelModelID)
	t.mu.Lock()
	s := t.samples[key]
	s = append(s, ttftMs)
	if len(s) > 30 {
		s = s[len(s)-30:]
	}
	t.samples[key] = s
	t.mu.Unlock()
}

func (t *LatencyTracker) RecordTimeout(channelID, channelModelID int64, timeoutMs int64) {
	t.Record(channelID, channelModelID, timeoutMs)
}

func (t *LatencyTracker) GetTimeout(channelID, channelModelID int64) int64 {
	minMs, maxMs := t.bounds()
	key := formatKey(channelID, channelModelID)
	t.mu.Lock()
	s := t.samples[key]
	t.mu.Unlock()
	if len(s) < 3 {
		return maxMs
	}
	var sum int64
	for _, v := range s {
		sum += v
	}
	avg := sum / int64(len(s))
	timeout := avg * 3
	if timeout < minMs {
		return minMs
	}
	if timeout > maxMs {
		return maxMs
	}
	return timeout
}

// bounds returns the live min/max timeout in milliseconds (Java default 20s/60s).
func (t *LatencyTracker) bounds() (int64, int64) {
	return t.minFn(), t.maxFn()
}

// StreamContentManager accumulates stream content for reroute context splicing.
type StreamContentManager struct {
	mu       sync.Mutex
	contents map[string]*strings.Builder
}

func NewStreamContentManager() *StreamContentManager {
	return &StreamContentManager{contents: make(map[string]*strings.Builder)}
}

func (m *StreamContentManager) Append(traceID, text string) {
	m.mu.Lock()
	b, ok := m.contents[traceID]
	if !ok {
		b = &strings.Builder{}
		m.contents[traceID] = b
	}
	b.WriteString(text)
	m.mu.Unlock()
}

func (m *StreamContentManager) GetAndClear(traceID string) string {
	m.mu.Lock()
	b, ok := m.contents[traceID]
	if ok {
		delete(m.contents, traceID)
	}
	m.mu.Unlock()
	if b != nil {
		return b.String()
	}
	return ""
}

func (m *StreamContentManager) Get(traceID string) string {
	m.mu.Lock()
	b := m.contents[traceID]
	m.mu.Unlock()
	if b != nil {
		return b.String()
	}
	return ""
}

func (m *StreamContentManager) Clear(traceID string) {
	m.mu.Lock()
	delete(m.contents, traceID)
	m.mu.Unlock()
}

// formatKey builds "a:b" for a pair of int64s without collisions.
func formatKey(a, b int64) string {
	return strconv.FormatInt(a, 10) + ":" + strconv.FormatInt(b, 10)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}

func genTraceID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

var _ = json.RawMessage{}
var _ = time.Second
var _ = context.Background
