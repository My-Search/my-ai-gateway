package relay

import (
	"encoding/json"
	"strings"
	"testing"
)

// TestOpenAIToAnthropicStream checks the full event sequence an Anthropic client
// receives when the upstream speaks OpenAI.
func TestOpenAIToAnthropicStream(t *testing.T) {
	state := NewTranslateState(ProtoOpenAI, ProtoAnthropic)
	origModel := "my-model"

	chunks := []string{
		`{"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}`,
		`{"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}`,
		`{"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}`,
		`{"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}`,
		`{"id":"chatcmpl-1","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}`,
	}
	var all []string
	for _, ch := range chunks {
		all = append(all, TranslateStreamEvent(state, ProtoOpenAI, "", ch, origModel)...)
	}

	types := eventTypes(all)
	wantOrder := []string{"message_start", "content_block_start", "content_block_delta",
		"content_block_delta", "content_block_stop", "message_delta", "message_stop"}
	if strings.Join(types, ",") != strings.Join(wantOrder, ",") {
		t.Errorf("event sequence mismatch:\n got %v\nwant %v\nstream:\n%s", types, wantOrder, strings.Join(all, "\n"))
	}
	joined := strings.Join(all, "\n")
	mustContain(t, joined, `"text":"Hello"`)
	mustContain(t, joined, `"text":" world"`)
	mustContain(t, joined, `"text_delta"`)
	mustContain(t, joined, `"stop_reason":"end_turn"`)
	mustContain(t, joined, `"input_tokens":10`)
	mustContain(t, joined, `"output_tokens":2`)
}

// eventTypes extracts the "type" field of each translated event payload.
// One translator call may return several events joined by "\n" (Java's
// appendEvent behaviour), so split on newlines first.
func eventTypes(events []string) []string {
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

// TestOpenAIToAnthropicToolCalls checks tool_calls → tool_use streaming.
func TestOpenAIToAnthropicToolCalls(t *testing.T) {
	state := NewTranslateState(ProtoOpenAI, ProtoAnthropic)
	chunks := []string{
		`{"id":"c1","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}`,
		`{"id":"c1","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"finish_reason":null}]}`,
		`{"id":"c1","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"SF\"}"}}]},"finish_reason":"tool_calls"}]}`,
		`{"id":"c1","model":"m","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":7}}`,
	}
	var all []string
	for _, ch := range chunks {
		all = append(all, TranslateStreamEvent(state, ProtoOpenAI, "", ch, "my-model")...)
	}
	joined := strings.Join(all, "\n")
	mustContain(t, joined, `"id":"call_1"`)
	mustContain(t, joined, `"name":"get_weather"`)
	mustContain(t, joined, `"type":"tool_use"`)
	mustContain(t, joined, `"type":"input_json_delta"`)
	mustContain(t, joined, `"stop_reason":"tool_use"`)
	mustContain(t, joined, `"type":"message_stop"`)
	// The tool_use block start must carry an empty input object.
	mustContain(t, joined, `"input":{}`)
}

// TestAnthropicToOpenAIStream checks the reverse direction including tool_use.
func TestAnthropicToOpenAIStream(t *testing.T) {
	state := NewTranslateState(ProtoAnthropic, ProtoOpenAI)
	var all []string
	feed := func(event, data string) {
		all = append(all, TranslateStreamEvent(state, ProtoAnthropic, event, data, "my-model")...)
	}
	feed("message_start", `{"type":"message_start","message":{"id":"msg_1","model":"claude-3","usage":{"input_tokens":10,"output_tokens":0}}}`)
	feed("content_block_start", `{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}`)
	feed("content_block_delta", `{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}`)
	feed("content_block_stop", `{"type":"content_block_stop","index":0}`)
	feed("content_block_start", `{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"calc"}}`)
	feed("content_block_delta", `{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}`)
	feed("message_delta", `{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":10,"output_tokens":3}}`)
	feed("message_stop", `{"type":"message_stop"}`)

	joined := strings.Join(all, "\n")
	for _, want := range []string{
		`"object":"chat.completion.chunk"`,
		`"content":"Hi"`,
		`"tool_calls"`,
		`"id":"toolu_1"`,
		`"name":"calc"`,
		`"arguments":"{\"a\":"`,
		`"finish_reason":"tool_calls"`,
		`"prompt_tokens":10`,
		`"completion_tokens":3`,
	} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing %s in translated stream:\n%s", want, joined)
		}
	}
}

// TestResponseTranslation checks the non-stream both directions.
func TestResponseTranslation(t *testing.T) {
	openaiBody := `{"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"hi","tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{\"x\":1}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}`
	out := TransformResponse(openaiBody, ProtoOpenAI, ProtoAnthropic, "my-model")
	mustContain(t, out, `"type":"message"`)
	mustContain(t, out, `"model":"my-model"`)
	mustContain(t, out, `"stop_reason":"tool_use"`)
	mustContain(t, out, `"type":"tool_use"`)
	mustContain(t, out, `"input_tokens":3`)
	mustContain(t, out, `"output_tokens":4`)

	anthropicBody := `{"id":"msg_1","type":"message","model":"claude-3","content":[{"type":"text","text":"hi"},{"type":"tool_use","id":"t1","name":"f","input":{"x":1}}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":4}}`
	out2 := TransformResponse(anthropicBody, ProtoAnthropic, ProtoOpenAI, "my-model")
	mustContain(t, out2, `"object":"chat.completion"`)
	mustContain(t, out2, `"model":"my-model"`)
	mustContain(t, out2, `"finish_reason":"stop"`)
	mustContain(t, out2, `"arguments":"{\"x\":1}"`)
	mustContain(t, out2, `"total_tokens":7`)

	// Same protocol: only the model field is rewritten.
	out3 := TransformResponse(openaiBody, ProtoOpenAI, ProtoOpenAI, "my-model")
	mustContain(t, out3, `"model":"my-model"`)
	mustContain(t, out3, `"content":"hi"`)
}

// TestRequestBuilding checks both upstream request renderings.
func TestRequestBuilding(t *testing.T) {
	anthropicReq := `{"model":"my-model","max_tokens":100,"system":"be nice","messages":[{"role":"user","content":"hi"},{"role":"assistant","content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"t1","name":"f","input":{"x":1}}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"done"}]}],"tools":[{"name":"f","description":"d","input_schema":{"type":"object"}}],"stream":true}`
	req, err := ParseRequest(anthropicReq, ProtoAnthropic)
	if err != nil {
		t.Fatal(err)
	}
	openaiBody := BuildProviderRequest(req, ProtoOpenAI)
	mustContain(t, openaiBody, `"role":"system"`)
	mustContain(t, openaiBody, `"tool_call_id":"t1"`)
	mustContain(t, openaiBody, `"type":"function"`)
	mustContain(t, openaiBody, `"stream_options":{"include_usage":true}`)
	if strings.Contains(openaiBody, `"tool_use"`) || strings.Contains(openaiBody, `"tool_result"`) {
		t.Errorf("Anthropic-only content blocks leaked into OpenAI request: %s", openaiBody)
	}

	openaiReq := `{"model":"my-model","messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}]}`
	req2, err := ParseRequest(openaiReq, ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}
	anthropicBody := BuildProviderRequest(req2, ProtoAnthropic)
	mustContain(t, anthropicBody, `"max_tokens":4096`)
	mustContain(t, anthropicBody, `"input_schema"`)
	if strings.Contains(anthropicBody, `"function"`) {
		t.Errorf("OpenAI tool wrapper leaked into Anthropic request: %s", anthropicBody)
	}
}

// TestErrorBody checks the per-protocol error envelopes.
func TestErrorBody(t *testing.T) {
	o := BuildErrorBody(ProtoOpenAI, "boom", "authentication_error", 401)
	var om map[string]any
	if err := json.Unmarshal([]byte(o), &om); err != nil {
		t.Fatal(err)
	}
	if om["error"].(map[string]any)["code"].(float64) != 401 {
		t.Errorf("openai error code wrong: %s", o)
	}
	a := BuildErrorBody(ProtoAnthropic, "boom", "", 500)
	mustContain(t, a, `"type":"error"`)
	mustContain(t, a, `"type":"api_error"`)
}

// TestMediaSkip checks the media-type routing filter.
func TestMediaSkip(t *testing.T) {
	body := `{"model":"m","messages":[{"role":"user","content":[{"type":"text","text":"see"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAA"}}]}]}`
	req, _ := ParseRequest(body, ProtoOpenAI)
	types := DetectRequestMediaTypes(req)
	if len(types) != 1 || types[0] != "image" {
		t.Fatalf("expected [image], got %v", types)
	}
	if got := UnsupportedMediaTypes(req, "text"); len(got) != 1 || got[0] != "image" {
		t.Errorf("text-only channel should reject image, got %v", got)
	}
	if got := UnsupportedMediaTypes(req, "text,image"); len(got) != 0 {
		t.Errorf("multimodal channel should accept image, got %v", got)
	}
}

// TestUsageExtraction checks both protocol field names.
func TestUsageExtraction(t *testing.T) {
	pt, ct, tt := extractUsageFromResponse(`{"usage":{"input_tokens":5,"output_tokens":7}}`)
	if pt != 5 || ct != 7 || tt != 12 {
		t.Errorf("anthropic usage extraction wrong: %d %d %d", pt, ct, tt)
	}
	pt, ct, tt = extractUsageFromResponse(`{"usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":99}}`)
	if pt != 5 || ct != 7 || tt != 99 {
		t.Errorf("openai usage extraction wrong: %d %d %d", pt, ct, tt)
	}
}

// TestBuildFailMessage checks the provider-error unwrapping.
func TestBuildFailMessage(t *testing.T) {
	got := buildFailMessage(`Provider error: 500 body: {"error":{"message":"rate limited","type":"rate_limit"}}`)
	if got != "[rate_limit] rate limited" {
		t.Errorf("unwrap failed: %q", got)
	}
	if got := buildFailMessage("read timed out"); got != "请求超时" {
		t.Errorf("timeout mapping failed: %q", got)
	}
	if got := buildFailMessage(""); got != "所有候选均失败" {
		t.Errorf("empty mapping failed: %q", got)
	}
}

// TestSseBlockParsing checks multi-line data joining and named events.
// Real callers split the upstream stream on "\n\n" before calling this.
func TestSseBlockParsing(t *testing.T) {
	events := ParseSseEventBlock("event: message_start\ndata: {\"a\":1}")
	if len(events) != 1 || events[0].Event != "message_start" || events[0].Data != `{"a":1}` {
		t.Fatalf("named event wrong: %+v", events)
	}
	events2 := ParseSseEventBlock("data: {\"b\":2}")
	if len(events2) != 1 || events2[0].Event != "" || events2[0].Data != `{"b":2}` {
		t.Fatalf("unnamed event wrong: %+v", events2)
	}
	// Multi-line data lines join with \n (Java SseHandler behaviour).
	multi := ParseSseEventBlock("data: line1\ndata: line2")
	if len(multi) != 1 || multi[0].Data != "line1\nline2" {
		t.Errorf("multi-line data wrong: %+v", multi)
	}
}

func mustContain(t *testing.T, haystack, needle string) {
	t.Helper()
	if !strings.Contains(haystack, needle) {
		t.Errorf("missing %s in:\n%s", needle, haystack)
	}
}
