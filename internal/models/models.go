// Package models mirrors the Java entity classes that the REST API serialises.
//
// Field order and JSON names match the original exactly because the Vue
// front-end reads these shapes directly. Optional numeric columns are modelled
// as pointers so that NULL round-trips as JSON null (Jackson serialised nulls;
// there is no @JsonInclude(NON_NULL) anywhere in the original).
package models

import (
	"time"

	"github.com/my-search/my-ai-gateway/internal/jtime"
)

// APITime is an alias for the shared optional timestamp type.
type APITime = jtime.APITime

// TimePtr wraps a time value in a pointer.
func TimePtr(t time.Time) *time.Time { return jtime.Ptr(t) }

// ---------------------------------------------------------------------------
// Channel
// ---------------------------------------------------------------------------

type Channel struct {
	ID                  int64   `json:"id"`
	Name                string  `json:"name"`
	ChannelType         string  `json:"channelType"`
	APIKey              string  `json:"apiKey"`
	BaseURL             string  `json:"baseUrl"`
	Enabled             *int    `json:"enabled"`
	SortOrder           *int    `json:"sortOrder"`
	ModelRefreshEnabled *int    `json:"modelRefreshEnabled"`
	CustomHeaders       *string `json:"customHeaders"`
	CreatedAt           APITime `json:"createdAt"`
	UpdatedAt           APITime `json:"updatedAt"`
	APIKeys             any     `json:"apiKeys"`
	Models              any     `json:"models"`
}

// ChannelAPIKey mirrors channel_api_keys.
type ChannelAPIKey struct {
	ID        int64   `json:"id"`
	ChannelID int64   `json:"channelId"`
	KeyName   string  `json:"keyName"`
	APIKey    string  `json:"apiKey"`
	Enabled   *int    `json:"enabled"`
	SortOrder *int    `json:"sortOrder"`
	CreatedAt APITime `json:"createdAt"`
	UpdatedAt APITime `json:"updatedAt"`
}

// ChannelModel mirrors channel_models.
type ChannelModel struct {
	ID             int64   `json:"id"`
	ChannelID      *int64  `json:"channelId"`
	ChannelAPIKeyID *int64 `json:"channelApiKeyId"`
	ModelName      string  `json:"modelName"`
	DisplayName    *string `json:"displayName"`
	Enabled        *int    `json:"enabled"`
	LastUsedAt     APITime `json:"lastUsedAt"`
	Source         *string `json:"source"`
	Input          *string `json:"input"`
	CreatedAt      APITime `json:"createdAt"`
	ChannelName    *string `json:"channelName"`
	ChannelType    *string `json:"channelType"`
	APIKeyName     *string `json:"apiKeyName"`
}

// Model mirrors models.
type Model struct {
	ID                          int64   `json:"id"`
	ModelName                   string  `json:"modelName"`
	Description                 *string `json:"description"`
	Strategy                    *string `json:"strategy"`
	Enabled                     *int    `json:"enabled"`
	Hidden                      *int    `json:"hidden"`
	RelMode                     *string `json:"relMode"`
	InheritFromModelID          *int64  `json:"inheritFromModelId"`
	ImageInvalidateCount        *int    `json:"imageInvalidateCount"`
	VideoInvalidateCount        *int    `json:"videoInvalidateCount"`
	AudioInvalidateCount        *int    `json:"audioInvalidateCount"`
	ForceOverrideReasoningEffort *int   `json:"forceOverrideReasoningEffort"`
	CreatedAt                   APITime `json:"createdAt"`
	UpdatedAt                   APITime `json:"updatedAt"`
}

// RelMode constants.
const (
	RelModeSelfAdd = "self_add"
	RelModeInherit = "inherit"
)

// ModelChannelRel mirrors model_channel_rels including computed display fields.
type ModelChannelRel struct {
	ID                   int64   `json:"id"`
	ModelID              *int64  `json:"modelId"`
	ChannelModelID       *int64  `json:"channelModelId"`
	Weight               *int    `json:"weight"`
	ReasoningEffort      *string `json:"reasoningEffort"`
	SortOrder            *int    `json:"sortOrder"`
	Enabled              *int    `json:"enabled"`
	CreatedAt            APITime `json:"createdAt"`
	ChannelModelName     *string `json:"channelModelName"`
	ChannelName          *string `json:"channelName"`
	ChannelType          *string `json:"channelType"`
	ChannelID            *int64  `json:"channelId"`
	ChannelEnabled       *int    `json:"channelEnabled"`
	APIKeyAvailable      *int    `json:"apiKeyAvailable"`
	TTFTMs               *int64  `json:"ttftMs"`
	SampleCount          *int    `json:"sampleCount"`
	OutputSpeed          *float64 `json:"outputSpeed"`
	Input                *string `json:"input"`
	CircuitBroken        *int    `json:"circuitBroken"`
	CircuitBrokenScope   *string `json:"circuitBrokenScope"`
	CircuitBrokenExpireAt APITime `json:"circuitBrokenExpireAt"`
	// 最近一次熔断探测（仅熔断关联上有值）：探测时间 / HTTP 状态码 / 失败详情。
	// 探测成功后熔断记录即被删除，因此存续记录上的探测结果通常为失败信息。
	LastProbeAt           APITime `json:"circuitBrokenLastProbeAt"`
	LastProbeStatus       *int    `json:"circuitBrokenLastProbeStatus"`
	LastProbeDetail       *string `json:"circuitBrokenLastProbeDetail"`
}

// CircuitBreakerConfig mirrors circuit_breaker_configs.
type CircuitBreakerConfig struct {
	ID                   int64   `json:"id"`
	ModelID              *int64  `json:"modelId"`
	RetryCount           *int    `json:"retryCount"`
	CircuitBreakDuration *int    `json:"circuitBreakDuration"`
	CircuitBreakScope    *string `json:"circuitBreakScope"`
	Enabled              *int    `json:"enabled"`
	CreatedAt            APITime `json:"createdAt"`
	UpdatedAt            APITime `json:"updatedAt"`
	ModelName            *string `json:"modelName"`
}

// NormalizeScope converts the legacy "apikey" scope to "channel" exactly like
// the Java getter/setter pair did.
func NormalizeScope(s *string) *string {
	if s != nil && *s == "apikey" {
		v := "channel"
		return &v
	}
	return s
}

// CircuitBreakerState mirrors circuit_breaker_states.
type CircuitBreakerState struct {
	ID             int64   `json:"id"`
	ChannelID      *int64  `json:"channelId"`
	ChannelAPIKeyID *int64 `json:"channelApiKeyId"`
	ChannelModelID *int64  `json:"channelModelId"`
	IsOpen         *int    `json:"isOpen"`
	FailCount      *int    `json:"failCount"`
	OpenedAt       APITime `json:"openedAt"`
	ExpireAt       APITime `json:"expireAt"`
	CreatedAt      APITime `json:"createdAt"`
	UpdatedAt      APITime `json:"updatedAt"`
}

// APIKey mirrors api_keys.
type APIKey struct {
	ID         int64   `json:"id"`
	KeyName    string  `json:"keyName"`
	KeyValue   string  `json:"keyValue"`
	Enabled    *int    `json:"enabled"`
	ShareCode  *string `json:"shareCode"`
	Shared     *int    `json:"shared"`
	LastUsedAt APITime `json:"lastUsedAt"`
	CreatedAt  APITime `json:"createdAt"`
	UpdatedAt  APITime `json:"updatedAt"`
}

// RequestLog mirrors request_logs.
type RequestLog struct {
	ID               int64   `json:"id"`
	TraceID          string  `json:"traceId"`
	APIKeyName       *string `json:"apiKeyName"`
	GatewayAPIKeyID  *int64  `json:"gatewayApiKeyId"`
	ModelName        *string `json:"modelName"`
	ChannelModelName *string `json:"channelModelName"`
	ChannelName      *string `json:"channelName"`
	Phase            string  `json:"phase"`
	Status           *string `json:"status"`
	Message          *string `json:"message"`
	ReasoningEffort  *string `json:"reasoningEffort"`
	RetryIndex       *int    `json:"retryIndex"`
	ResponseTimeMs   *int    `json:"responseTimeMs"`
	FirstByteMs      *int    `json:"firstByteMs"`
	PromptTokens     *int    `json:"promptTokens"`
	CompletionTokens *int    `json:"completionTokens"`
	TotalTokens      *int    `json:"totalTokens"`
	RequestHeaders   *string `json:"requestHeaders"`
	RequestBody      *string `json:"requestBody"`
	CreatedAt        APITime `json:"createdAt"`
}

// AdminConfig mirrors admin_config.
type AdminConfig struct {
	ID          int64   `json:"id"`
	ConfigKey   string  `json:"configKey"`
	ConfigValue string  `json:"configValue"`
	Description *string `json:"description"`
	CreatedAt   APITime `json:"createdAt"`
	UpdatedAt   APITime `json:"updatedAt"`
}

// MultiModalRule mirrors multimodal_rules.
type MultiModalRule struct {
	ID         int64   `json:"id"`
	Pattern    string  `json:"pattern"`
	AppendType string  `json:"appendType"`
	CreatedAt  APITime `json:"createdAt"`
	UpdatedAt  APITime `json:"updatedAt"`
}

// PromptInjection mirrors prompt_injections.
type PromptInjection struct {
	ID             int64   `json:"id"`
	ModelID        *int64  `json:"modelId"`
	Name           *string `json:"name"`
	InjectRole     *string `json:"injectRole"`
	InjectPosition *string `json:"injectPosition"`
	Content        *string `json:"content"`
	Enabled        *int    `json:"enabled"`
	Priority       *int    `json:"priority"`
	CreatedAt      APITime `json:"createdAt"`
	UpdatedAt      APITime `json:"updatedAt"`
}

// Int / Int64 / Str helpers for building optional values.
func Int(v int) *int          { return &v }
func Int64(v int64) *int64    { return &v }
func Str(v string) *string    { return &v }
func F64(v float64) *float64  { return &v }
func DerefInt(p *int, def int) int {
	if p == nil {
		return def
	}
	return *p
}
func DerefStr(p *string, def string) string {
	if p == nil {
		return def
	}
	return *p
}
func DerefI64(p *int64) int64 {
	if p == nil {
		return 0
	}
	return *p
}
