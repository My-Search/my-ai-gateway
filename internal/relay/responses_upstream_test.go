package relay

import (
	"encoding/json"
	"strings"
	"testing"
)

// ---------------------------------------------------------------------------
// Upstream Responses request building
// ---------------------------------------------------------------------------

func TestBuildResponsesRequestFromChatMessages(t *testing.T) {
	req := &InternalRequest{
		Model:        "resp-model",
		SystemPrompt: "Be terse",
		MaxTokens:    intPtr(64),
		Temperature:  floatPtr(0.2),
		Stream:       true,
		Messages: []InternalMessage{
			{Role: "user", Content: "hi"},
			{Role: "assistant", Content: "hello"},
			{Role: "tool", ToolCallID: "call_1", Content: "42"},
		},
	}
	out := buildResponsesRequest(req)
	var root map[string]any
	if err := json.Unmarshal([]byte(out), &root); err != nil {
		t.Fatalf("unmarshal: %v\n%s", err, out)
	}
	if root["model"] != "resp-model" {
		t.Errorf("model = %v", root["model"])
	}
	if root["instructions"] != "Be terse" {
		t.Errorf("instructions = %v", root["instructions"])
	}
	if root["max_output_tokens"].(float64) != 64 {
		t.Errorf("max_output_tokens = %v", root["max_output_tokens"])
	}
	if root["stream"] != true {
		t.Errorf("stream = %v", root["stream"])
	}
	input, ok := root["input"].([]any)
	if !ok || len(input) != 3 {
		t.Fatalf("input = %#v", root["input"])
	}
	// user message
	if m := input[0].(map[string]any); m["role"] != "user" || m["content"] != "hi" {
		t.Errorf("input[0] = %#v", m)
	}
	// assistant text → output_text part
	asst := input[1].(map[string]any)
	parts := asst["content"].([]any)
	if parts[0].(map[string]any)["type"] != "output_text" {
		t.Errorf("assistant content part = %#v", parts[0])
	}
	// tool → function_call_output
	tool := input[2].(map[string]any)
	if tool["type"] != "function_call_output" || tool["call_id"] != "call_1" {
		t.Errorf("tool item = %#v", tool)
	}
}

func TestBuildResponsesRequestToolsAndReasoning(t *testing.T) {
	req := &InternalRequest{
		Model:           "m",
		ReasoningEffort: strPtr("high"),
		Messages:        []InternalMessage{{Role: "user", Content: "x"}},
		Tools: []map[string]any{{
			"type": "function",
			"function": map[string]any{
				"name":        "lookup",
				"description": "d",
				"parameters":  map[string]any{"type": "object"},
			},
		}},
		ExtraParams: map[string]any{
			"response_format": map[string]any{"type": "json_object"},
		},
	}
	out := buildResponsesRequest(req)
	var root map[string]any
	if err := json.Unmarshal([]byte(out), &root); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if r, ok := root["reasoning"].(map[string]any); !ok || r["effort"] != "high" {
		t.Errorf("reasoning = %#v", root["reasoning"])
	}
	tools := root["tools"].([]any)
	if tl := tools[0].(map[string]any); tl["type"] != "function" || tl["name"] != "lookup" {
		t.Errorf("tool = %#v", tl)
	}
	if _, has := root["response_format"]; has {
		t.Errorf("response_format must be converted to text.format, not forwarded")
	}
	if tf := root["text"].(map[string]any)["format"].(map[string]any); tf["type"] != "json_object" {
		t.Errorf("text.format = %#v", root["text"])
	}
}

// ---------------------------------------------------------------------------
// Upstream Responses → OpenAI non-stream conversion
// ---------------------------------------------------------------------------

func TestConvertResponsesToOpenAI(t *testing.T) {
	body := `{
		"id":"resp_1","object":"response","status":"completed","model":"upstream",
		"created_at":1700000000,
		"output":[
			{"type":"reasoning","summary":[{"type":"summary_text","text":"think"}]},
			{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]},
			{"type":"function_call","call_id":"call_9","name":"f","arguments":"{\"a\":1}"}
		],
		"usage":{"input_tokens":11,"output_tokens":7,"total_tokens":18}
	}`
	var root map[string]any
	if err := json.Unmarshal([]byte(body), &root); err != nil {
		t.Fatal(err)
	}
	out := convertResponsesToOpenAI(root, "entry-model")
	var oai map[string]any
	if err := json.Unmarshal([]byte(out), &oai); err != nil {
		t.Fatalf("unmarshal: %v\n%s", err, out)
	}
	if oai["object"] != "chat.completion" || oai["model"] != "entry-model" {
		t.Errorf("envelope = %#v", oai)
	}
	choice := oai["choices"].([]any)[0].(map[string]any)
	msg := choice["message"].(map[string]any)
	if msg["content"] != "Hello" {
		t.Errorf("content = %#v", msg["content"])
	}
	if msg["reasoning_content"] != "think" {
		t.Errorf("reasoning_content = %#v", msg["reasoning_content"])
	}
	tcs := msg["tool_calls"].([]any)
	if len(tcs) != 1 || tcs[0].(map[string]any)["id"] != "call_9" {
		t.Errorf("tool_calls = %#v", tcs)
	}
	if choice["finish_reason"] != "tool_calls" {
		t.Errorf("finish_reason = %v", choice["finish_reason"])
	}
	usage := oai["usage"].(map[string]any)
	if usage["prompt_tokens"].(float64) != 11 || usage["completion_tokens"].(float64) != 7 {
		t.Errorf("usage = %#v", usage)
	}
}

func TestTransformResponseResponsesToOpenAIAndAnthropic(t *testing.T) {
	upstream := `{"id":"resp_1","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hi"}]}],"usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}`

	// responses → openai chat
	oai := TransformResponse(upstream, ProtoResponses, ProtoOpenAI, "m")
	if !strings.Contains(oai, `"chat.completion"`) || !strings.Contains(oai, `"Hi"`) {
		t.Errorf("responses→openai = %s", oai)
	}
	// responses → anthropic
	anth := TransformResponse(upstream, ProtoResponses, ProtoAnthropic, "m")
	if !strings.Contains(anth, `"type":"message"`) || !strings.Contains(anth, `"Hi"`) {
		t.Errorf("responses→anthropic = %s", anth)
	}
	// responses → responses passthrough (client model rewritten, output preserved)
	pass := TransformResponse(upstream, ProtoResponses, ProtoResponses, "m")
	if !strings.Contains(pass, `"resp_1"`) || !strings.Contains(pass, `"Hi"`) {
		t.Errorf("responses→responses = %s", pass)
	}
}

// ---------------------------------------------------------------------------
// Upstream Responses → streaming client chunks
// ---------------------------------------------------------------------------

func TestResponsesProviderStreamToOpenAI(t *testing.T) {
	state := NewTranslateState(ProtoResponses, ProtoOpenAI)
	switch state.(type) {
	case *ResponsesToOpenAIState:
	default:
		t.Fatalf("NewTranslateState(responses, openai) = %T", state)
	}

	var chunks []string
	events := []struct{ typ, data string }{
		{"response.created", `{"type":"response.created"}`},
		{"response.output_text.delta", `{"type":"response.output_text.delta","delta":"He"}`},
		{"response.output_text.delta", `{"type":"response.output_text.delta","delta":"llo"}`},
		{"response.completed", `{"type":"response.completed","response":{"status":"completed","output":[{"type":"message"}],"usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}}`},
	}
	for _, e := range events {
		chunks = append(chunks, TranslateStreamEvent(state, ProtoResponses, e.typ, e.data, "m")...)
	}
	chunks = append(chunks, TranslateStreamEnd(state, ProtoResponses, "m")...)

	joined := strings.Join(chunks, "\n")
	if !strings.Contains(joined, `"role":"assistant"`) {
		t.Errorf("missing role chunk:\n%s", joined)
	}
	if !strings.Contains(joined, `"content":"He"`) || !strings.Contains(joined, `"content":"llo"`) {
		t.Errorf("missing text deltas:\n%s", joined)
	}
	if !strings.Contains(joined, `"finish_reason":"stop"`) {
		t.Errorf("missing finish chunk:\n%s", joined)
	}
	if !strings.Contains(joined, `"total_tokens":6`) {
		t.Errorf("missing usage:\n%s", joined)
	}
	if !strings.Contains(joined, `"model":"m"`) {
		t.Errorf("wrong model:\n%s", joined)
	}
}

func TestResponsesProviderStreamToAnthropic(t *testing.T) {
	state := NewTranslateState(ProtoResponses, ProtoAnthropic)
	if _, ok := state.(*ResponsesToAnthropicState); !ok {
		t.Fatalf("NewTranslateState(responses, anthropic) = %T", state)
	}
	var out []string
	out = append(out, TranslateStreamEvent(state, ProtoResponses, "response.created", `{"type":"response.created"}`, "m")...)
	out = append(out, TranslateStreamEvent(state, ProtoResponses, "response.output_text.delta", `{"type":"response.output_text.delta","delta":"Hi"}`, "m")...)
	out = append(out, TranslateStreamEvent(state, ProtoResponses, "response.completed", `{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}`, "m")...)
	out = append(out, TranslateStreamEnd(state, ProtoResponses, "m")...)
	joined := strings.Join(out, "\n")
	if !strings.Contains(joined, `"type":"message_start"`) {
		t.Errorf("missing message_start:\n%s", joined)
	}
	if !strings.Contains(joined, `"text_delta"`) || !strings.Contains(joined, `"text":"Hi"`) {
		t.Errorf("missing text_delta:\n%s", joined)
	}
	if !strings.Contains(joined, `"type":"message_stop"`) {
		t.Errorf("missing message_stop:\n%s", joined)
	}
}

func TestResponsesProviderToolCallStream(t *testing.T) {
	state := NewTranslateState(ProtoResponses, ProtoOpenAI)
	added := `{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_1","name":"lookup"}}`
	args := `{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"x\":"}`
	done := `{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}`
	var chunks []string
	chunks = append(chunks, TranslateStreamEvent(state, ProtoResponses, "response.output_item.added", added, "m")...)
	chunks = append(chunks, TranslateStreamEvent(state, ProtoResponses, "response.function_call_arguments.delta", args, "m")...)
	chunks = append(chunks, TranslateStreamEvent(state, ProtoResponses, "response.completed", done, "m")...)
	joined := strings.Join(chunks, "\n")
	if !strings.Contains(joined, `"call_id"`) && !strings.Contains(joined, `"id":"call_1"`) {
		t.Errorf("missing tool call start:\n%s", joined)
	}
	if !strings.Contains(joined, `"arguments":"{\"x\":"`) {
		t.Errorf("missing arguments delta:\n%s", joined)
	}
	if !strings.Contains(joined, `"finish_reason":"tool_calls"`) {
		t.Errorf("finish_reason should be tool_calls:\n%s", joined)
	}
}

func TestExtractResponsesContentAndUsage(t *testing.T) {
	data := `{"type":"response.output_text.delta","delta":"abc"}`
	if got := ExtractTextContentFromRawData(data, ProtoResponses); got != "abc" {
		t.Errorf("ExtractTextContentFromRawData = %q, want abc", got)
	}
	completed := `{"type":"response.completed","response":{"usage":{"input_tokens":9,"output_tokens":4,"total_tokens":13}}}`
	pt, ct, tt, ok := ExtractUsageFromSseData(completed)
	if !ok || pt != 9 || ct != 4 || tt != 13 {
		t.Errorf("ExtractUsageFromSseData = %d,%d,%d,%v", pt, ct, tt, ok)
	}
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

func intPtr(n int) *int           { return &n }
func floatPtr(f float64) *float64 { return &f }
