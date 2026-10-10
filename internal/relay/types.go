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
	// ProtoResponses is the OpenAI-native Responses API (POST /v1/responses).
	// It is both an inbound *client* format and a valid upstream channel
	// protocol: a channel whose channel_type is "responses" is sent Responses
	// requests and its Responses responses are translated to the client format.
	ProtoResponses = "responses"
)

// Group strategy constants: how a model group orders its own members.
//
// These mirror the values persisted in model_groups.strategy; the relay package
// keeps its own copy so the routing engine has no dependency on the admin model
// package. Unknown/blank values fall back to random (see normalizeGroupStrategy).
const (
	GroupStrategyFailover   = "failover"
	GroupStrategyRandom     = "random"
	GroupStrategyRoundRobin = "round_robin"
)

// Client protocol slugs recorded on circuit-breaker records so a broken path is
// later probed with the very protocol that failed, and so the admin UI can show
// which protocol tripped the breaker. Unlike ProtoOpenAI/ProtoAnthropic (which
// describe the wire format), these identify the actual inbound endpoint.
const (
	ClientProtocolOpenAIChat        = "openai-chat"
	ClientProtocolAnthropicMessages = "anthropic-messages"
	ClientProtocolOpenAIResponses   = "openai-responses"
	ClientProtocolEmbeddings        = "embeddings"
)

// ClientProtocol derives the inbound-protocol slug from a parsed request.
// Embeddings reuses the OpenAI wire format, so it is distinguished by the
// endpoint override rather than by ClientAPIFormat.
func ClientProtocol(req *InternalRequest) string {
	if req == nil {
		return ClientProtocolOpenAIChat
	}
	if req.EndpointPath == embeddingsEndpoint {
		return ClientProtocolEmbeddings
	}
	switch req.ClientAPIFormat {
	case ProtoAnthropic:
		return ClientProtocolAnthropicMessages
	case ProtoResponses:
		return ClientProtocolOpenAIResponses
	default:
		return ClientProtocolOpenAIChat
	}
}

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

// PhaseInterrupted is the terminal phase for a request whose parent context was
// canceled mid-flight (caller/connection gone, or the process shutting down).
// It is deliberately distinct from PhaseFail: no candidate proved faulty, so the
// trace must not read as "every member failed".
const PhaseInterrupted = "interrupted"

// StatusClientClosedRequest (nginx's 499) reports an interrupted request. Nobody
// reads the code once the connection is gone; it exists so the gateway's own
// logs and metrics never confuse an interruption with a real upstream failure.
const StatusClientClosedRequest = 499

// InterruptedMessage explains a canceled request without blaming a candidate.
const InterruptedMessage = "客户端已断开或请求已取消"

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

// isRequestFaultStatus reports whether an upstream status blames the request,
// not the model service. Such a candidate gets exactly one attempt — no retry
// and no breaker trip — because the model is healthy and a faultless request
// would succeed; tripping the breaker would take it down for every tenant.
// Note 401/403/404 are deliberately absent: a wrong key or model name fails
// every request until we fix the configuration, so they must trip the breaker.
func isRequestFaultStatus(status int) bool {
	switch status {
	case 400, 413, 422:
		return true
	default:
		return false
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

	// Passthrough is a raw upstream request body for non-chat endpoints
	// (currently POST /v1/embeddings). When non-empty, BuildProviderRequest
	// forwards it verbatim (only rewriting the model field) instead of
	// rendering the request from Messages.
	Passthrough json.RawMessage
	// EndpointPath overrides the upstream URL suffix (e.g. "/embeddings").
	// Empty means the protocol default ("/chat/completions" or "/messages").
	EndpointPath string
	// EstimatedTokens, when > 0, overrides the message-based token heuristic
	// used by the context-limit guard for payloads without chat messages.
	EstimatedTokens int64
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
	// Input is the channel model's supported media types ("text", "image", ...),
	// consumed by the media-type routing skip (RequestPreprocessor.skipIfMediaTypeUnsupported).
	Input string
	// ContextLength is the channel model's context window in tokens (from our
	// context rules / the models.dev catalog). 0 means unknown: no context skip.
	ContextLength int64
	// Weight is the routing weight in effect for this candidate: the model
	// relation's weight for direct relations, the group member's weight inside a
	// model group. It drives weighted random selection and the sticky hash ring;
	// <=0 is treated as 1.
	Weight int
	// GroupMemberID identifies the owning model_group_members row. Multiple API
	// keys expand one group member into several candidates; group-level selection
	// and sticky hashing treat that block as one weighted member.
	GroupMemberID int64
	// GroupID / GroupName / GroupStrategy describe the model group this candidate
	// came from; zero/empty for a direct channel-model relation. They exist for
	// display (request-log provenance), never for routing decisions.
	GroupID       int64
	GroupName     string
	GroupStrategy string
	// RouteSource labels how this candidate was selected, written to the request
	// log: sticky = the group's session hash pinned this member,
	// sticky_fallback = the pinned member failed and this member took over.
	// Empty for non-sticky routing.
	RouteSource string
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
