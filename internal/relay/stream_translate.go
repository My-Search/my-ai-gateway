// Streaming protocol translation (OpenAI SSE chunks ↔ Anthropic SSE events).
//
// This file reproduces SseHandler's translation dispatch together with
// OpenAiToAnthropicTranslator and AnthropicToOpenAiTranslator, including the
// per-trace cross-chunk state machines.
//
// A translator call returns a list of payloads: an empty list means "drop this
// event" (the Java translators returned null).
package relay

import (
	"encoding/json"
	"log/slog"
	"strings"
	"sync"
	"time"
)

// StreamTranslateState is the cross-chunk translator state for one trace.
type StreamTranslateState interface {
	translatorName() string
}

// OpenAIToAnthropicState mirrors OpenAiToAnthropicState.
type OpenAIToAnthropicState struct {
	MessageStartSent       bool
	MessageDeltaSent       bool
	UsageReceived          bool
	FinishReasonSeen       bool
	PendingFinishReason    string
	HasPendingFinishReason bool
	CurrentBlockType       string
	ContentBlockIndex      int
	ToolCallBaseIndex      int
	ToolCallMaxIndexOffset int
	PromptTokens           int
	CompletionTokens       int
	ToolCallAccumulators   map[int]*toolCallAccumulator
}

type toolCallAccumulator struct {
	Index     int
	ID        string
	Name      string
	Arguments strings.Builder
}

func (s *OpenAIToAnthropicState) translatorName() string { return "openai->anthropic" }

// AnthropicToOpenAIState mirrors AnthropicToOpenAiState.
type AnthropicToOpenAIState struct {
	ResponseID           string
	Model                string
	MessageStartSent     bool
	MessageDeltaSent     bool
	FinishReason         string
	PendingToolCallIndex int
	PendingToolCallID    string
	PendingToolCallName  string
	PendingArguments     strings.Builder
	PromptTokens         int
	CompletionTokens     int
}

func (s *AnthropicToOpenAIState) translatorName() string { return "anthropic->openai" }

// NewTranslateState creates the state for a provider→client direction.
func NewTranslateState(provider, clientFormat string) StreamTranslateState {
	if provider == ProtoAnthropic {
		return &AnthropicToOpenAIState{}
	}
	return &OpenAIToAnthropicState{}
}

// TranslateStreamEvent converts one upstream event.
// Returns nil when the event must be dropped.
func TranslateStreamEvent(state StreamTranslateState, provider, eventType, eventData, originalModel string) []string {
	if state == nil {
		return nil
	}
	if provider == ProtoAnthropic {
		if s, ok := state.(*AnthropicToOpenAIState); ok {
			return anthropicEventToOpenAI(s, eventType, eventData, originalModel)
		}
		return nil
	}
	if s, ok := state.(*OpenAIToAnthropicState); ok {
		return openAIEventToAnthropic(s, eventData, originalModel)
	}
	return nil
}

// TranslateStreamEnd flushes the terminating events for a trace.
func TranslateStreamEnd(state StreamTranslateState, provider, originalModel string) []string {
	if state == nil {
		return nil
	}
	if provider == ProtoAnthropic {
		if s, ok := state.(*AnthropicToOpenAIState); ok {
			return anthropicStreamEnd(s, originalModel)
		}
		return nil
	}
	if s, ok := state.(*OpenAIToAnthropicState); ok {
		return openAIStreamEnd(s, originalModel)
	}
	return nil
}

// ---------------------------------------------------------------------------
// OpenAI → Anthropic
// ---------------------------------------------------------------------------

func openAIEventToAnthropic(s *OpenAIToAnthropicState, eventData, originalModel string) []string {
	if eventData == "[DONE]" {
		return nil
	}
	var root map[string]any
	if err := json.Unmarshal([]byte(eventData), &root); err != nil {
		return nil
	}
	chunkID := strVal(root["id"])
	chunkModel := strVal(root["model"])
	if chunkModel == "" {
		chunkModel = originalModel
	}

	choices, _ := root["choices"].([]any)
	// 场景 D：usage-only chunk（choices 为空但带 usage）
	if len(choices) == 0 {
		if usage, ok := root["usage"].(map[string]any); ok {
			extractOpenAIUsage(usage, s)
			s.UsageReceived = true
			if s.FinishReasonSeen && !s.MessageDeltaSent {
				return []string{buildMessageDeltaAndStop(s, chunkID, chunkModel)}
			}
		}
		return nil
	}

	choice, _ := choices[0].(map[string]any)
	if choice == nil {
		return nil
	}
	delta, _ := choice["delta"].(map[string]any)

	// 提前提取 usage（某些上游在任意 chunk 中携带）
	if usage, ok := root["usage"].(map[string]any); ok {
		extractOpenAIUsage(usage, s)
		s.UsageReceived = true
	}

	var out []string

	// 第一个含 choices 的 chunk 时发出 message_start
	if !s.MessageStartSent {
		s.MessageStartSent = true
		id := chunkID
		if id == "" {
			id = "msg_" + randHex(32)
		}
		out = append(out, mustJSONString(map[string]any{
			"type": "message_start",
			"message": map[string]any{
				"id": id, "type": "message", "role": "assistant", "model": chunkModel,
				"usage": map[string]any{"input_tokens": s.PromptTokens, "output_tokens": 0},
			},
		}))
	}

	// finish_reason：空字符串不视为结束信号
	finishReason := ""
	hasFinish := false
	if fr, ok := choice["finish_reason"]; ok && fr != nil {
		if s, ok := fr.(string); ok && s != "" {
			finishReason = s
			hasFinish = true
		}
	}
	if hasFinish {
		s.FinishReasonSeen = true
		s.PendingFinishReason = finishReason
		s.HasPendingFinishReason = true
		if s.UsageReceived {
			return append(out, buildMessageDeltaAndStop(s, chunkID, chunkModel))
		}
		// usage 尚未就绪：延迟关闭，等 usage-only chunk
		return out
	}

	if delta == nil {
		return out
	}

	// 1. reasoning_content（thinking）
	if rc, ok := delta["reasoning_content"]; ok && rc != nil {
		if reasoning, ok := rc.(string); ok && reasoning != "" {
			out = append(out, switchToBlock(s, "thinking")...)
			out = append(out, mustJSONString(map[string]any{
				"type":  "content_block_delta",
				"index": s.ContentBlockIndex,
				"delta": map[string]any{"type": "thinking_delta", "thinking": reasoning},
			}))
		}
	}

	// 2. 文本 content
	if c, ok := delta["content"]; ok && c != nil {
		if content, ok := c.(string); ok && content != "" {
			out = append(out, switchToBlock(s, "text")...)
			out = append(out, mustJSONString(map[string]any{
				"type":  "content_block_delta",
				"index": s.ContentBlockIndex,
				"delta": map[string]any{"type": "text_delta", "text": content},
			}))
		}
	}

	// 3. tool_calls
	if tcs, ok := delta["tool_calls"].([]any); ok {
		out = append(out, switchToBlock(s, "tools")...)
		for _, tcAny := range tcs {
			tc, _ := tcAny.(map[string]any)
			if tc == nil {
				continue
			}
			idx := intVal(tc["index"])
			offset := idx
			if offset > s.ToolCallMaxIndexOffset {
				s.ToolCallMaxIndexOffset = offset
			}
			if s.ToolCallAccumulators == nil {
				s.ToolCallAccumulators = map[int]*toolCallAccumulator{}
			}
			if id, ok := tc["id"]; ok && id != nil {
				acc := s.ToolCallAccumulators[idx]
				if acc == nil {
					acc = &toolCallAccumulator{}
					s.ToolCallAccumulators[idx] = acc
				}
				acc.Index = idx
				acc.ID = strVal(id)
				if fn, ok := tc["function"].(map[string]any); ok {
					acc.Name = strVal(fn["name"])
				}
				blockIdx := s.ToolCallBaseIndex + offset
				out = append(out, mustJSONString(map[string]any{
					"type":  "content_block_start",
					"index": blockIdx,
					"content_block": map[string]any{
						"type": "tool_use", "id": acc.ID, "name": acc.Name, "input": map[string]any{},
					},
				}))
			}
			if fn, ok := tc["function"].(map[string]any); ok {
				if args, ok := fn["arguments"]; ok && args != nil {
					arguments := strVal(args)
					acc := s.ToolCallAccumulators[idx]
					if acc == nil {
						acc = &toolCallAccumulator{}
						acc.Index = idx
						s.ToolCallAccumulators[idx] = acc
					}
					acc.Arguments.WriteString(arguments)
					blockIdx := s.ToolCallBaseIndex + offset
					out = append(out, mustJSONString(map[string]any{
						"type":  "content_block_delta",
						"index": blockIdx,
						"delta": map[string]any{"type": "input_json_delta", "partial_json": arguments},
					}))
				}
			}
		}
	}
	return out
}

// switchToBlock mirrors OpenAiToAnthropicTranslator.switchToBlock.
func switchToBlock(s *OpenAIToAnthropicState, newType string) []string {
	if newType == s.CurrentBlockType {
		return nil
	}
	out := stopOpenBlocks(s)
	switch newType {
	case "text":
		s.ContentBlockIndex++
		out = append(out, mustJSONString(map[string]any{
			"type": "content_block_start", "index": s.ContentBlockIndex,
			"content_block": map[string]any{"type": "text", "text": ""},
		}))
	case "thinking":
		s.ContentBlockIndex++
		out = append(out, mustJSONString(map[string]any{
			"type": "content_block_start", "index": s.ContentBlockIndex,
			"content_block": map[string]any{"type": "thinking", "thinking": ""},
		}))
	case "tools":
		s.ToolCallBaseIndex = s.ContentBlockIndex + 1
		s.ToolCallMaxIndexOffset = 0
	}
	s.CurrentBlockType = newType
	return out
}

// stopOpenBlocks mirrors OpenAiToAnthropicTranslator.stopOpenBlocks.
func stopOpenBlocks(s *OpenAIToAnthropicState) []string {
	var out []string
	switch s.CurrentBlockType {
	case "text", "thinking":
		out = append(out, mustJSONString(map[string]any{
			"type": "content_block_stop", "index": s.ContentBlockIndex,
		}))
	case "tools":
		for offset := 0; offset <= s.ToolCallMaxIndexOffset; offset++ {
			out = append(out, mustJSONString(map[string]any{
				"type": "content_block_stop", "index": s.ToolCallBaseIndex + offset,
			}))
		}
		s.ContentBlockIndex = s.ToolCallBaseIndex + s.ToolCallMaxIndexOffset
	}
	return out
}

func buildMessageDeltaAndStop(s *OpenAIToAnthropicState, chunkID, chunkModel string) string {
	var out []string
	// 极端场景：收到 finish_reason 前从未发过 message_start，先补发
	if !s.MessageStartSent {
		s.MessageStartSent = true
		id := chunkID
		if id == "" {
			id = "msg_" + randHex(32)
		}
		out = append(out, mustJSONString(map[string]any{
			"type": "message_start",
			"message": map[string]any{
				"id": id, "type": "message", "role": "assistant", "model": chunkModel,
				"usage": map[string]any{"input_tokens": s.PromptTokens, "output_tokens": 0},
			},
		}))
	}
	out = append(out, stopOpenBlocks(s)...)
	pending := s.PendingFinishReason
	if !s.HasPendingFinishReason {
		pending = ""
	}
	out = append(out, mustJSONString(map[string]any{
		"type": "message_delta",
		"delta": map[string]any{
			"stop_reason": mapOpenAIStopReason(pending), "stop_sequence": nil,
		},
		"usage": map[string]any{"input_tokens": s.PromptTokens, "output_tokens": s.CompletionTokens},
	}))
	out = append(out, mustJSONString(map[string]any{"type": "message_stop"}))
	s.MessageDeltaSent = true
	return strings.Join(out, "\n")
}

func openAIStreamEnd(s *OpenAIToAnthropicState, originalModel string) []string {
	if !s.MessageDeltaSent {
		return []string{buildMessageDeltaAndStop(s, "", originalModel)}
	}
	return nil
}

func extractOpenAIUsage(usage map[string]any, s *OpenAIToAnthropicState) {
	s.PromptTokens = intVal(usage["prompt_tokens"])
	s.CompletionTokens = intVal(usage["completion_tokens"])
}

// ---------------------------------------------------------------------------
// Anthropic → OpenAI
// ---------------------------------------------------------------------------

func anthropicEventToOpenAI(s *AnthropicToOpenAIState, eventType, eventData, originalModel string) []string {
	var root map[string]any
	if err := json.Unmarshal([]byte(eventData), &root); err != nil {
		return nil
	}
	switch eventType {
	case "message_start":
		if msg, ok := root["message"].(map[string]any); ok {
			s.ResponseID = strVal(msg["id"])
			s.Model = strOrDefault(msg["model"], originalModel)
		}
		s.MessageStartSent = true
		return []string{createOpenAIChunk(originalModel, nil, nil)}
	case "content_block_start":
		block, _ := root["content_block"].(map[string]any)
		if block != nil && blockType(block) == "tool_use" {
			index := intVal(root["index"])
			s.PendingToolCallIndex = index
			s.PendingToolCallID = strVal(block["id"])
			s.PendingToolCallName = strVal(block["name"])
			s.PendingArguments.Reset()
			delta := map[string]any{"tool_calls": []any{map[string]any{
				"index": index, "id": s.PendingToolCallID, "type": "function",
				"function": map[string]any{"name": s.PendingToolCallName, "arguments": ""},
			}}}
			return []string{createOpenAIChunk(originalModel, nil, delta)}
		}
		return nil
	case "content_block_delta":
		delta, _ := root["delta"].(map[string]any)
		if delta == nil {
			return nil
		}
		deltaType := strOrDefault(delta["type"], "text_delta")
		switch deltaType {
		case "text_delta":
			return []string{createOpenAIChunk(originalModel, nil, map[string]any{"content": strVal(delta["text"])})}
		case "input_json_delta":
			partial := strVal(delta["partial_json"])
			s.PendingArguments.WriteString(partial)
			index := s.PendingToolCallIndex
			if v, ok := root["index"]; ok {
				index = intVal(v)
			}
			return []string{createOpenAIChunk(originalModel, nil, map[string]any{
				"tool_calls": []any{map[string]any{"index": index, "function": map[string]any{"arguments": partial}}},
			})}
		}
		return nil
	case "message_delta":
		stopReason := "stop"
		if d, ok := root["delta"].(map[string]any); ok {
			if sr, ok := d["stop_reason"]; ok && sr != nil {
				stopReason = strVal(sr)
			}
		}
		s.FinishReason = mapAnthropicStopReason(stopReason)
		s.MessageDeltaSent = true
		chunk := createOpenAIChunk(originalModel, strPtr(s.FinishReason), map[string]any{})
		if usage, ok := root["usage"].(map[string]any); ok {
			s.PromptTokens = intVal(usage["input_tokens"])
			s.CompletionTokens = intVal(usage["output_tokens"])
			var m map[string]any
			if err := json.Unmarshal([]byte(chunk), &m); err == nil {
				m["usage"] = map[string]any{
					"prompt_tokens":     s.PromptTokens,
					"completion_tokens": s.CompletionTokens,
					"total_tokens":      s.PromptTokens + s.CompletionTokens,
				}
				return []string{mustJSONString(m)}
			}
		}
		return []string{chunk}
	}
	return nil
}

func anthropicStreamEnd(s *AnthropicToOpenAIState, originalModel string) []string {
	if s.MessageDeltaSent {
		return nil
	}
	finishReason := s.FinishReason
	if finishReason == "" {
		finishReason = "stop"
	}
	chunk := createOpenAIChunk(originalModel, &finishReason, map[string]any{})
	if s.PromptTokens > 0 || s.CompletionTokens > 0 {
		var m map[string]any
		if err := json.Unmarshal([]byte(chunk), &m); err == nil {
			m["usage"] = map[string]any{
				"prompt_tokens":     s.PromptTokens,
				"completion_tokens": s.CompletionTokens,
				"total_tokens":      s.PromptTokens + s.CompletionTokens,
			}
			return []string{mustJSONString(m)}
		}
	}
	return []string{chunk}
}

// createOpenAIChunk mirrors AnthropicToOpenAiTranslator.createChunk.
func createOpenAIChunk(model string, finishReason *string, delta map[string]any) string {
	choice := map[string]any{"index": 0, "finish_reason": finishReason}
	if delta == nil {
		delta = map[string]any{}
	}
	choice["delta"] = delta
	return mustJSONString(map[string]any{
		"id":      "chatcmpl-" + randHex(24),
		"object":  "chat.completion.chunk",
		"created": nowUnix(),
		"model":   model,
		"choices": []any{choice},
	})
}

func strPtr(s string) *string { return &s }

func nowUnix() int64 { return time.Now().Unix() }

func mustJSONString(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// ---------------------------------------------------------------------------
// Content extraction / usage (SseHandler.extractTextContentFromRawData etc.)
// ---------------------------------------------------------------------------

// ExtractTextContentFromRawData mirrors SseHandler.extractTextContentFromRawData.
func ExtractTextContentFromRawData(rawData, provider string) string {
	var root map[string]any
	if err := json.Unmarshal([]byte(rawData), &root); err != nil {
		return ""
	}
	if provider == ProtoAnthropic {
		if delta, ok := root["delta"].(map[string]any); ok {
			if t, ok := delta["text"]; ok {
				return strVal(t)
			}
		}
		if block, ok := root["content_block"].(map[string]any); ok {
			if t, ok := block["text"]; ok {
				return strVal(t)
			}
		}
		return ""
	}
	if choices, ok := root["choices"].([]any); ok && len(choices) > 0 {
		if choice, ok := choices[0].(map[string]any); ok {
			if delta, ok := choice["delta"].(map[string]any); ok {
				if c, ok := delta["content"]; ok && c != nil {
					return strVal(c)
				}
			}
		}
	}
	return ""
}

// ExtractUsageFromSseData mirrors SseHandler.extractUsageFromSseData.
func ExtractUsageFromSseData(data string) (pt, ct, tt int, ok bool) {
	if data == "" || data == "[DONE]" {
		return 0, 0, 0, false
	}
	var root map[string]any
	if err := json.Unmarshal([]byte(data), &root); err != nil {
		return 0, 0, 0, false
	}
	usage, isObj := root["usage"].(map[string]any)
	if !isObj {
		return 0, 0, 0, false
	}
	pt = intVal(usage["prompt_tokens"])
	if _, has := usage["prompt_tokens"]; !has {
		pt = intVal(usage["input_tokens"])
	}
	ct = intVal(usage["completion_tokens"])
	if _, has := usage["completion_tokens"]; !has {
		ct = intVal(usage["output_tokens"])
	}
	if v, has := usage["total_tokens"]; has {
		tt = intVal(v)
	} else {
		tt = pt + ct
	}
	return pt, ct, tt, true
}

// AddSseEventSplit mirrors SseHandler.addSseEventSplit: payloads containing
// newlines are split into separate events.
func AddSseEventSplit(events []SseEvent, event, data string) []SseEvent {
	if strings.Contains(data, "\n") {
		for _, part := range strings.Split(data, "\n") {
			if part != "" {
				events = append(events, SseEvent{Event: event, Data: part})
			}
		}
		return events
	}
	return append(events, SseEvent{Event: event, Data: data})
}

// StreamTranslateStates is the per-trace translator state registry
// (SseHandler.streamTranslateStates).
type StreamTranslateStates struct {
	mu     sync.Mutex
	states map[string]StreamTranslateState
}

func NewStreamTranslateStates() *StreamTranslateStates {
	return &StreamTranslateStates{states: map[string]StreamTranslateState{}}
}

func (r *StreamTranslateStates) GetOrCreate(traceID, provider, clientFormat string) StreamTranslateState {
	r.mu.Lock()
	defer r.mu.Unlock()
	if s, ok := r.states[traceID]; ok {
		return s
	}
	s := NewTranslateState(provider, clientFormat)
	r.states[traceID] = s
	return s
}

func (r *StreamTranslateStates) Remove(traceID string) StreamTranslateState {
	r.mu.Lock()
	defer r.mu.Unlock()
	s := r.states[traceID]
	delete(r.states, traceID)
	return s
}

func (r *StreamTranslateStates) Clear(traceID string) {
	r.mu.Lock()
	delete(r.states, traceID)
	r.mu.Unlock()
}

// ParseSseEventBlock mirrors SseHandler.parseSseEventBlock: one raw block may
// yield several events, and multi-line data: lines are joined with "\n".
func ParseSseEventBlock(block string) []SseEvent {
	var events []SseEvent
	var currentEvent string
	var dataBuilder strings.Builder
	flush := func() {
		if dataBuilder.Len() > 0 {
			events = append(events, SseEvent{Event: currentEvent, Data: dataBuilder.String()})
			dataBuilder.Reset()
		}
	}
	for _, line := range strings.Split(block, "\n") {
		line = strings.TrimRight(line, "\r")
		switch {
		case strings.HasPrefix(line, "event:"):
			flush()
			currentEvent = strings.TrimSpace(line[len("event:"):])
		case strings.HasPrefix(line, "data:"):
			if dataBuilder.Len() > 0 {
				dataBuilder.WriteString("\n")
			}
			dataBuilder.WriteString(strings.TrimSpace(line[len("data:"):]))
		}
	}
	flush()
	return events
}

var _ = slog.Info
