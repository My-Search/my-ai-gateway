// OpenAI Responses API (POST /v1/responses) inbound support.
//
// The gateway treats "responses" purely as a *client* protocol: the request is
// normalized into the shared InternalRequest (so routing, retries, logging,
// prompt injection and media handling all work unchanged), and upstream
// channels keep speaking the openai/anthropic chat protocols. Provider
// responses are converted back into Responses objects / SSE events.
//
// This implementation is stateless: previous_response_id chaining is not
// supported and is ignored with a warning, so clients must send the full
// conversation in `input` on every call.
package relay

import (
	"encoding/json"
	"log/slog"
	"strings"
	"time"
)

// ---------------------------------------------------------------------------
// Request parsing
// ---------------------------------------------------------------------------

// parseResponsesRequest converts a Responses API request body into InternalRequest.
func parseResponsesRequest(raw map[string]json.RawMessage) *InternalRequest {
	req := &InternalRequest{
		ClientAPIFormat: ProtoResponses,
		Model:           rawString(raw, "model"),
		Stream:          rawBool(raw, "stream"),
		Temperature:     rawFloat(raw, "temperature"),
		TopP:            rawFloat(raw, "top_p"),
		MaxTokens:       rawInt(raw, "max_output_tokens"),
	}

	// Stateless gateway: previous_response_id cannot be honored.
	if v, ok := raw["previous_response_id"]; ok {
		var s string
		if json.Unmarshal(v, &s) == nil && strings.TrimSpace(s) != "" {
			slog.Warn("Responses API previous_response_id 暂不支持（无状态网关），已忽略，请携带完整 input",
				"previous_response_id", s)
		}
	}

	// reasoning.effort -> reasoning_effort
	if v, ok := raw["reasoning"]; ok {
		var r map[string]any
		if json.Unmarshal(v, &r) == nil {
			if effort, ok := r["effort"].(string); ok && effort != "" {
				req.ReasoningEffort = &effort
			}
		}
	}

	// text.format -> response_format (chat-completions compatible)
	if v, ok := raw["text"]; ok {
		var t map[string]json.RawMessage
		if json.Unmarshal(v, &t) == nil {
			if f, ok := t["format"]; ok {
				applyResponsesTextFormat(req, f)
			}
		}
	}

	// instructions -> leading system message (matches the OpenAI parser so that
	// prompt-injection replace_system rules keep working).
	var messages []InternalMessage
	if v, ok := raw["instructions"]; ok {
		var s string
		if json.Unmarshal(v, &s) == nil && strings.TrimSpace(s) != "" {
			messages = append(messages, InternalMessage{Role: "system", Content: s})
		}
	}
	if v, ok := raw["input"]; ok {
		messages = append(messages, parseResponsesInput(v)...)
	}
	req.Messages = messages

	// tools: flat Responses shape -> OpenAI nested shape (function tools only).
	if v, ok := raw["tools"]; ok {
		var tools []map[string]any
		if json.Unmarshal(v, &tools) == nil {
			out := make([]map[string]any, 0, len(tools))
			for _, tool := range tools {
				if strVal(tool["type"]) != "function" {
					continue
				}
				fn := map[string]any{"name": strVal(tool["name"])}
				if d, ok := tool["description"]; ok {
					fn["description"] = d
				}
				if p, ok := tool["parameters"]; ok {
					fn["parameters"] = p
				}
				if s, ok := tool["strict"]; ok {
					fn["strict"] = s
				}
				out = append(out, map[string]any{"type": "function", "function": fn})
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

func applyResponsesTextFormat(req *InternalRequest, f json.RawMessage) {
	var fmtObj map[string]any
	if json.Unmarshal(f, &fmtObj) != nil {
		return
	}
	set := func(v any) {
		if req.ExtraParams == nil {
			req.ExtraParams = map[string]any{}
		}
		req.ExtraParams["response_format"] = v
	}
	switch strVal(fmtObj["type"]) {
	case "json_schema":
		schema := map[string]any{"type": "json_schema"}
		if js, ok := fmtObj["json_schema"]; ok {
			schema["json_schema"] = js
		} else {
			// Responses flattens the schema fields under `format` directly.
			inner := map[string]any{}
			for _, k := range []string{"name", "description", "schema", "strict"} {
				if val, ok := fmtObj[k]; ok {
					inner[k] = val
				}
			}
			schema["json_schema"] = inner
		}
		set(schema)
	case "json_object":
		set(map[string]any{"type": "json_object"})
	}
}

// parseResponsesInput handles the `input` field, which may be a plain string or
// an array of input items.
func parseResponsesInput(v json.RawMessage) []InternalMessage {
	var s string
	if json.Unmarshal(v, &s) == nil {
		if s == "" {
			return nil
		}
		return []InternalMessage{{Role: "user", Content: s}}
	}
	var items []map[string]json.RawMessage
	if json.Unmarshal(v, &items) != nil {
		return nil
	}
	out := make([]InternalMessage, 0, len(items))
	for _, item := range items {
		switch rawString(item, "type") {
		case "", "message":
			im := InternalMessage{Role: rawString(item, "role")}
			if im.Role == "" {
				im.Role = "user"
			}
			if c, ok := item["content"]; ok {
				var cs string
				if json.Unmarshal(c, &cs) == nil {
					im.Content = cs
				} else {
					var parts []map[string]json.RawMessage
					if json.Unmarshal(c, &parts) == nil {
						im.ContentParts = responsesPartsToOpenAI(parts)
					}
				}
			}
			out = append(out, im)
		case "function_call":
			out = append(out, InternalMessage{
				Role: "assistant",
				ToolCalls: []map[string]any{{
					"id":   strOrDefault(rawString(item, "call_id"), rawString(item, "id")),
					"type": "function",
					"function": map[string]any{
						"name":      rawString(item, "name"),
						"arguments": strOrDefault(rawString(item, "arguments"), "{}"),
					},
				}},
			})
		case "function_call_output":
			out = append(out, InternalMessage{
				Role:       "tool",
				ToolCallID: rawString(item, "call_id"),
				Content:    strVal(rawField(item, "output")),
			})
		case "reasoning":
			if txt := responsesReasoningText(item); txt != "" {
				out = append(out, InternalMessage{Role: "assistant", ReasoningContent: &txt})
			}
		default:
			// Unknown item types are ignored rather than failing the request.
		}
	}
	return out
}

// responsesPartsToOpenAI normalizes Responses content parts into the OpenAI
// content-part vocabulary understood by the shared preprocessors.
func responsesPartsToOpenAI(parts []map[string]json.RawMessage) []map[string]any {
	out := make([]map[string]any, 0, len(parts))
	for _, p := range parts {
		switch rawString(p, "type") {
		case "input_text", "output_text", "text", "":
			out = append(out, map[string]any{"type": "text", "text": rawString(p, "text")})
		case "input_image":
			out = append(out, map[string]any{
				"type":      "image_url",
				"image_url": map[string]any{"url": rawString(p, "image_url")},
			})
		case "input_file":
			if d := rawString(p, "file_data"); d != "" {
				out = append(out, map[string]any{"type": "text", "text": d})
			}
		}
	}
	return out
}

// responsesReasoningText extracts plain text from a reasoning input item.
func responsesReasoningText(item map[string]json.RawMessage) string {
	for _, key := range []string{"summary", "content"} {
		v, ok := item[key]
		if !ok {
			continue
		}
		var blocks []map[string]any
		if json.Unmarshal(v, &blocks) != nil {
			continue
		}
		var sb strings.Builder
		for _, b := range blocks {
			if t, ok := b["text"].(string); ok {
				sb.WriteString(t)
			}
		}
		if sb.Len() > 0 {
			return sb.String()
		}
	}
	return ""
}

// ---------------------------------------------------------------------------
// Non-streaming response conversion
// ---------------------------------------------------------------------------

func convertChatCompletionToResponses(root map[string]any, originalModel string) string {
	var text, reasoning string
	var toolCalls []any
	if choices, ok := root["choices"].([]any); ok && len(choices) > 0 {
		if first, ok := choices[0].(map[string]any); ok {
			if msg, ok := first["message"].(map[string]any); ok {
				switch c := msg["content"].(type) {
				case string:
					text = c
				case []any:
					var sb strings.Builder
					for _, partAny := range c {
						if part, ok := partAny.(map[string]any); ok && strVal(part["type"]) == "text" {
							sb.WriteString(strVal(part["text"]))
						}
					}
					text = sb.String()
				}
				if rc, ok := msg["reasoning_content"].(string); ok {
					reasoning = rc
				}
				if tcs, ok := msg["tool_calls"].([]any); ok {
					toolCalls = tcs
				}
			}
		}
	}
	pt, ct, tt := responsesUsageFromOpenAI(root["usage"])
	return string(mustJSON(buildResponsesObject(
		"resp_"+randHex(24), originalModel, int64(intVal(root["created"])),
		responsesOutputItems(text, reasoning, toolCalls), text, pt, ct, tt, 0, "completed")))
}

func convertAnthropicMessageToResponses(root map[string]any, originalModel string) string {
	var text, reasoning strings.Builder
	var toolCalls []any
	if content, ok := root["content"].([]any); ok {
		for _, blockAny := range content {
			block, ok := blockAny.(map[string]any)
			if !ok {
				continue
			}
			switch blockType(block) {
			case "text":
				text.WriteString(strVal(block["text"]))
			case "thinking":
				reasoning.WriteString(strVal(block["thinking"]))
			case "tool_use":
				args := "{}"
				if input, ok := block["input"]; ok {
					args = string(mustJSON(input))
				}
				toolCalls = append(toolCalls, map[string]any{
					"id":       strVal(block["id"]),
					"function": map[string]any{"name": strVal(block["name"]), "arguments": args},
				})
			}
		}
	}
	pt, ct := 0, 0
	if usage, ok := root["usage"].(map[string]any); ok {
		pt = intVal(usage["input_tokens"])
		ct = intVal(usage["output_tokens"])
	}
	return string(mustJSON(buildResponsesObject(
		"resp_"+randHex(24), originalModel, 0,
		responsesOutputItems(text.String(), reasoning.String(), toolCalls),
		text.String(), pt, ct, pt+ct, 0, "completed")))
}

// responsesOutputItems assembles the Responses `output` array.
func responsesOutputItems(text, reasoning string, toolCalls []any) []any {
	output := []any{}
	if reasoning != "" {
		output = append(output, map[string]any{
			"type":    "reasoning",
			"id":      "rs_" + randHex(24),
			"summary": []any{map[string]any{"type": "summary_text", "text": reasoning}},
		})
	}
	if text != "" || len(toolCalls) == 0 {
		output = append(output, responsesMessageItem(text))
	}
	for _, tcAny := range toolCalls {
		tc, _ := tcAny.(map[string]any)
		fn, _ := tc["function"].(map[string]any)
		output = append(output, map[string]any{
			"type":      "function_call",
			"id":        "fc_" + randHex(24),
			"call_id":   strOrDefault(tc["id"], "call_"+randHex(12)),
			"name":      strVal(fn["name"]),
			"arguments": strOrDefault(fn["arguments"], "{}"),
			"status":    "completed",
		})
	}
	return output
}

func responsesMessageItem(text string) map[string]any {
	return map[string]any{
		"type":   "message",
		"id":     "msg_" + randHex(24),
		"status": "completed",
		"role":   "assistant",
		"content": []any{
			map[string]any{"type": "output_text", "text": text, "annotations": []any{}},
		},
	}
}

// buildResponsesObject renders a complete Responses object.
func buildResponsesObject(id, model string, createdAt int64, output []any, outputText string,
	pt, ct, tt, reasoningTokens int, status string) map[string]any {
	if id == "" {
		id = "resp_" + randHex(24)
	}
	if createdAt == 0 {
		createdAt = time.Now().Unix()
	}
	if output == nil {
		output = []any{}
	}
	return map[string]any{
		"id":                 id,
		"object":             "response",
		"created_at":         createdAt,
		"status":             status,
		"model":              model,
		"output":             output,
		"output_text":        outputText,
		"error":              nil,
		"incomplete_details": nil,
		"usage":              responsesUsage(pt, ct, tt, reasoningTokens),
	}
}

func responsesUsage(pt, ct, tt, reasoningTokens int) map[string]any {
	if tt == 0 {
		tt = pt + ct
	}
	return map[string]any{
		"input_tokens":          pt,
		"input_tokens_details":  map[string]any{"cached_tokens": 0},
		"output_tokens":         ct,
		"output_tokens_details": map[string]any{"reasoning_tokens": reasoningTokens},
		"total_tokens":          tt,
	}
}

func responsesUsageFromOpenAI(v any) (pt, ct, tt int) {
	usage, ok := v.(map[string]any)
	if !ok {
		return 0, 0, 0
	}
	pt = intVal(usage["prompt_tokens"])
	ct = intVal(usage["completion_tokens"])
	if _, has := usage["total_tokens"]; has {
		tt = intVal(usage["total_tokens"])
	} else {
		tt = pt + ct
	}
	return pt, ct, tt
}
