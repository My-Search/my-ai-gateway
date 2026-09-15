// Protocol translation between the OpenAI Chat Completions API and the
// Anthropic Messages API.
//
// This file reproduces MessageTransformer, OpenAiToAnthropicTranslator and
// AnthropicToOpenAiTranslator. It lives in package relay (rather than a
// subpackage) because the routing loop calls it directly.
package relay

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"time"
)

// ---------------------------------------------------------------------------
// Request parsing (MessageTransformer.parseOpenAiRequest / parseAnthropicRequest)
// ---------------------------------------------------------------------------

// ParseRequest parses the client request body into the internal representation.
func ParseRequest(requestBody, clientFormat string) (*InternalRequest, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(requestBody), &raw); err != nil {
		return nil, err
	}
	if clientFormat == ProtoAnthropic {
		return parseAnthropicRequest(raw), nil
	}
	return parseOpenAIRequest(raw), nil
}

func rawString(raw map[string]json.RawMessage, key string) string {
	if v, ok := raw[key]; ok {
		var s string
		if json.Unmarshal(v, &s) == nil {
			return s
		}
	}
	return ""
}

func rawBool(raw map[string]json.RawMessage, key string) bool {
	if v, ok := raw[key]; ok {
		var b bool
		if json.Unmarshal(v, &b) == nil {
			return b
		}
	}
	return false
}

func rawInt(raw map[string]json.RawMessage, key string) *int {
	if v, ok := raw[key]; ok {
		var n int
		if json.Unmarshal(v, &n) == nil {
			return &n
		}
		var f float64
		if json.Unmarshal(v, &f) == nil {
			n = int(f)
			return &n
		}
	}
	return nil
}

func rawFloat(raw map[string]json.RawMessage, key string) *float64 {
	if v, ok := raw[key]; ok {
		var f float64
		if json.Unmarshal(v, &f) == nil {
			return &f
		}
	}
	return nil
}

func rawNode(raw map[string]json.RawMessage, key string) any {
	v, ok := raw[key]
	if !ok {
		return nil
	}
	var out any
	if json.Unmarshal(v, &out) == nil {
		return out
	}
	return nil
}

func parseOpenAIRequest(raw map[string]json.RawMessage) *InternalRequest {
	req := &InternalRequest{
		ClientAPIFormat: ProtoOpenAI,
		Model:           rawString(raw, "model"),
		Stream:          rawBool(raw, "stream"),
		Temperature:     rawFloat(raw, "temperature"),
		TopP:            rawFloat(raw, "top_p"),
		MaxTokens:       rawInt(raw, "max_tokens"),
		ToolChoice:      rawField(raw, "tool_choice"),
	}
	if req.MaxTokens == nil {
		req.MaxTokens = rawInt(raw, "max_completion_tokens")
	}
	if v, ok := raw["stop"]; ok {
		var s string
		if json.Unmarshal(v, &s) == nil {
			req.Stop = []string{s}
		} else {
			var arr []string
			if json.Unmarshal(v, &arr) == nil {
				req.Stop = arr
			}
		}
	}
	if v, ok := raw["messages"]; ok {
		var msgs []map[string]json.RawMessage
		if json.Unmarshal(v, &msgs) == nil {
			for _, m := range msgs {
				im := InternalMessage{Role: rawString(m, "role")}
				if c, ok := m["content"]; ok {
					var s string
					if json.Unmarshal(c, &s) == nil {
						im.Content = s
					} else {
						var parts []map[string]any
						if json.Unmarshal(c, &parts) == nil {
							im.ContentParts = parts
						}
					}
				}
				if tc, ok := m["tool_calls"]; ok {
					var calls []map[string]any
					if json.Unmarshal(tc, &calls) == nil {
						im.ToolCalls = calls
					}
				}
				im.ToolCallID = rawString(m, "tool_call_id")
				if _, ok := m["name"]; ok {
					im.Name = rawString(m, "name")
				}
				// DeepSeek thinking mode：assistant 角色的 reasoning_content 必须传回
				if im.Role == "assistant" {
					if rc, ok := m["reasoning_content"]; ok {
						var s string
						if json.Unmarshal(rc, &s) == nil {
							im.ReasoningContent = &s
						}
					}
				}
				req.Messages = append(req.Messages, im)
			}
		}
	}
	if v, ok := raw["tools"]; ok {
		var tools []map[string]any
		if json.Unmarshal(v, &tools) == nil {
			req.Tools = tools
		}
	}
	if v, ok := raw["reasoning_effort"]; ok {
		var s string
		if json.Unmarshal(v, &s) == nil {
			req.ReasoningEffort = &s
		}
	}
	if v, ok := raw["stream_options"]; ok {
		req.StreamOptions = rawField(raw, "stream_options")
		_ = v
	}
	return req
}

func rawField(raw map[string]json.RawMessage, key string) any {
	if v, ok := raw[key]; ok {
		var out any
		if json.Unmarshal(v, &out) == nil {
			return out
		}
	}
	return nil
}

func parseAnthropicRequest(raw map[string]json.RawMessage) *InternalRequest {
	req := &InternalRequest{
		ClientAPIFormat: ProtoAnthropic,
		Model:           rawString(raw, "model"),
		Stream:          rawBool(raw, "stream"),
		MaxTokens:       rawInt(raw, "max_tokens"),
		Temperature:     rawFloat(raw, "temperature"),
		TopP:            rawFloat(raw, "top_p"),
	}
	if v, ok := raw["system"]; ok {
		var s string
		if json.Unmarshal(v, &s) == nil {
			req.SystemPrompt = s
		} else {
			var blocks []map[string]any
			if json.Unmarshal(v, &blocks) == nil {
				var sb strings.Builder
				for _, b := range blocks {
					if t, ok := b["text"].(string); ok {
						sb.WriteString(t)
					}
				}
				req.SystemPrompt = sb.String()
			}
		}
	}
	if v, ok := raw["stop_sequences"]; ok {
		var arr []string
		if json.Unmarshal(v, &arr) == nil {
			req.Stop = arr
		}
	}
	if v, ok := raw["messages"]; ok {
		var msgs []map[string]json.RawMessage
		if json.Unmarshal(v, &msgs) == nil {
			for _, m := range msgs {
				im := InternalMessage{Role: rawString(m, "role")}
				var contentParts []map[string]any
				if c, ok := m["content"]; ok {
					var s string
					if json.Unmarshal(c, &s) == nil {
						im.Content = s
					} else {
						var parts []map[string]any
						if json.Unmarshal(c, &parts) == nil {
							im.ContentParts = parts
							contentParts = parts
						}
					}
				}
				// tool_use 块 → OpenAI tool_calls
				if contentParts != nil {
					var toolCalls []map[string]any
					for _, block := range contentParts {
						if blockType(block) == "tool_use" {
							args := "{}"
							if input, ok := block["input"]; ok {
								if b, err := json.Marshal(input); err == nil {
									args = string(b)
								}
							}
							toolCalls = append(toolCalls, map[string]any{
								"id":   strVal(block["id"]),
								"type": "function",
								"function": map[string]any{
									"name":      strVal(block["name"]),
									"arguments": args,
								},
							})
						}
					}
					if len(toolCalls) > 0 {
						im.ToolCalls = toolCalls
					}
				}
				// tool_result 块 → role=tool
				if im.Role == "tool" || hasToolResultBlock(contentParts) {
					for _, block := range contentParts {
						if blockType(block) == "tool_result" {
							im.ToolCallID = strVal(block["tool_use_id"])
							if rc, ok := block["content"]; ok {
								if s, ok := rc.(string); ok {
									im.Content = s
								} else if b, err := json.Marshal(rc); err == nil {
									im.Content = string(b)
								}
							}
							im.Role = "tool"
							break
						}
					}
				}
				req.Messages = append(req.Messages, im)
			}
		}
	}
	if v, ok := raw["reasoning_effort"]; ok {
		var s string
		if json.Unmarshal(v, &s) == nil {
			req.ReasoningEffort = &s
		}
	}
	if v, ok := raw["tools"]; ok {
		var tools []map[string]any
		if json.Unmarshal(v, &tools) == nil {
			var out []map[string]any
			for _, tool := range tools {
				openAiTool := map[string]any{"type": "function"}
				fn := map[string]any{"name": strVal(tool["name"])}
				if d, ok := tool["description"]; ok {
					fn["description"] = d
				}
				if s, ok := tool["input_schema"]; ok {
					fn["parameters"] = s
				}
				openAiTool["function"] = fn
				out = append(out, openAiTool)
			}
			req.Tools = out
		}
	}
	if v, ok := raw["tool_choice"]; ok {
		var out any
		if json.Unmarshal(v, &out) == nil {
			req.ToolChoice = out
		}
	}
	return req
}

func blockType(block map[string]any) string {
	if t, ok := block["type"]; ok {
		return strVal(t)
	}
	return ""
}

func strVal(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case nil:
		return ""
	default:
		b, _ := json.Marshal(x)
		return string(b)
	}
}

func hasToolResultBlock(parts []map[string]any) bool {
	for _, p := range parts {
		if blockType(p) == "tool_result" {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------------------
// Request building (MessageTransformer.buildOpenAiRequest / buildAnthropicRequest)
// ---------------------------------------------------------------------------

// BuildProviderRequest renders the upstream request body for the target provider.
func BuildProviderRequest(req *InternalRequest, provider string) string {
	if provider == ProtoAnthropic {
		return buildAnthropicRequest(req)
	}
	return buildOpenAIRequest(req)
}

func buildOpenAIRequest(req *InternalRequest) string {
	root := map[string]any{"model": req.Model}
	if req.ReasoningEffort != nil && *req.ReasoningEffort != "" {
		root["reasoning_effort"] = *req.ReasoningEffort
	}

	messages := make([]any, 0, len(req.Messages)+1)
	if req.SystemPrompt != "" {
		messages = append(messages, map[string]any{"role": "system", "content": req.SystemPrompt})
	}
	for _, msg := range req.Messages {
		msgNode := map[string]any{"role": msg.Role}
		if msg.Content != "" {
			msgNode["content"] = msg.Content
		} else if msg.ContentParts != nil {
			contentArray := make([]any, 0, len(msg.ContentParts))
			for _, part := range msg.ContentParts {
				typ := blockType(part)
				// tool_use / tool_result / thinking 是 Anthropic 专有内容块，不能透传给 OpenAI
				if typ == "tool_use" || typ == "tool_result" || typ == "thinking" {
					continue
				}
				switch typ {
				case "text":
					contentArray = append(contentArray, map[string]any{"type": "text", "text": part["text"]})
				case "image_url":
					contentArray = append(contentArray, map[string]any{"type": "image_url", "image_url": part["image_url"]})
				case "image":
					mediaType := "image/jpeg"
					data := ""
					if src, ok := part["source"].(map[string]any); ok {
						if mt, ok := src["media_type"].(string); ok && mt != "" {
							mediaType = mt
						}
						data = strVal(src["data"])
					}
					contentArray = append(contentArray, map[string]any{
						"type":      "image_url",
						"image_url": map[string]any{"url": "data:" + mediaType + ";base64," + data},
					})
				default:
					slog.Warn("buildOpenAiRequest 遇到未识别的 content part", "type", typ)
					contentArray = append(contentArray, part)
				}
			}
			// 过滤后可能为空：assistant 消息仅含 tool_calls 时不能保留空数组
			if len(contentArray) > 0 {
				msgNode["content"] = contentArray
			}
		}
		if len(msg.ToolCalls) > 0 {
			msgNode["tool_calls"] = msg.ToolCalls
		}
		if msg.ToolCallID != "" {
			msgNode["tool_call_id"] = msg.ToolCallID
		}
		if msg.Name != "" {
			msgNode["name"] = msg.Name
		}
		applyDeepSeekReasoningContentPatch(msgNode, msg, req)
		messages = append(messages, msgNode)
	}
	root["messages"] = messages

	if req.Temperature != nil {
		root["temperature"] = *req.Temperature
	}
	if req.TopP != nil {
		root["top_p"] = *req.TopP
	}
	if req.MaxTokens != nil {
		root["max_tokens"] = *req.MaxTokens
	}
	if req.Stream {
		root["stream"] = true
		root["stream_options"] = map[string]any{"include_usage": true}
	}
	if len(req.Stop) == 1 {
		root["stop"] = req.Stop[0]
	} else if len(req.Stop) > 1 {
		root["stop"] = req.Stop
	}
	if len(req.Tools) > 0 {
		toolsNode := make([]any, 0, len(req.Tools))
		for _, tool := range req.Tools {
			if strVal(tool["type"]) == "function" {
				patchToolForCompatibility(tool)
				toolsNode = append(toolsNode, tool)
			} else {
				toolNode := map[string]any{"type": "function"}
				fn := map[string]any{"name": strVal(tool["name"])}
				if d, ok := tool["description"]; ok {
					fn["description"] = d
				}
				if s, ok := tool["input_schema"]; ok {
					fn["parameters"] = s
				}
				toolNode["function"] = fn
				toolsNode = append(toolsNode, toolNode)
			}
		}
		root["tools"] = toolsNode
	}
	if req.ToolChoice != nil {
		root["tool_choice"] = req.ToolChoice
	}
	if req.ExtraParams != nil {
		for k, v := range req.ExtraParams {
			root[k] = v
		}
	}
	out, err := json.Marshal(root)
	if err != nil {
		return "{}"
	}
	return string(out)
}

func patchToolForCompatibility(tool map[string]any) {
	fn, ok := tool["function"].(map[string]any)
	if !ok {
		return
	}
	if _, has := fn["name"]; !has {
		if name, ok := tool["name"]; ok {
			fn["name"] = name
		}
	}
}

func buildAnthropicRequest(req *InternalRequest) string {
	root := map[string]any{"model": req.Model}
	if req.MaxTokens != nil {
		root["max_tokens"] = *req.MaxTokens
	} else {
		root["max_tokens"] = 4096
	}
	if req.SystemPrompt != "" {
		root["system"] = req.SystemPrompt
	}

	messages := make([]any, 0, len(req.Messages))
	for _, msg := range req.Messages {
		role := msg.Role
		if role == "system" {
			if req.SystemPrompt == "" {
				root["system"] = msg.Content
			}
			continue
		}
		msgNode := map[string]any{}
		if role == "tool" {
			msgNode["role"] = "user"
			if len(msg.ContentParts) > 0 {
				// Anthropic→Anthropic：保留原始 tool_result 块，避免多 tool_result 丢失
				msgNode["content"] = msg.ContentParts
			} else {
				content := msg.Content
				msgNode["content"] = []any{map[string]any{
					"type": "tool_result", "tool_use_id": msg.ToolCallID, "content": content,
				}}
			}
			messages = append(messages, msgNode)
			continue
		}
		msgNode["role"] = role
		hasToolCalls := len(msg.ToolCalls) > 0
		if msg.Content != "" && !hasToolCalls {
			msgNode["content"] = msg.Content
		} else if msg.ContentParts != nil && !hasToolCalls {
			contentArray := make([]any, 0, len(msg.ContentParts))
			for _, part := range msg.ContentParts {
				switch blockType(part) {
				case "text":
					contentArray = append(contentArray, map[string]any{"type": "text", "text": part["text"]})
				case "image_url":
					url := ""
					if iu, ok := part["image_url"].(map[string]any); ok {
						url = strVal(iu["url"])
					}
					source := map[string]any{"type": "base64"}
					if strings.HasPrefix(url, "data:") {
						segs := strings.SplitN(url, ";base64,", 2)
						source["media_type"] = strings.Replace(segs[0], "data:", "", 1)
						if len(segs) > 1 {
							source["data"] = segs[1]
						} else {
							source["data"] = ""
						}
					} else {
						source["media_type"] = "image/jpeg"
						source["data"] = url
					}
					contentArray = append(contentArray, map[string]any{"type": "image", "source": source})
				default:
					contentArray = append(contentArray, part)
				}
			}
			msgNode["content"] = contentArray
		}
		if role == "assistant" && hasToolCalls {
			contentArr := make([]any, 0, len(msg.ToolCalls)+1)
			if msg.ContentParts != nil {
				for _, part := range msg.ContentParts {
					if blockType(part) == "text" {
						contentArr = append(contentArr, map[string]any{"type": "text", "text": part["text"]})
					}
				}
			} else if msg.Content != "" {
				contentArr = append(contentArr, map[string]any{"type": "text", "text": msg.Content})
			}
			for _, tc := range msg.ToolCalls {
				toolUse := map[string]any{"type": "tool_use", "id": strVal(tc["id"])}
				if fn, ok := tc["function"].(map[string]any); ok {
					toolUse["name"] = strVal(fn["name"])
					args := strVal(fn["arguments"])
					var input any
					if json.Unmarshal([]byte(args), &input) == nil {
						toolUse["input"] = input
					} else {
						toolUse["input"] = map[string]any{}
					}
				}
				contentArr = append(contentArr, toolUse)
			}
			msgNode["content"] = contentArr
		}
		if role != "assistant" && hasToolCalls && msg.Content == "" {
			msgNode["content"] = msg.ContentParts
		}
		messages = append(messages, msgNode)
	}
	root["messages"] = messages

	if req.Temperature != nil {
		root["temperature"] = *req.Temperature
	}
	if req.TopP != nil {
		root["top_p"] = *req.TopP
	}
	if req.Stream {
		root["stream"] = true
	}
	if len(req.Stop) > 0 {
		root["stop_sequences"] = req.Stop
	}
	if len(req.Tools) > 0 {
		toolsNode := make([]any, 0, len(req.Tools))
		for _, tool := range req.Tools {
			toolNode := map[string]any{}
			if strVal(tool["type"]) == "function" {
				if fn, ok := tool["function"].(map[string]any); ok {
					toolNode["name"] = strVal(fn["name"])
					if d, ok := fn["description"]; ok {
						toolNode["description"] = d
					}
					if p, ok := fn["parameters"]; ok {
						toolNode["input_schema"] = p
					}
				}
			} else {
				toolNode["name"] = strVal(tool["name"])
				if d, ok := tool["description"]; ok {
					toolNode["description"] = d
				}
				if s, ok := tool["input_schema"]; ok {
					toolNode["input_schema"] = s
				}
			}
			toolsNode = append(toolsNode, toolNode)
		}
		root["tools"] = toolsNode
	}
	if req.ReasoningEffort != nil && *req.ReasoningEffort != "" {
		root["reasoning_effort"] = *req.ReasoningEffort
	}
	if req.ToolChoice != nil {
		root["tool_choice"] = req.ToolChoice
	}
	out, err := json.Marshal(root)
	if err != nil {
		return "{}"
	}
	return string(out)
}

// applyDeepSeekReasoningContentPatch mirrors MessageTransformer's private patch.
func applyDeepSeekReasoningContentPatch(msgNode map[string]any, msg InternalMessage, req *InternalRequest) {
	if msg.Role != "assistant" {
		return
	}
	if msg.ReasoningContent != nil {
		msgNode["reasoning_content"] = *msg.ReasoningContent
		return
	}
	if thinking := extractThinkingText(msg); thinking != "" {
		if strings.Contains(strings.ToLower(req.Model), "deepseek") {
			msgNode["reasoning_content"] = thinking
		}
		return
	}
	if strings.Contains(strings.ToLower(req.Model), "deepseek") &&
		req.ReasoningEffort != nil && *req.ReasoningEffort != "" {
		msgNode["reasoning_content"] = ""
	}
}

func extractThinkingText(msg InternalMessage) string {
	var sb strings.Builder
	found := false
	for _, part := range msg.ContentParts {
		if blockType(part) == "thinking" {
			if t := strVal(part["thinking"]); t != "" {
				sb.WriteString(t)
				found = true
			}
		}
	}
	if !found {
		return ""
	}
	return sb.String()
}

// ---------------------------------------------------------------------------
// Non-stream response translation
// ---------------------------------------------------------------------------

// TransformResponse converts a provider response body into the client protocol.
func TransformResponse(providerBody, provider, clientFormat, originalModel string) string {
	var root any
	if err := json.Unmarshal([]byte(providerBody), &root); err != nil {
		return providerBody
	}
	if clientFormat == provider {
		return replaceModelInValue(root, originalModel, providerBody)
	}
	if provider == ProtoAnthropic { // anthropic → openai
		return convertAnthropicResponseToOpenAI(root, originalModel)
	}
	return convertOpenAIResponseToAnthropic(root, originalModel)
}

func replaceModelInValue(root any, originalModel, fallback string) string {
	obj, ok := root.(map[string]any)
	if !ok {
		return fallback
	}
	if _, has := obj["model"]; has {
		obj["model"] = originalModel
	}
	out, err := json.Marshal(obj)
	if err != nil {
		return fallback
	}
	return string(out)
}

// ReplaceModelInJSON rewrites the model field of a raw JSON string.
func ReplaceModelInJSON(raw, originalModel string) string {
	var obj map[string]any
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		return raw
	}
	if _, has := obj["model"]; !has {
		return raw
	}
	obj["model"] = originalModel
	out, err := json.Marshal(obj)
	if err != nil {
		return raw
	}
	return string(out)
}

func convertOpenAIResponseToAnthropic(root any, originalModel string) string {
	openai, ok := root.(map[string]any)
	if !ok {
		out, _ := json.Marshal(root)
		return string(out)
	}
	anthropic := map[string]any{
		"id":    strOrDefault(openai["id"], "msg_"+randHex(16)),
		"type":  "message",
		"role":  "assistant",
		"model": originalModel,
	}
	contentBlocks := []any{}
	if choices, ok := openai["choices"].([]any); ok && len(choices) > 0 {
		if first, ok := choices[0].(map[string]any); ok {
			if message, ok := first["message"].(map[string]any); ok {
				if content, ok := message["content"]; ok && content != nil {
					if s, ok := content.(string); ok && s != "" {
						contentBlocks = append(contentBlocks, map[string]any{"type": "text", "text": s})
					}
				}
				if tcs, ok := message["tool_calls"].([]any); ok {
					for _, tcAny := range tcs {
						tc, _ := tcAny.(map[string]any)
						if tc == nil {
							continue
						}
						toolUse := map[string]any{"type": "tool_use", "id": strVal(tc["id"])}
						if fn, ok := tc["function"].(map[string]any); ok {
							toolUse["name"] = strVal(fn["name"])
							args := strVal(fn["arguments"])
							var input any
							if json.Unmarshal([]byte(args), &input) == nil {
								toolUse["input"] = input
							} else {
								toolUse["input"] = map[string]any{}
							}
						}
						contentBlocks = append(contentBlocks, toolUse)
					}
				}
			}
			finishReason := strOrDefault(first["finish_reason"], "stop")
			anthropic["stop_reason"] = mapOpenAIStopReason(finishReason)
		}
	}
	anthropic["stop_sequence"] = nil
	anthropic["content"] = contentBlocks
	if usage, ok := openai["usage"].(map[string]any); ok {
		anthropic["usage"] = map[string]any{
			"input_tokens":  intVal(usage["prompt_tokens"]),
			"output_tokens": intVal(usage["completion_tokens"]),
		}
	}
	out, err := json.Marshal(anthropic)
	if err != nil {
		return string(mustJSON(root))
	}
	return string(out)
}

func convertAnthropicResponseToOpenAI(root any, originalModel string) string {
	anthropic, ok := root.(map[string]any)
	if !ok {
		out, _ := json.Marshal(root)
		return string(out)
	}
	openai := map[string]any{
		"id":      strOrDefault(anthropic["id"], "chatcmpl-"+randHex(16)),
		"object":  "chat.completion",
		"created": time.Now().Unix(),
		"model":   originalModel,
	}
	message := map[string]any{"role": "assistant"}
	var textContent strings.Builder
	var toolCalls []any
	if content, ok := anthropic["content"].([]any); ok {
		for _, blockAny := range content {
			block, _ := blockAny.(map[string]any)
			if block == nil {
				continue
			}
			switch blockType(block) {
			case "text":
				textContent.WriteString(strVal(block["text"]))
			case "tool_use":
				args := "{}"
				if input, ok := block["input"]; ok {
					if b, err := json.Marshal(input); err == nil {
						args = string(b)
					}
				}
				toolCalls = append(toolCalls, map[string]any{
					"id":   strVal(block["id"]),
					"type": "function",
					"function": map[string]any{
						"name":      strVal(block["name"]),
						"arguments": args,
					},
				})
			}
		}
	}
	if textContent.Len() > 0 {
		message["content"] = textContent.String()
	} else {
		message["content"] = nil
	}
	if len(toolCalls) > 0 {
		message["tool_calls"] = toolCalls
	}
	finishReason := mapAnthropicStopReason(strOrDefault(anthropic["stop_reason"], "stop"))
	openai["choices"] = []any{map[string]any{
		"index": 0, "message": message, "finish_reason": finishReason,
	}}
	if usage, ok := anthropic["usage"].(map[string]any); ok {
		inputTokens := intVal(usage["input_tokens"])
		outputTokens := intVal(usage["output_tokens"])
		openai["usage"] = map[string]any{
			"prompt_tokens":     inputTokens,
			"completion_tokens": outputTokens,
			"total_tokens":      inputTokens + outputTokens,
		}
	}
	out, err := json.Marshal(openai)
	if err != nil {
		return string(mustJSON(root))
	}
	return string(out)
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		return []byte("{}")
	}
	return b
}

func strOrDefault(v any, def string) string {
	if s, ok := v.(string); ok && s != "" {
		return s
	}
	return def
}

func intVal(v any) int {
	switch x := v.(type) {
	case float64:
		return int(x)
	case int:
		return x
	case string:
		var n int
		if _, err := fmt.Sscan(x, &n); err == nil {
			return n
		}
	}
	return 0
}

func mapOpenAIStopReason(reason string) string {
	switch reason {
	case "stop":
		return "end_turn"
	case "length":
		return "max_tokens"
	case "tool_calls":
		return "tool_use"
	default:
		return "end_turn"
	}
}

func mapAnthropicStopReason(reason string) string {
	switch reason {
	case "end_turn":
		return "stop"
	case "max_tokens":
		return "length"
	case "tool_use":
		return "tool_calls"
	case "stop_sequence":
		return "stop"
	default:
		return "stop"
	}
}

// ---------------------------------------------------------------------------
// Error response construction (MessageTransformer.buildErrorResponse)
// ---------------------------------------------------------------------------

// BuildErrorBody renders the client-protocol error envelope.
func BuildErrorBody(clientFormat, message, errorType string, statusCode int) string {
	if clientFormat == ProtoAnthropic {
		if errorType == "" {
			errorType = mapStatusToAnthropicErrorType(statusCode)
		}
		out, _ := json.Marshal(map[string]any{
			"type":  "error",
			"error": map[string]any{"type": errorType, "message": message},
		})
		return string(out)
	}
	if errorType == "" {
		errorType = mapStatusToOpenAIErrorType(statusCode)
	}
	out, _ := json.Marshal(map[string]any{
		"error": map[string]any{"message": message, "type": errorType, "code": statusCode},
	})
	return string(out)
}

func mapStatusToOpenAIErrorType(statusCode int) string {
	switch statusCode {
	case 400:
		return "invalid_request_error"
	case 401:
		return "authentication_error"
	case 403:
		return "permission_error"
	case 404:
		return "not_found_error"
	case 429:
		return "rate_limit_error"
	default:
		return "api_error"
	}
}

func mapStatusToAnthropicErrorType(statusCode int) string {
	switch statusCode {
	case 400:
		return "invalid_request_error"
	case 401:
		return "authentication_error"
	case 403:
		return "permission_error"
	case 404:
		return "not_found_error"
	case 429:
		return "rate_limit_error"
	case 529:
		return "overloaded_error"
	default:
		return "api_error"
	}
}

func randHex(n int) string {
	b := make([]byte, (n+1)/2)
	if _, err := rand.Read(b); err != nil {
		return strings.Repeat("0", n)
	}
	return hex.EncodeToString(b)[:n]
}
