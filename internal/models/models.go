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
	// ContextLength 是模型上下文窗口（token 数），来自上下文规则 / models.dev 目录；
	// nil 表示未知（不做上下文过滤）。
	ContextLength  *int    `json:"contextLength"`
	CreatedAt      APITime `json:"createdAt"`
	ChannelName    *string `json:"channelName"`
	ChannelType    *string `json:"channelType"`
	APIKeyName     *string `json:"apiKeyName"`
	// 关联状态：是否被至少一个入口模型关联（仅渠道模型列表接口计算返回，其余接口省略）
	Linked         *bool   `json:"linked,omitempty"`
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
	// ContextLength 是渠道模型的上下文窗口（tokens），来自模型配置规则 /
	// models.dev 数据；未知时为 null，表示不参与上下文过滤。
	ContextLength       *int64  `json:"contextLength"`
	CircuitBroken        *int    `json:"circuitBroken"`
	CircuitBrokenScope   *string `json:"circuitBrokenScope"`
	CircuitBrokenExpireAt APITime `json:"circuitBrokenExpireAt"`
	// 最近一次熔断探测（仅熔断关联上有值）：探测时间 / HTTP 状态码 / 失败详情。
	// 探测成功后熔断记录即被删除，因此存续记录上的探测结果通常为失败信息。
	LastProbeAt           APITime `json:"circuitBrokenLastProbeAt"`
	LastProbeStatus       *int    `json:"circuitBrokenLastProbeStatus"`
	LastProbeDetail       *string `json:"circuitBrokenLastProbeDetail"`
	// CircuitBrokenProtocols 按 API Key 列出各自熔断时命中的入站协议
	// （不同 Key 可能协议不同）；未熔断或无数据时为 null。
	CircuitBrokenProtocols []APIKeyProtocol `json:"circuitBrokenProtocols"`
}

// APIKeyProtocol pairs a channel API key with the inbound protocol its breaker
// tripped on (openai-chat / anthropic-messages / openai-responses / embeddings).
type APIKeyProtocol struct {
	KeyID    int64  `json:"keyId"`
	KeyName  string `json:"keyName"`
	Protocol string `json:"protocol"`
}

// ModelGroup mirrors model_groups.入口模型可以关联一个小组，小组内部再按自己的
// 路由方式（strategy）在成员渠道模型之间选择，从而把同一能力的请求分散到多个渠道。
type ModelGroup struct {
	ID          int64   `json:"id"`
	Name        string  `json:"name"`
	Description *string `json:"description"`
	// Strategy 是组内路由方式：failover / random（按权重随机）/ round_robin（按权重轮转）。
	Strategy *string `json:"strategy"`
	// Sticky=1 时按请求消息前缀的一致性哈希固定组内成员，命中上游 prompt cache。
	Sticky    *int    `json:"sticky"`
	Enabled   *int    `json:"enabled"`
	CreatedAt APITime `json:"createdAt"`
	UpdatedAt APITime `json:"updatedAt"`
	// MemberCount 仅列表接口计算返回。
	MemberCount *int `json:"memberCount,omitempty"`
	// 以下摘要字段仅列表接口计算返回，口径与入口模型关联页的小组行一致
	// （groupMemberSummaryOf，按可路由成员统计；性能样本为 24h 日志聚合）。
	AvailableCount *int     `json:"availableCount,omitempty"`
	BrokenCount    *int     `json:"brokenCount,omitempty"`
	TTFTMs         *int64   `json:"ttftMs,omitempty"`
	SampleCount    *int     `json:"sampleCount,omitempty"`
	OutputSpeed    *float64 `json:"outputSpeed,omitempty"`
}

// Group strategy constants.
const (
	GroupStrategyFailover   = "failover"
	GroupStrategyRandom     = "random"
	GroupStrategyRoundRobin = "round_robin"
)

// ModelGroupMember mirrors model_group_members plus the computed channel/model
// display fields the admin UI renders.
type ModelGroupMember struct {
	ID             int64   `json:"id"`
	GroupID        *int64  `json:"groupId"`
	ChannelModelID *int64  `json:"channelModelId"`
	// Weight 是组内路由权重：strategy=random 的加权抽样、strategy=round_robin 的
	// 加权轮转起点，以及 sticky 的哈希环虚拟节点数都用它；<=0 视为 1。
	Weight *int `json:"weight"`
	ReasoningEffort *string `json:"reasoningEffort"`
	SortOrder       *int    `json:"sortOrder"`
	Enabled         *int    `json:"enabled"`
	CreatedAt       APITime `json:"createdAt"`
	ChannelModelName *string `json:"channelModelName"`
	ChannelName      *string `json:"channelName"`
	ChannelType      *string `json:"channelType"`
	ChannelID        *int64  `json:"channelId"`
	ChannelEnabled   *int    `json:"channelEnabled"`
	APIKeyAvailable  *int    `json:"apiKeyAvailable"`
	Input            *string `json:"input"`
	ContextLength    *int64  `json:"contextLength"`
	// TTFTMs / OutputSpeed 是该成员 24h 性能样本的均值，口径与入口模型关联行
	// （computeRelStats）一致；无样本时为 null。SampleCount 是参与平均的样本数。
	TTFTMs      *int64   `json:"ttftMs"`
	SampleCount *int     `json:"sampleCount"`
	OutputSpeed *float64 `json:"outputSpeed"`
	// CircuitBroken 等熔断展示字段与入口模型关联一致，按 (渠道, 渠道模型, Key) 判定。
	CircuitBroken          *int              `json:"circuitBroken"`
	CircuitBrokenScope     *string           `json:"circuitBrokenScope"`
	CircuitBrokenExpireAt  APITime           `json:"circuitBrokenExpireAt"`
	// 最近一次熔断探测（同入口模型关联行）：探测时间 / HTTP 状态码 / 失败详情。
	LastProbeAt            APITime           `json:"circuitBrokenLastProbeAt"`
	LastProbeStatus        *int              `json:"circuitBrokenLastProbeStatus"`
	LastProbeDetail        *string           `json:"circuitBrokenLastProbeDetail"`
	CircuitBrokenProtocols []APIKeyProtocol  `json:"circuitBrokenProtocols"`
}

// ModelGroupRel mirrors model_group_rels：入口模型 -> 小组的关联，与
// model_channel_rels 共用同一 sort_order 序号空间，路由时合并成一条候选队列。
type ModelGroupRel struct {
	ID        int64   `json:"id"`
	ModelID   *int64  `json:"modelId"`
	GroupID   *int64  `json:"groupId"`
	SortOrder *int    `json:"sortOrder"`
	// ReasoningEffort 是该入口模型关联这个小组时的默认思考强度：仅当组内成员
	// 未单独配置思考强度时对其生效（成员设置优先）。NULL 表示不设置。
	ReasoningEffort *string `json:"reasoningEffort"`
	Enabled         *int    `json:"enabled"`
	CreatedAt       APITime `json:"createdAt"`
	// 展示字段
	GroupName        *string `json:"groupName"`
	GroupStrategy    *string `json:"groupStrategy"`
	GroupSticky      *int    `json:"groupSticky"`
	GroupEnabled     *int    `json:"groupEnabled"`
	GroupDescription *string `json:"groupDescription"`
	MemberCount      *int    `json:"memberCount"`
	AvailableCount   *int    `json:"availableCount"`
	// 成员聚合摘要（按可路由成员统计：成员启用 + 渠道模型启用 + 渠道启用 + 有可用 Key）。
	// Input 是可路由成员输入模态并集（text 优先、去重）；MaxContextLength 是其中的
	// 最大正值上下文，全未知时为 null（前端显示 -）。
	Input            *string `json:"input"`
	MaxContextLength *int64  `json:"maxContextLength"`
	// TTFTMs / OutputSpeed 是可路由成员 24h 性能样本的总体平均（与渠道模型行同
	// 口径），无样本时为 null。SampleCount 是参与平均的样本数。
	TTFTMs      *int64   `json:"ttftMs"`
	SampleCount *int     `json:"sampleCount"`
	OutputSpeed *float64 `json:"outputSpeed"`
	// BrokenCount 是可路由成员中处于熔断状态的个数（与 CircuitBroken 的判定同源），
	// 供入口模型关联列表的小组行展示「熔断成员数/成员总数」。
	BrokenCount *int `json:"brokenCount"`
	// CircuitBroken：1 = 组内可路由成员全部熔断（此时才显示「熔断中」）；
	// 部分熔断为 0（仍有可用候选，不告警）。
	CircuitBroken *int `json:"circuitBroken"`
	// CircuitBrokenScope：全部熔断时聚合出的熔断级别——"model" | "channel" | "both"。
	// 成员级别不一致时取包含关系最广的一档（both > channel > model），与渠道模型行
	// 的展示字段同义，供前端渲染「熔断中（模型级 n/m）」。
	CircuitBrokenScope *string `json:"circuitBrokenScope"`
	// MemberModelNames 是可路由成员的上游模型名（去重、按成员顺序），
	// 供入口模型关联列表的小组行在「模型」列逐行展示。
	MemberModelNames []string `json:"memberModelNames"`
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
	// RouteSource 标注本次路由的来源：sticky=小组粘性哈希命中的成员，
	// sticky_fallback=命中成员失败后由组内其他成员承接；非粘性为 NULL。
	RouteSource *string `json:"routeSource"`
	CreatedAt   APITime `json:"createdAt"`
}

// RouteSource 取值（写入 request_logs.route_source）。
const (
	// RouteSourceSticky 该候选是小组粘性哈希命中的成员。
	RouteSourceSticky = "sticky"
	// RouteSourceStickyFallback 粘性命中成员失败后，实际承接的组内其他成员。
	RouteSourceStickyFallback = "sticky_fallback"
)

// AdminConfig mirrors admin_config.
type AdminConfig struct {
	ID          int64   `json:"id"`
	ConfigKey   string  `json:"configKey"`
	ConfigValue string  `json:"configValue"`
	Description *string `json:"description"`
	CreatedAt   APITime `json:"createdAt"`
	UpdatedAt   APITime `json:"updatedAt"`
}

// ModelConfigRule mirrors model_config_rules: one regex rule that assigns a
// channel model its input modalities and/or context window. Both value fields
// are optional — an empty append_type or a zero context_length means "do not
// override this dimension", leaving whatever the models.dev baseline provides.
type ModelConfigRule struct {
	ID            int64   `json:"id"`
	Pattern       string  `json:"pattern"`
	AppendType    string  `json:"appendType"`
	ContextLength int64   `json:"contextLength"`
	CreatedAt     APITime `json:"createdAt"`
	UpdatedAt     APITime `json:"updatedAt"`
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
