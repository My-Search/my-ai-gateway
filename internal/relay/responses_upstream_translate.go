// Streaming translation for an upstream channel that speaks the OpenAI
// Responses API.
//
// The Responses SSE stream is normalized event-by-event into OpenAI
// chat.completion.chunk payloads, then the existing chat translators take over:
// the openai client consumes those chunks directly, while the anthropic client
// runs them through openAIEventToAnthropic (ResponsesToAnthropicState embeds
// both state machines).
package relay

import (
	"encoding/json"
)

// ResponsesToOpenAIState tracks a Responses→chat-chunk translation.
type ResponsesToOpenAIState struct {
	started  bool
	finished bool
}

func (s *ResponsesToOpenAIState) translatorName() string { return "responses->openai" }

// ResponsesToAnthropicState converts Responses → chat chunks → anthropic events.
type ResponsesToAnthropicState struct {
	Inner ResponsesToOpenAIState
	Ant   OpenAIToAnthropicState
}

func (s *ResponsesToAnthropicState) translatorName() string { return "responses->anthropic" }

// responsesProviderEvent dispatches one upstream Responses event to the right
// client translator. The passthrough (client==responses) case is handled by the
// relay loop before this is reached, so only openai/anthropic states appear here.
func responsesProviderEvent(state StreamTranslateState, eventType, eventData, originalModel string) []string {
	chunks := responsesEventToChunks(state, eventType, eventData, originalModel)
	if len(chunks) == 0 {
		return nil
	}
	s, ok := state.(*ResponsesToAnthropicState)
	if !ok {
		// openai client consumes the chunks verbatim.
		return chunks
	}
	var out []string
	for _, chunk := range chunks {
		out = append(out, openAIEventToAnthropic(&s.Ant, chunk, originalModel)...)
	}
	return out
}

// responsesProviderStreamEnd flushes the terminating events when the upstream
// stream ends without a response.completed event.
func responsesProviderStreamEnd(state StreamTranslateState, originalModel string) []string {
	switch s := state.(type) {
	case *ResponsesToOpenAIState:
		if s.finished {
			return nil
		}
		return responsesCompletedChunks(s, "stop", nil, originalModel)
	case *ResponsesToAnthropicState:
		if s.Inner.finished {
			return nil
		}
		chunks := responsesCompletedChunks(&s.Inner, "stop", nil, originalModel)
		var out []string
		for _, chunk := range chunks {
			out = append(out, openAIEventToAnthropic(&s.Ant, chunk, originalModel)...)
		}
		return out
	}
	return nil
}

// responsesEventToChunks converts one Responses SSE event into OpenAI chat
// completion chunk JSON payloads (empty slice = drop).
func responsesEventToChunks(state StreamTranslateState, eventType, eventData, originalModel string) []string {
	var root map[string]any
	if err := json.Unmarshal([]byte(eventData), &root); err != nil {
		return nil
	}
	typ := strVal(root["type"])
	if typ == "" {
		typ = eventType
	}
	switch typ {
	case "response.created", "response.in_progress":
		return nil
	case "response.output_item.added":
		return responsesOutputItemAdded(state, root, originalModel)
	case "response.function_call_arguments.delta":
		return responsesFunctionCallArgsDelta(state, root, originalModel)
	case "response.output_text.delta":
		delta := strVal(root["delta"])
		if delta == "" {
			return nil
		}
		out := ensureStarted(state, originalModel)
		out = append(out, createOpenAIChunk(originalModel, nil, map[string]any{"content": delta}))
		return out
	case "response.completed", "response.incomplete", "response.failed":
		finish := responsesFinishReason(root)
		var usage map[string]any
		if resp, ok := root["response"].(map[string]any); ok {
			if u, ok := resp["usage"].(map[string]any); ok {
				usage = u
			}
		}
		return responsesCompleted(state, finish, usage, originalModel)
	}
	return nil
}

// responsesOutputItemAdded starts a tool call when a function_call item appears.
func responsesOutputItemAdded(state StreamTranslateState, root map[string]any, originalModel string) []string {
	item, ok := root["item"].(map[string]any)
	if !ok || strVal(item["type"]) != "function_call" {
		return nil
	}
	index := intVal(root["output_index"])
	callID := strVal(item["call_id"])
	if callID == "" {
		callID = strVal(item["id"])
	}
	delta := map[string]any{"tool_calls": []any{map[string]any{
		"index": index, "id": callID, "type": "function",
		"function": map[string]any{"name": strVal(item["name"]), "arguments": ""},
	}}}
	out := ensureStarted(state, originalModel)
	out = append(out, createOpenAIChunk(originalModel, nil, delta))
	return out
}

// responsesFunctionCallArgsDelta streams a tool call's arguments.
func responsesFunctionCallArgsDelta(state StreamTranslateState, root map[string]any, originalModel string) []string {
	partial := strVal(root["delta"])
	if partial == "" {
		return nil
	}
	index := intVal(root["output_index"])
	out := ensureStarted(state, originalModel)
	out = append(out, createOpenAIChunk(originalModel, nil, map[string]any{
		"tool_calls": []any{map[string]any{
			"index": index, "function": map[string]any{"arguments": partial},
		}},
	}))
	return out
}

// responsesCompleted marks the state finished and renders the terminal chat
// chunks (raw; the dispatcher converts them for anthropic clients).
func responsesCompleted(state StreamTranslateState, finishReason string, usage map[string]any, originalModel string) []string {
	switch s := state.(type) {
	case *ResponsesToOpenAIState:
		if s.finished {
			return nil
		}
		return responsesCompletedChunks(s, finishReason, usage, originalModel)
	case *ResponsesToAnthropicState:
		if s.Inner.finished {
			return nil
		}
		return responsesCompletedChunks(&s.Inner, finishReason, usage, originalModel)
	}
	return nil
}

// ensureStarted emits the leading role=assistant chunk exactly once.
func ensureStarted(state StreamTranslateState, originalModel string) []string {
	started := false
	switch s := state.(type) {
	case *ResponsesToOpenAIState:
		started = s.started
		s.started = true
	case *ResponsesToAnthropicState:
		started = s.Inner.started
		s.Inner.started = true
	}
	if started {
		return nil
	}
	return []string{createOpenAIChunk(originalModel, nil, map[string]any{"role": "assistant"})}
}

// responsesCompletedChunks renders the terminal chunk carrying finish_reason and
// (when available) usage, so the client observes a well-formed completion.
func responsesCompletedChunks(s *ResponsesToOpenAIState, finishReason string, usage map[string]any, originalModel string) []string {
	s.finished = true
	if finishReason == "" {
		finishReason = "stop"
	}
	chunk := createOpenAIChunk(originalModel, &finishReason, map[string]any{})
	if usage != nil {
		pt := intVal(usage["input_tokens"])
		ct := intVal(usage["output_tokens"])
		tt := intVal(usage["total_tokens"])
		if tt == 0 {
			tt = pt + ct
		}
		var m map[string]any
		if json.Unmarshal([]byte(chunk), &m) == nil {
			m["usage"] = map[string]any{
				"prompt_tokens":     pt,
				"completion_tokens": ct,
				"total_tokens":      tt,
			}
			return []string{mustJSONString(m)}
		}
	}
	return []string{chunk}
}

// responsesFinishReason maps a terminal Responses event to an OpenAI finish_reason.
func responsesFinishReason(root map[string]any) string {
	resp, _ := root["response"].(map[string]any)
	if resp == nil {
		resp = root
	}
	if strVal(resp["status"]) == "incomplete" {
		if details, ok := resp["incomplete_details"].(map[string]any); ok {
			if strVal(details["reason"]) == "content_filter" {
				return "content_filter"
			}
		}
		return "length"
	}
	if output, ok := resp["output"].([]any); ok {
		for _, itemAny := range output {
			if item, ok := itemAny.(map[string]any); ok && strVal(item["type"]) == "function_call" {
				return "tool_calls"
			}
		}
	}
	return "stop"
}
