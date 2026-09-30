// Streaming translation for the OpenAI Responses API (client protocol).
//
// Upstream is always an openai/anthropic chat stream. For anthropic we first
// normalize each event into an OpenAI chat.completion.chunk (reusing the
// existing anthropicEventToOpenAI), then both providers share one code path
// that emits the Responses event lifecycle.
package relay

import (
	"encoding/json"
	"strings"
	"time"
)

// ResponsesState is the per-trace translator state for a Responses client.
type ResponsesState struct {
	// Provider is the upstream protocol ("openai" or "anthropic").
	Provider string
	// Anthropic normalizes anthropic events into OpenAI chunks first.
	Anthropic AnthropicToOpenAIState

	seq       int
	id        string
	model     string
	createdAt int64

	createdSent bool
	text        strings.Builder
	textDone    bool

	messageItemID   string
	messageIndex    int
	messageIndexSet bool
	messageOpened   bool
	messageClosed   bool
	nextOutputIndex int

	toolCalls   map[int]*responsesToolState
	toolCallSeq []int

	usagePrompt     int
	usageCompletion int
	usageSeen       bool

	finishReason string
	completed    bool
}

type responsesToolState struct {
	index       int
	callID      string
	name        string
	args        strings.Builder
	itemID      string
	outputIndex int
	added       bool
	done        bool
}

func (s *ResponsesState) translatorName() string { return "->responses" }

func (s *ResponsesState) ensureInit(model string) {
	if s.id == "" {
		s.id = "resp_" + randHex(24)
	}
	if s.createdAt == 0 {
		s.createdAt = time.Now().Unix()
	}
	if s.model == "" {
		s.model = model
	}
}

func (s *ResponsesState) nextSeq() int {
	s.seq++
	return s.seq
}

// event renders one Responses SSE payload (single-line JSON).
func (s *ResponsesState) event(typ string, extra map[string]any) string {
	node := map[string]any{"type": typ, "sequence_number": s.nextSeq()}
	for k, v := range extra {
		node[k] = v
	}
	return string(mustJSON(node))
}

// responsesEventFromProvider converts one upstream event into Responses events.
func responsesEventFromProvider(s *ResponsesState, provider, eventType, eventData, model string) []string {
	s.ensureInit(model)

	var chunkData []string
	if provider == ProtoAnthropic {
		chunkData = anthropicEventToOpenAI(&s.Anthropic, eventType, eventData, model)
	} else {
		chunkData = []string{eventData}
	}

	var out []string
	for _, data := range chunkData {
		if data == "" || data == "[DONE]" {
			continue
		}
		out = append(out, responsesEventsFromChunk(s, data)...)
	}
	return out
}

// responsesEventsFromChunk handles a single OpenAI chat.completion.chunk JSON.
func responsesEventsFromChunk(s *ResponsesState, data string) []string {
	var root map[string]any
	if json.Unmarshal([]byte(data), &root) != nil {
		return nil
	}
	if m := strVal(root["model"]); m != "" {
		s.model = m
	}
	var out []string
	out = append(out, s.ensureCreated()...)

	choices, _ := root["choices"].([]any)
	if len(choices) > 0 {
		if choice, ok := choices[0].(map[string]any); ok {
			if fr, ok := choice["finish_reason"]; ok && fr != nil {
				s.finishReason = strVal(fr)
			}
			if delta, ok := choice["delta"].(map[string]any); ok {
				out = append(out, s.handleDelta(delta)...)
			}
		}
	}

	if pt, ct, _, ok := ExtractUsageFromSseData(data); ok {
		s.usagePrompt, s.usageCompletion, s.usageSeen = pt, ct, true
	}
	return out
}

// ensureCreated emits response.created + response.in_progress exactly once.
func (s *ResponsesState) ensureCreated() []string {
	if s.createdSent {
		return nil
	}
	s.createdSent = true
	resp := buildResponsesObject(s.id, s.model, s.createdAt, []any{}, "", 0, 0, 0, 0, "in_progress")
	return []string{
		s.event("response.created", map[string]any{"response": resp}),
		s.event("response.in_progress", map[string]any{"response": resp}),
	}
}

func (s *ResponsesState) handleDelta(delta map[string]any) []string {
	var out []string

	if rc, ok := delta["reasoning_content"]; ok && rc != nil {
		// Reasoning is not surfaced as a separate event stream; keep the final
		// response.completed object authoritative.
		_ = strVal(rc)
	}

	if c, ok := delta["content"]; ok && c != nil {
		text := strVal(c)
		if text != "" {
			out = append(out, s.openMessageItem()...)
			s.text.WriteString(text)
			out = append(out, s.event("response.output_text.delta", map[string]any{
				"item_id":       s.messageItemID,
				"output_index":  s.messageIndex,
				"content_index": 0,
				"delta":         text,
			}))
		}
	}

	if tcs, ok := delta["tool_calls"].([]any); ok {
		for _, tcAny := range tcs {
			tc, ok := tcAny.(map[string]any)
			if !ok {
				continue
			}
			out = append(out, s.handleToolCallDelta(tc)...)
		}
	}
	return out
}

// openMessageItem lazily emits the message output item + content part.
func (s *ResponsesState) openMessageItem() []string {
	if s.messageOpened {
		return nil
	}
	s.messageOpened = true
	s.messageItemID = "msg_" + randHex(24)
	s.messageIndex = s.nextOutputIndex
	s.messageIndexSet = true
	s.nextOutputIndex++
	item := map[string]any{
		"type": "message", "id": s.messageItemID, "status": "in_progress",
		"role": "assistant", "content": []any{},
	}
	return []string{
		s.event("response.output_item.added", map[string]any{
			"output_index": s.messageIndex, "item": item,
		}),
		s.event("response.content_part.added", map[string]any{
			"item_id": s.messageItemID, "output_index": s.messageIndex,
			"content_index": 0,
			"part":          map[string]any{"type": "output_text", "text": "", "annotations": []any{}},
		}),
	}
}

func (s *ResponsesState) handleToolCallDelta(tc map[string]any) []string {
	index := intVal(tc["index"])
	if s.toolCalls == nil {
		s.toolCalls = map[int]*responsesToolState{}
	}
	st, ok := s.toolCalls[index]
	if !ok {
		st = &responsesToolState{index: index, itemID: "fc_" + randHex(24)}
		st.outputIndex = s.nextOutputIndex
		s.nextOutputIndex++
		s.toolCalls[index] = st
		s.toolCallSeq = append(s.toolCallSeq, index)
	}
	var out []string
	// The first sighting carries id/name; the item must exist before deltas.
	if !st.added {
		if id, ok := tc["id"].(string); ok && id != "" {
			st.callID = id
		}
		if fn, ok := tc["function"].(map[string]any); ok {
			if n, ok := fn["name"].(string); ok && n != "" {
				st.name = n
			}
		}
		if st.callID != "" || st.name != "" {
			st.added = true
			item := map[string]any{
				"type": "function_call", "id": st.itemID,
				"call_id": st.callID, "name": st.name, "arguments": "", "status": "in_progress",
			}
			out = append(out, s.event("response.output_item.added", map[string]any{
				"output_index": st.outputIndex, "item": item,
			}))
		}
	}
	if fn, ok := tc["function"].(map[string]any); ok {
		if partial := strVal(fn["arguments"]); partial != "" {
			st.args.WriteString(partial)
			out = append(out, s.event("response.function_call_arguments.delta", map[string]any{
				"item_id": st.itemID, "output_index": st.outputIndex, "delta": partial,
			}))
		}
	}
	return out
}

// responsesStreamEnd flushes the terminating Responses events. Idempotent.
func responsesStreamEnd(s *ResponsesState, model string) []string {
	s.ensureInit(model)
	if s.completed {
		return nil
	}
	var out []string
	out = append(out, s.ensureCreated()...)

	output := make([]any, 0, 1+len(s.toolCallSeq))

	// Close the text message item.
	if s.messageOpened && !s.messageClosed {
		s.messageClosed = true
		full := s.text.String()
		out = append(out,
			s.event("response.output_text.done", map[string]any{
				"item_id": s.messageItemID, "output_index": s.messageIndex,
				"content_index": 0, "text": full,
			}),
			s.event("response.content_part.done", map[string]any{
				"item_id": s.messageItemID, "output_index": s.messageIndex,
				"content_index": 0,
				"part":          map[string]any{"type": "output_text", "text": full, "annotations": []any{}},
			}),
		)
		item := map[string]any{
			"type": "message", "id": s.messageItemID, "status": "completed",
			"role": "assistant",
			"content": []any{
				map[string]any{"type": "output_text", "text": full, "annotations": []any{}},
			},
		}
		out = append(out, s.event("response.output_item.done", map[string]any{
			"output_index": s.messageIndex, "item": item,
		}))
		output = append(output, item)
	}

	// Close tool calls in the order they appeared.
	for _, idx := range s.toolCallSeq {
		st := s.toolCalls[idx]
		args := st.args.String()
		if args == "" {
			args = "{}"
		}
		if !st.done {
			st.done = true
			out = append(out, s.event("response.function_call_arguments.done", map[string]any{
				"item_id": st.itemID, "output_index": st.outputIndex, "arguments": args,
			}))
			item := map[string]any{
				"type": "function_call", "id": st.itemID, "call_id": st.callID,
				"name": st.name, "arguments": args, "status": "completed",
			}
			out = append(out, s.event("response.output_item.done", map[string]any{
				"output_index": st.outputIndex, "item": item,
			}))
			output = append(output, item)
		}
	}

	// A text-only response that never opened an item still needs a message item.
	if len(output) == 0 {
		item := responsesMessageItem(s.text.String())
		output = append(output, item)
	}

	status := "completed"
	final := buildResponsesObject(s.id, s.model, s.createdAt, output, s.text.String(),
		s.usagePrompt, s.usageCompletion, 0, 0, status)

	out = append(out, s.event("response.completed", map[string]any{"response": final}))
	s.completed = true
	return out
}
