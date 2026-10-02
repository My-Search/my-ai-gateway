// Upstream direction for the OpenAI Responses API.
//
// A channel whose channel_type is "responses" is sent Responses requests
// (buildResponsesRequest) and its Responses responses are converted back into
// the client protocol (convertResponsesToOpenAI, plus the streaming translator
// in stream_translate.go). This lets a Responses-native upstream serve clients
// that speak /v1/chat/completions, /v1/messages or /v1/responses alike.
package relay

import (
	"encoding/json"
	"strings"
)

// buildResponsesRequest renders a Responses API request body from the internal
// representation, mirroring parseResponsesRequest in the opposite direction.
func buildResponsesRequest(req *InternalRequest) string {
	root := map[string]any{"model": req.Model}

	instructions := req.SystemPrompt
	input := make([]any, 0, len(req.Messages))
	for _, msg := range req.Messages {
		switch msg.Role {
		case "system":
			// Responses carries the system prompt out-of-band.
			if instructions == "" {
				instructions = msg.Content
			}
		case "tool":
			input = append(input, map[string]any{
				"type":    "function_call_output",
				"call_id": msg.ToolCallID,
				"output":  msg.Content,
			})
		case "assistant":
			// Emit any assistant text first, then each tool call as a
			// function_call item (Responses models tool calls as output items).
			if text := internalMessageText(msg); text != "" {
				input = append(input, map[string]any{
					"role":    "assistant",
					"content": []any{map[string]any{"type": "output_text", "text": text}},
				})
			}
			for _, tc := range msg.ToolCalls {
				callID := strVal(tc["id"])
				name := ""
				args := "{}"
				if fn, ok := tc["function"].(map[string]any); ok {
					name = strVal(fn["name"])
					if a := strVal(fn["arguments"]); a != "" {
						args = a
					}
				}
				input = append(input, map[string]any{
					"type":      "function_call",
					"call_id":   callID,
					"name":      name,
					"arguments": args,
				})
			}
		default:
			content := responsesInputContent(msg)
			if content == nil {
				continue
			}
			role := msg.Role
			if role == "" {
				role = "user"
			}
			input = append(input, map[string]any{"role": role, "content": content})
		}
	}
	if instructions != "" {
		root["instructions"] = instructions
	}
	root["input"] = input

	if req.MaxTokens != nil {
		root["max_output_tokens"] = *req.MaxTokens
	}
	if req.Temperature != nil {
		root["temperature"] = *req.Temperature
	}
	if req.TopP != nil {
		root["top_p"] = *req.TopP
	}
	if req.ReasoningEffort != nil && *req.ReasoningEffort != "" {
		root["reasoning"] = map[string]any{"effort": *req.ReasoningEffort}
	}
	if req.Stream {
		root["stream"] = true
	}
	if len(req.Tools) > 0 {
		root["tools"] = responsesToolsFromInternal(req.Tools)
	}
	if req.ToolChoice != nil {
		root["tool_choice"] = responsesToolChoice(req.ToolChoice)
	}
	// response_format → text.format (Responses shape).
	if req.ExtraParams != nil {
		if rf, ok := req.ExtraParams["response_format"]; ok {
			if format := responsesTextFormat(rf); format != nil {
				root["text"] = map[string]any{"format": format}
			}
		}
		// Forward any other explicit extra params verbatim.
		for k, v := range req.ExtraParams {
			if k == "response_format" {
				continue
			}
			root[k] = v
		}
	}

	out, err := json.Marshal(root)
	if err != nil {
		return "{}"
	}
	return string(out)
}

// internalMessageText returns the plain text of a message (Content, or the
// concatenated text parts).
func internalMessageText(msg InternalMessage) string {
	if msg.Content != "" {
		return msg.Content
	}
	var sb strings.Builder
	for _, part := range msg.ContentParts {
		if blockType(part) == "text" {
			sb.WriteString(strVal(part["text"]))
		}
	}
	return sb.String()
}

// responsesInputContent converts a non-assistant message into the Responses
// `input` content value: a plain string when it is text-only, otherwise an
// array of input_text / input_image parts. Returns nil for an empty message.
func responsesInputContent(msg InternalMessage) any {
	if msg.Content != "" {
		return msg.Content
	}
	if len(msg.ContentParts) == 0 {
		return nil
	}
	parts := make([]any, 0, len(msg.ContentParts))
	for _, part := range msg.ContentParts {
		switch blockType(part) {
		case "text":
			parts = append(parts, map[string]any{"type": "input_text", "text": strVal(part["text"])})
		case "image_url":
			url := ""
			if iu, ok := part["image_url"].(map[string]any); ok {
				url = strVal(iu["url"])
			}
			parts = append(parts, map[string]any{"type": "input_image", "image_url": url})
		default:
			// tool_use / tool_result / thinking are handled elsewhere and must
			// not leak into Responses input content.
		}
	}
	if len(parts) == 0 {
		return nil
	}
	return parts
}

// responsesToolsFromInternal flattens OpenAI-nested tools into the Responses
// flat tool shape (function tools only).
func responsesToolsFromInternal(tools []map[string]any) []any {
	out := make([]any, 0, len(tools))
	for _, tool := range tools {
		if fn, ok := tool["function"].(map[string]any); ok {
			node := map[string]any{"type": "function", "name": strVal(fn["name"])}
			if d, ok := fn["description"]; ok {
				node["description"] = d
			}
			if p, ok := fn["parameters"]; ok {
				node["parameters"] = p
			}
			if s, ok := fn["strict"]; ok {
				node["strict"] = s
			}
			out = append(out, node)
			continue
		}
		if strVal(tool["type"]) == "function" {
			out = append(out, tool)
		}
	}
	return out
}

// responsesToolChoice normalizes an OpenAI chat tool_choice into the Responses
// flat form (nested {"type":"function","function":{"name"}} → {"type":"function","name"}).
func responsesToolChoice(choice any) any {
	obj, ok := choice.(map[string]any)
	if !ok {
		return choice
	}
	if strVal(obj["type"]) != "function" {
		return choice
	}
	if fn, ok := obj["function"].(map[string]any); ok {
		return map[string]any{"type": "function", "name": strVal(fn["name"])}
	}
	return choice
}

// responsesTextFormat converts an OpenAI chat response_format into the Responses
// text.format shape. Returns nil when the value cannot be represented.
func responsesTextFormat(rf any) any {
	obj, ok := rf.(map[string]any)
	if !ok {
		return nil
	}
	switch strVal(obj["type"]) {
	case "json_object":
		return map[string]any{"type": "json_object"}
	case "json_schema":
		inner, _ := obj["json_schema"].(map[string]any)
		if inner == nil {
			inner = obj
		}
		out := map[string]any{"type": "json_schema"}
		for _, k := range []string{"name", "description", "schema", "strict"} {
			if v, ok := inner[k]; ok {
				out[k] = v
			}
		}
		return out
	}
	return nil
}

// ---------------------------------------------------------------------------
// Non-streaming response conversion: Responses → OpenAI chat completion
// ---------------------------------------------------------------------------

// convertResponsesToOpenAI converts a Responses object into a chat.completion
// envelope (the shared intermediate form from which anthropic is derived).
func convertResponsesToOpenAI(root map[string]any, originalModel string) string {
	var text, reasoning strings.Builder
	var toolCalls []any

	output, _ := root["output"].([]any)
	for _, itemAny := range output {
		item, ok := itemAny.(map[string]any)
		if !ok {
			continue
		}
		switch strVal(item["type"]) {
		case "message":
			if content, ok := item["content"].([]any); ok {
				for _, partAny := range content {
					if part, ok := partAny.(map[string]any); ok && strVal(part["type"]) == "output_text" {
						text.WriteString(strVal(part["text"]))
					}
				}
			}
		case "reasoning":
			reasoning.WriteString(responsesReasoningSummaryText(item))
		case "function_call":
			toolCalls = append(toolCalls, map[string]any{
				"id":   strVal(item["call_id"]),
				"type": "function",
				"function": map[string]any{
					"name":      strVal(item["name"]),
					"arguments": strOrDefault(item["arguments"], "{}"),
				},
			})
		}
	}

	message := map[string]any{"role": "assistant"}
	if text.Len() > 0 {
		message["content"] = text.String()
	} else {
		message["content"] = nil
	}
	if len(toolCalls) > 0 {
		message["tool_calls"] = toolCalls
	}
	if reasoning.Len() > 0 {
		message["reasoning_content"] = reasoning.String()
	}

	finishReason := "stop"
	if len(toolCalls) > 0 {
		finishReason = "tool_calls"
	} else if strVal(root["status"]) == "incomplete" {
		if details, ok := root["incomplete_details"].(map[string]any); ok &&
			strVal(details["reason"]) == "max_output_tokens" {
			finishReason = "length"
		}
	}

	created := int64(intVal(root["created_at"]))
	out := map[string]any{
		"id":      strOrDefault(root["id"], "chatcmpl-"+randHex(16)),
		"object":  "chat.completion",
		"created": created,
		"model":   originalModel,
		"choices": []any{map[string]any{
			"index": 0, "message": message, "finish_reason": finishReason,
		}},
	}
	if usage, ok := root["usage"].(map[string]any); ok {
		pt := intVal(usage["input_tokens"])
		ct := intVal(usage["output_tokens"])
		tt := intVal(usage["total_tokens"])
		if tt == 0 {
			tt = pt + ct
		}
		out["usage"] = map[string]any{
			"prompt_tokens":     pt,
			"completion_tokens": ct,
			"total_tokens":      tt,
		}
	}
	return string(mustJSON(out))
}

// responsesReasoningSummaryText joins the summary_text blocks of a reasoning item.
func responsesReasoningSummaryText(item map[string]any) string {
	var sb strings.Builder
	for _, key := range []string{"summary", "content"} {
		blocks, ok := item[key].([]any)
		if !ok {
			continue
		}
		for _, blockAny := range blocks {
			if block, ok := blockAny.(map[string]any); ok {
				sb.WriteString(strVal(block["text"]))
			}
		}
	}
	return sb.String()
}
