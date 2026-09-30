package relay

import (
	"encoding/json"
	"strings"
	"testing"
)

// ---------------------------------------------------------------------------
// Request parsing
// ---------------------------------------------------------------------------

func TestParseResponsesStringInput(t *testing.T) {
	body := `{"model":"my-model","input":"Hello there","instructions":"Be brief","max_output_tokens":128,"temperature":0.5,"stream":false}`
	req, err := ParseRequest(body, ProtoResponses)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if req.ClientAPIFormat != ProtoResponses {
		t.Errorf("ClientAPIFormat = %q, want responses", req.ClientAPIFormat)
	}
	if req.Model != "my-model" {
		t.Errorf("Model = %q", req.Model)
	}
	if req.MaxTokens == nil || *req.MaxTokens != 128 {
		t.Errorf("MaxTokens = %v, want 128", req.MaxTokens)
	}
	if req.Temperature == nil || *req.Temperature != 0.5 {
		t.Errorf("Temperature = %v, want 0.5", req.Temperature)
	}
	if len(req.Messages) != 2 {
		t.Fatalf("expected 2 messages (system+user), got %d: %+v", len(req.Messages), req.Messages)
	}
	if req.Messages[0].Role != "system" || req.Messages[0].Content != "Be brief" {
		t.Errorf("instructions not mapped to system message: %+v", req.Messages[0])
	}
	if req.Messages[1].Role != "user" || req.Messages[1].Content != "Hello there" {
		t.Errorf("string input not mapped to user message: %+v", req.Messages[1])
	}
}

func TestParseResponsesArrayInputWithImage(t *testing.T) {
	body := `{
		"model":"m",
		"input":[
			{"type":"message","role":"user","content":[
				{"type":"input_text","text":"What is this?"},
				{"type":"input_image","image_url":"data:image/png;base64,AAAA"}
			]}
		]
	}`
	req, err := ParseRequest(body, ProtoResponses)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(req.Messages) != 1 {
		t.Fatalf("expected 1 message, got %d", len(req.Messages))
	}
	parts := req.Messages[0].ContentParts
	if len(parts) != 2 {
		t.Fatalf("expected 2 content parts, got %d: %+v", len(parts), parts)
	}
	if blockType(parts[0]) != "text" || strVal(parts[0]["text"]) != "What is this?" {
		t.Errorf("input_text not normalized: %+v", parts[0])
	}
	if blockType(parts[1]) != "image_url" {
		t.Errorf("input_image not normalized to image_url: %+v", parts[1])
	}
	// Media detection in preprocess.go must see the image.
	if types := DetectRequestMediaTypes(req); !containsStr(types, "image") {
		t.Errorf("DetectRequestMediaTypes = %v, want image", types)
	}
}

func TestParseResponsesToolsAndReasoning(t *testing.T) {
	body := `{
		"model":"m","input":"hi",
		"reasoning":{"effort":"high"},
		"text":{"format":{"type":"json_schema","name":"out","schema":{"type":"object"}}},
		"tools":[{"type":"function","name":"get_weather","description":"d","parameters":{"type":"object"}},
		         {"type":"web_search"}]
	}`
	req, err := ParseRequest(body, ProtoResponses)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if req.ReasoningEffort == nil || *req.ReasoningEffort != "high" {
		t.Errorf("reasoning effort = %v, want high", req.ReasoningEffort)
	}
	if len(req.Tools) != 1 {
		t.Fatalf("expected only function tool forwarded, got %d: %+v", len(req.Tools), req.Tools)
	}
	if strVal(req.Tools[0]["type"]) != "function" {
		t.Errorf("tool type = %v", req.Tools[0]["type"])
	}
	fn, _ := req.Tools[0]["function"].(map[string]any)
	if fn == nil || strVal(fn["name"]) != "get_weather" {
		t.Errorf("tool not wrapped in OpenAI function shape: %+v", req.Tools[0])
	}
	if req.ExtraParams == nil || req.ExtraParams["response_format"] == nil {
		t.Fatalf("text.format not mapped to response_format: %v", req.ExtraParams)
	}
}

func TestParseResponsesFunctionOutput(t *testing.T) {
	body := `{"model":"m","input":[
		{"type":"function_call","call_id":"call_1","name":"f","arguments":"{\"a\":1}"},
		{"type":"function_call_output","call_id":"call_1","output":"42"}
	]}`
	req, err := ParseRequest(body, ProtoResponses)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(req.Messages) != 2 {
		t.Fatalf("expected 2 messages, got %d", len(req.Messages))
	}
	if len(req.Messages[0].ToolCalls) != 1 || strVal(req.Messages[0].ToolCalls[0]["id"]) != "call_1" {
		t.Errorf("function_call not mapped to tool_calls: %+v", req.Messages[0])
	}
	if req.Messages[1].Role != "tool" || req.Messages[1].ToolCallID != "call_1" || req.Messages[1].Content != "42" {
		t.Errorf("function_call_output not mapped to tool message: %+v", req.Messages[1])
	}
}

// ---------------------------------------------------------------------------
// Non-streaming conversion
// ---------------------------------------------------------------------------

func TestTransformChatCompletionToResponses(t *testing.T) {
	body := `{"id":"chatcmpl-1","created":1700000000,"model":"gpt-4o",
		"choices":[{"index":0,"message":{"role":"assistant","content":"Hi there"},"finish_reason":"stop"}],
		"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}`
	out := TransformResponse(body, ProtoOpenAI, ProtoResponses, "my-model")
	var resp map[string]any
	if err := json.Unmarshal([]byte(out), &resp); err != nil {
		t.Fatalf("invalid JSON: %v\n%s", err, out)
	}
	if resp["object"] != "response" || resp["status"] != "completed" {
		t.Errorf("wrong object/status: %v / %v", resp["object"], resp["status"])
	}
	if resp["model"] != "my-model" {
		t.Errorf("model = %v, want my-model", resp["model"])
	}
	if resp["output_text"] != "Hi there" {
		t.Errorf("output_text = %v", resp["output_text"])
	}
	output, _ := resp["output"].([]any)
	if len(output) == 0 {
		t.Fatalf("empty output: %s", out)
	}
	msg, _ := output[0].(map[string]any)
	if msg["type"] != "message" {
		t.Errorf("first output item type = %v", msg["type"])
	}
	content, _ := msg["content"].([]any)
	if len(content) != 1 || content[0].(map[string]any)["type"] != "output_text" {
		t.Errorf("content part wrong: %+v", content)
	}
	usage, _ := resp["usage"].(map[string]any)
	if intVal(usage["input_tokens"]) != 5 || intVal(usage["output_tokens"]) != 2 || intVal(usage["total_tokens"]) != 7 {
		t.Errorf("usage wrong: %+v", usage)
	}
}

func TestTransformChatCompletionWithToolCallsToResponses(t *testing.T) {
	body := `{"id":"chatcmpl-2","created":1700000000,"model":"gpt-4o",
		"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
			{"id":"call_1","type":"function","function":{"name":"f","arguments":"{\"a\":1}"}}]},
			"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}`
	out := TransformResponse(body, ProtoOpenAI, ProtoResponses, "m")
	var resp map[string]any
	if err := json.Unmarshal([]byte(out), &resp); err != nil {
		t.Fatalf("invalid JSON: %v", err)
	}
	output, _ := resp["output"].([]any)
	var sawCall bool
	for _, itemAny := range output {
		if item, ok := itemAny.(map[string]any); ok && item["type"] == "function_call" {
			sawCall = true
			if item["call_id"] != "call_1" || item["name"] != "f" {
				t.Errorf("function_call fields wrong: %+v", item)
			}
		}
	}
	if !sawCall {
		t.Errorf("no function_call item in output: %s", out)
	}
}

func TestTransformAnthropicToResponses(t *testing.T) {
	body := `{"id":"msg_1","type":"message","role":"assistant","model":"claude",
		"content":[{"type":"text","text":"Hello"},{"type":"tool_use","id":"toolu_1","name":"f","input":{"x":1}}],
		"stop_reason":"tool_use","usage":{"input_tokens":9,"output_tokens":3}}`
	out := TransformResponse(body, ProtoAnthropic, ProtoResponses, "my-model")
	var resp map[string]any
	if err := json.Unmarshal([]byte(out), &resp); err != nil {
		t.Fatalf("invalid JSON: %v\n%s", err, out)
	}
	if resp["object"] != "response" || resp["model"] != "my-model" {
		t.Errorf("object/model wrong: %v / %v", resp["object"], resp["model"])
	}
	if resp["output_text"] != "Hello" {
		t.Errorf("output_text = %v", resp["output_text"])
	}
	usage, _ := resp["usage"].(map[string]any)
	if intVal(usage["input_tokens"]) != 9 || intVal(usage["output_tokens"]) != 3 {
		t.Errorf("usage wrong: %+v", usage)
	}
	output, _ := resp["output"].([]any)
	var sawCall bool
	for _, itemAny := range output {
		if item, ok := itemAny.(map[string]any); ok && item["type"] == "function_call" {
			sawCall = true
			if item["call_id"] != "toolu_1" {
				t.Errorf("call_id = %v", item["call_id"])
			}
		}
	}
	if !sawCall {
		t.Errorf("no function_call item: %s", out)
	}
}

// ---------------------------------------------------------------------------
// Streaming conversion
// ---------------------------------------------------------------------------

func TestResponsesStreamFromOpenAI(t *testing.T) {
	state := NewTranslateState(ProtoOpenAI, ProtoResponses)
	if _, ok := state.(*ResponsesState); !ok {
		t.Fatalf("NewTranslateState returned %T, want *ResponsesState", state)
	}
	chunks := []string{
		`{"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}`,
		`{"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}`,
		`{"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}`,
		`{"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}`,
		`{"id":"chatcmpl-1","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}`,
	}
	var all []string
	for _, ch := range chunks {
		all = append(all, TranslateStreamEvent(state, ProtoOpenAI, "", ch, "my-model")...)
	}
	all = append(all, TranslateStreamEnd(state, ProtoOpenAI, "my-model")...)

	types := flatEventTypes(all)
	want := []string{
		"response.created", "response.in_progress",
		"response.output_item.added", "response.content_part.added",
		"response.output_text.delta", "response.output_text.delta",
		"response.output_text.done", "response.content_part.done",
		"response.output_item.done", "response.completed",
	}
	if strings.Join(types, ",") != strings.Join(want, ",") {
		t.Fatalf("event sequence mismatch:\n got %v\nwant %v\nstream:\n%s", types, want, strings.Join(all, "\n"))
	}
	joined := strings.Join(all, "\n")
	mustContain(t, joined, `"delta":"Hel"`)
	mustContain(t, joined, `"delta":"lo"`)
	mustContain(t, joined, `"text":"Hello"`)
	mustContain(t, joined, `"input_tokens":4`)
	mustContain(t, joined, `"output_tokens":2`)
	// Responses streams must never emit a literal [DONE].
	if strings.Contains(joined, "[DONE]") {
		t.Errorf("unexpected [DONE] in Responses stream:\n%s", joined)
	}
}

func TestResponsesStreamFromAnthropic(t *testing.T) {
	state := NewTranslateState(ProtoAnthropic, ProtoResponses)
	events := []struct{ typ, data string }{
		{"message_start", `{"type":"message_start","message":{"id":"msg_1","model":"claude"}}`},
		{"content_block_start", `{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}`},
		{"content_block_delta", `{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}`},
		{"content_block_stop", `{"type":"content_block_stop","index":0}`},
		{"message_delta", `{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":3,"output_tokens":1}}`},
	}
	var all []string
	for _, ev := range events {
		all = append(all, TranslateStreamEvent(state, ProtoAnthropic, ev.typ, ev.data, "my-model")...)
	}
	all = append(all, TranslateStreamEnd(state, ProtoAnthropic, "my-model")...)

	types := flatEventTypes(all)
	if types[0] != "response.created" {
		t.Errorf("first event = %v, want response.created", types[0])
	}
	if last := types[len(types)-1]; last != "response.completed" {
		t.Errorf("last event = %v, want response.completed (all: %v)", last, types)
	}
	joined := strings.Join(all, "\n")
	mustContain(t, joined, `"delta":"Hi"`)
	mustContain(t, joined, `"output_text"`)
	mustContain(t, joined, `"input_tokens":3`)
	if strings.Contains(joined, "[DONE]") {
		t.Errorf("unexpected [DONE] in Responses stream:\n%s", joined)
	}
}

func TestResponsesStreamEndIdempotent(t *testing.T) {
	state := NewTranslateState(ProtoOpenAI, ProtoResponses)
	TranslateStreamEvent(state, ProtoOpenAI, "", `{"id":"c","model":"m","choices":[{"index":0,"delta":{"content":"x"},"finish_reason":null}]}`, "m")
	first := TranslateStreamEnd(state, ProtoOpenAI, "m")
	if len(first) == 0 {
		t.Fatal("expected terminal events")
	}
	second := TranslateStreamEnd(state, ProtoOpenAI, "m")
	if len(second) != 0 {
		t.Errorf("TranslateStreamEnd not idempotent: second call returned %v", second)
	}
}

// flatEventTypes extracts the "type" of each payload, splitting multi-line joins.
func flatEventTypes(events []string) []string {
	out := make([]string, 0, len(events))
	for _, e := range events {
		for _, line := range strings.Split(e, "\n") {
			var m map[string]any
			if json.Unmarshal([]byte(line), &m) == nil {
				if t, ok := m["type"].(string); ok {
					out = append(out, t)
				}
			}
		}
	}
	return out
}

func containsStr(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}
