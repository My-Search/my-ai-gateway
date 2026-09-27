// Request preprocessing: prompt injection, multimodal invalidation, forced
// reasoning-effort override, media-type routing skip and reroute context
// splicing. Reproduces RequestPreprocessor.
package relay

import (
	"context"
	"log/slog"
	"strings"
)

// Media invalidation replacement texts (RequestPreprocessor constants).
const (
	mediaReplaceTextImage = "图片输入已失效已被系统移除"
	mediaReplaceTextVideo = "视频输入已失效已被系统移除"
	mediaReplaceTextAudio = "音频输入已失效已被系统移除"
)

// PromptInjectionRule is the subset of prompt_injections used at runtime.
type PromptInjectionRule struct {
	Name           string
	InjectRole     string
	InjectPosition string
	Content        string
}

// PreprocessDeps are the lookups the preprocessor needs. They are injected so
// the relay package stays free of server/service imports.
type PreprocessDeps struct {
	// ModelID resolves a custom model id by name.
	ModelID func(ctx context.Context, modelName string) int64
	// PromptInjections returns the enabled rules for a model, ordered by priority.
	PromptInjections func(ctx context.Context, modelID int64) []PromptInjectionRule
	// ModelFlags returns the media invalidation counts and the forced-override flag.
	ModelFlags func(ctx context.Context, modelID int64) (imageN, videoN, audioN int, forceOverride bool)
}

// ApplyPromptInjections mirrors RequestPreprocessor.applyPromptInjections.
func ApplyPromptInjections(ctx context.Context, deps PreprocessDeps, req *InternalRequest) {
	if deps.ModelID == nil || deps.PromptInjections == nil {
		return
	}
	modelID := deps.ModelID(ctx, req.Model)
	if modelID == 0 {
		return
	}
	rules := deps.PromptInjections(ctx, modelID)
	if len(rules) == 0 {
		return
	}
	var prependMessages, appendMessages []InternalMessage
	var replaceSystemMsg *InternalMessage
	for _, rule := range rules {
		injectMsg := InternalMessage{Role: rule.InjectRole, Content: rule.Content}
		slog.Info("应用 Prompt 注入规则", "modelId", modelID, "ruleName", rule.Name,
			"role", rule.InjectRole, "position", rule.InjectPosition)
		switch rule.InjectPosition {
		case "prepend":
			prependMessages = append(prependMessages, injectMsg)
		case "append":
			appendMessages = append(appendMessages, injectMsg)
		case "replace_system":
			msg := injectMsg
			replaceSystemMsg = &msg
		default:
			slog.Warn("未知的注入位置", "position", rule.InjectPosition)
		}
	}
	if replaceSystemMsg != nil {
		found := false
		for i := range req.Messages {
			if req.Messages[i].Role == "system" {
				req.Messages[i] = *replaceSystemMsg
				found = true
				break
			}
		}
		if !found {
			prependMessages = append(prependMessages, *replaceSystemMsg)
		}
	}
	newMessages := make([]InternalMessage, 0, len(prependMessages)+len(req.Messages)+len(appendMessages))
	newMessages = append(newMessages, prependMessages...)
	newMessages = append(newMessages, req.Messages...)
	newMessages = append(newMessages, appendMessages...)
	req.Messages = newMessages
}

// PreprocessMediaInvalidation mirrors RequestPreprocessor.preprocessMediaInvalidation.
func PreprocessMediaInvalidation(ctx context.Context, deps PreprocessDeps, req *InternalRequest) {
	if len(req.Messages) == 0 || deps.ModelID == nil || deps.ModelFlags == nil {
		return
	}
	modelID := deps.ModelID(ctx, req.Model)
	if modelID == 0 {
		return
	}
	imageN, videoN, audioN, _ := deps.ModelFlags(ctx, modelID)
	if imageN > 0 {
		preprocessMediaInvalidationForType(req, "image", imageN, mediaReplaceTextImage)
	}
	if videoN > 0 {
		preprocessMediaInvalidationForType(req, "video", videoN, mediaReplaceTextVideo)
	}
	if audioN > 0 {
		preprocessMediaInvalidationForType(req, "audio", audioN, mediaReplaceTextAudio)
	}
}

func preprocessMediaInvalidationForType(req *InternalRequest, typePrefix string, invalidateCount int, replaceText string) {
	lastMediaUserIdx := -1
	userCount := 0
	for i := range req.Messages {
		if req.Messages[i].Role != "user" {
			continue
		}
		if hasMediaType(req.Messages[i], typePrefix) {
			lastMediaUserIdx = userCount
		}
		userCount++
	}
	if lastMediaUserIdx < 0 {
		return
	}
	userMsgsAfter := userCount - 1 - lastMediaUserIdx
	if userMsgsAfter < invalidateCount {
		return
	}
	slog.Info("多模态失效触发", "mediaType", typePrefix, "invalidateCount", invalidateCount,
		"userMsgsAfter", userMsgsAfter, "model", req.Model)
	for i := range req.Messages {
		replaceMediaParts(&req.Messages[i], typePrefix, replaceText)
	}
	req.DetectedMediaTypes = nil
}

func hasMediaType(msg InternalMessage, typePrefix string) bool {
	for _, part := range msg.ContentParts {
		if strings.HasPrefix(blockType(part), typePrefix) {
			return true
		}
	}
	return false
}

func replaceMediaParts(msg *InternalMessage, typePrefix string, replaceText string) {
	if msg.ContentParts == nil {
		return
	}
	newParts := make([]map[string]any, 0, len(msg.ContentParts))
	replaced := false
	for _, part := range msg.ContentParts {
		if strings.HasPrefix(blockType(part), typePrefix) {
			if !replaced {
				newParts = append(newParts, map[string]any{"type": "text", "text": replaceText})
				replaced = true
			}
			continue
		}
		newParts = append(newParts, part)
	}
	msg.ContentParts = newParts
}

// ApplyReasoningEffortOverride mirrors RequestPreprocessor.applyReasoningEffortOverride.
func ApplyReasoningEffortOverride(ctx context.Context, deps PreprocessDeps, req *InternalRequest) {
	if req.ReasoningEffort == nil || *req.ReasoningEffort == "" {
		return
	}
	if deps.ModelID == nil || deps.ModelFlags == nil {
		return
	}
	modelID := deps.ModelID(ctx, req.Model)
	if modelID == 0 {
		return
	}
	_, _, _, forceOverride := deps.ModelFlags(ctx, modelID)
	if forceOverride {
		slog.Info("强制覆盖思考强度: 清除客户端请求的 reasoning_effort", "value", *req.ReasoningEffort, "model", req.Model)
		req.ReasoningEffort = nil
	}
}

// DetectRequestMediaTypes mirrors RequestPreprocessor.detectRequestMediaTypes.
func DetectRequestMediaTypes(req *InternalRequest) []string {
	if req.DetectedMediaTypes != nil {
		return req.DetectedMediaTypes
	}
	seen := map[string]bool{}
	var types []string
	for _, msg := range req.Messages {
		for _, part := range msg.ContentParts {
			t := blockType(part)
			var kind string
			switch {
			case strings.HasPrefix(t, "image"):
				kind = "image"
			case strings.HasPrefix(t, "video"):
				kind = "video"
			case strings.HasPrefix(t, "audio"):
				kind = "audio"
			}
			if kind != "" && !seen[kind] {
				seen[kind] = true
				types = append(types, kind)
			}
		}
	}
	if types == nil {
		types = []string{}
	}
	req.DetectedMediaTypes = types
	return types
}

// SupportsMediaType mirrors RequestPreprocessor.supportsMediaType.
func SupportsMediaType(channelModelInput, mediaType string) bool {
	return strings.Contains(channelModelInput, mediaType)
}

// UnsupportedMediaTypes returns the request media types the candidate cannot handle.
func UnsupportedMediaTypes(req *InternalRequest, channelModelInput string) []string {
	var unsupported []string
	for _, t := range DetectRequestMediaTypes(req) {
		if !SupportsMediaType(channelModelInput, t) {
			unsupported = append(unsupported, t)
		}
	}
	return unsupported
}

// ContextToleranceTokens is the slack added to a candidate's context window
// before a request is rejected: it is skipped only when the estimated request
// size exceeds window + tolerance. Mirrors channelload.ContextToleranceTokens;
// duplicated here so the relay package stays free of channelload imports.
const ContextToleranceTokens = 5000

// Non-text content parts are counted as a flat token allowance: the gateway has
// no per-provider image/video tokenizer, and a fixed, generous estimate is safer
// than ignoring media entirely (which would under-count and route an oversized
// request to a small model).
const (
	mediaPartTokensImage = 1000
	mediaPartTokensOther = 2000
)

// EstimateRequestTokens approximates the request's context size in tokens using
// the same coarse heuristic as the usage fallback: CJK runes count as one token
// each, other characters as one token per four. max_tokens is added when the
// client set it explicitly, because that output budget must fit the same window.
func EstimateRequestTokens(req *InternalRequest) int64 {
	if req == nil {
		return 0
	}
	var tokens int64
	tokens += estimateTextTokens(req.SystemPrompt)
	for _, msg := range req.Messages {
		tokens += estimateTextTokens(msg.Content)
		for _, part := range msg.ContentParts {
			t := blockType(part)
			switch {
			case strings.HasPrefix(t, "image"):
				tokens += mediaPartTokensImage
			case strings.HasPrefix(t, "video"), strings.HasPrefix(t, "audio"):
				tokens += mediaPartTokensOther
			default:
				if s, ok := part["text"].(string); ok {
					tokens += estimateTextTokens(s)
				}
			}
		}
	}
	if req.MaxTokens != nil && *req.MaxTokens > 0 {
		tokens += int64(*req.MaxTokens)
	}
	return tokens
}

func estimateTextTokens(s string) int64 {
	if s == "" {
		return 0
	}
	var cjk, other int64
	for _, r := range s {
		if isCJK(r) {
			cjk++
		} else {
			other++
		}
	}
	return cjk + other/4
}

func isCJK(r rune) bool {
	switch {
	case r >= 0x3400 && r <= 0x4dbf: // CJK Unified Ideographs Extension A
		return true
	case r >= 0x4e00 && r <= 0x9fff: // CJK Unified Ideographs
		return true
	case r >= 0xf900 && r <= 0xfaff: // CJK Compatibility Ideographs
		return true
	}
	return false
}

// ExceedsContextLimit reports whether the request is too large for a candidate
// whose context window is contextLength. A zero/negative window means unknown,
// which never skips. The request must exceed window + tolerance to be rejected.
func ExceedsContextLimit(req *InternalRequest, contextLength int64) (bool, int64) {
	if contextLength <= 0 {
		return false, 0
	}
	estimated := EstimateRequestTokens(req)
	return estimated > contextLength+ContextToleranceTokens, estimated
}

// BuildRequestWithContext mirrors RequestPreprocessor.buildRequestWithContext.
func BuildRequestWithContext(originalReq *InternalRequest, accumulatedContent string) *InternalRequest {
	contextReq := &InternalRequest{
		Model:              originalReq.Model,
		Stream:             originalReq.Stream,
		MaxTokens:          originalReq.MaxTokens,
		Temperature:        originalReq.Temperature,
		TopP:               originalReq.TopP,
		Stop:               originalReq.Stop,
		Tools:              originalReq.Tools,
		ToolChoice:         originalReq.ToolChoice,
		SystemPrompt:       originalReq.SystemPrompt,
		StreamOptions:      originalReq.StreamOptions,
		ExtraParams:        originalReq.ExtraParams,
		OriginalRequestRaw: originalReq.OriginalRequestRaw,
		ClientAPIFormat:    originalReq.ClientAPIFormat,
		ReasoningEffort:    originalReq.ReasoningEffort,
		ContextRetry:       true,
	}
	newMessages := make([]InternalMessage, 0, len(originalReq.Messages)+1)
	newMessages = append(newMessages, originalReq.Messages...)
	newMessages = append(newMessages, InternalMessage{Role: "assistant", Content: accumulatedContent})
	contextReq.Messages = newMessages
	return contextReq
}
