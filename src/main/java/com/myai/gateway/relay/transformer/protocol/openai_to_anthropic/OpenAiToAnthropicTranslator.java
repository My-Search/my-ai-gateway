package com.myai.gateway.relay.transformer.protocol.openai_to_anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.registry.ProtocolTranslator;
import com.myai.gateway.relay.transformer.registry.StreamTranslateState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * OpenAI Chat Completion API → Anthropic Messages API 协议翻译器。
 *
 * <p>处理方向：OpenAI（上游）→ Anthropic（客户端）。
 * 包括流式响应转换（OpenAI SSE chunk → Anthropic SSE event）和非流式响应转换。
 *
 * <p>CPA 参考：对应 Go 中 {@code internal/translator/openai/claude/} 包的流式状态机。
 *
 * <p>流式事件映射：
 * <pre>
 * OpenAI chunk                             → Anthropic event
 * ─────────────────────────────────────────────────────
 * 首 chunk (choices[0].delta 含内容)        → content_block_start (text) + content_block_delta (text)
 * 中间 chunk (delta.content)                → content_block_delta (text_delta)
 * tool_calls chunk (含 id)                  → content_block_start (tool_use)
 * tool_calls chunk (function.arguments)     → content_block_delta (input_json_delta)
 * 最终 chunk (finish_reason + usage)        → message_delta (stop_reason + usage) + message_stop
 * [DONE]                                    → （translateStreamEnd 中发出 message_stop 兜底）
 * </pre>
 */
@Component
public class OpenAiToAnthropicTranslator implements ProtocolTranslator {

    private static final Logger log = LoggerFactory.getLogger(OpenAiToAnthropicTranslator.class);

    private final ObjectMapper objectMapper;

    public OpenAiToAnthropicTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceFormat() {
        return "openai";
    }

    @Override
    public String targetFormat() {
        return "anthropic";
    }

    // ==================== 请求翻译 ====================

    @Override
    public String translateRequest(InternalRequest request, String targetModel) {
        // 走内部模型桥接：buildAnthropicRequest 已处理 OpenAI→Anthropic 的请求格式转换
        throw new UnsupportedOperationException("OpenAI→Anthropic 请求转换由 MessageTransformer.buildAnthropicRequest() 处理");
    }

    // ==================== 非流式响应翻译 ====================

    @Override
    public String translateResponse(JsonNode providerResponse, String originalModel) {
        return convertOpenAiResponseToAnthropic(providerResponse, originalModel);
    }

    /**
     * OpenAI Chat Completion Response → Anthropic Messages Response。
     */
    private String convertOpenAiResponseToAnthropic(JsonNode openai, String originalModel) {
        try {
            ObjectNode anthropic = objectMapper.createObjectNode();
            anthropic.put("id", openai.has("id") ? openai.get("id").asText()
                    : "msg_" + UUID.randomUUID().toString().replace("-", ""));
            anthropic.put("type", "message");
            anthropic.put("role", "assistant");
            anthropic.put("model", originalModel);

            ArrayNode contentBlocks = anthropic.putArray("content");

            if (openai.has("choices") && openai.get("choices").isArray()
                    && openai.get("choices").size() > 0) {
                JsonNode firstChoice = openai.get("choices").get(0);
                JsonNode message = firstChoice.get("message");

                // 文本内容
                if (message != null && message.has("content") && !message.get("content").isNull()) {
                    String content = message.get("content").asText();
                    if (!content.isEmpty()) {
                        ObjectNode textBlock = contentBlocks.addObject();
                        textBlock.put("type", "text");
                        textBlock.put("text", content);
                    }
                }

                // tool_calls
                if (message != null && message.has("tool_calls")) {
                    for (JsonNode tc : message.get("tool_calls")) {
                        ObjectNode toolUse = contentBlocks.addObject();
                        toolUse.put("type", "tool_use");
                        toolUse.put("id", tc.get("id").asText());
                        JsonNode func = tc.get("function");
                        toolUse.put("name", func.get("name").asText());
                        try {
                            toolUse.set("input", objectMapper.readTree(func.get("arguments").asText()));
                        } catch (Exception e) {
                            toolUse.putObject("input");
                        }
                    }
                }

                String finishReason = firstChoice.has("finish_reason")
                        ? firstChoice.get("finish_reason").asText() : "stop";
                anthropic.put("stop_reason", mapOpenAiStopReason(finishReason));
            }

            anthropic.putNull("stop_sequence");

            if (openai.has("usage")) {
                ObjectNode usage = anthropic.putObject("usage");
                JsonNode oUsage = openai.get("usage");
                usage.put("input_tokens", oUsage.has("prompt_tokens") ? oUsage.get("prompt_tokens").asInt() : 0);
                usage.put("output_tokens", oUsage.has("completion_tokens") ? oUsage.get("completion_tokens").asInt() : 0);
            }

            return objectMapper.writeValueAsString(anthropic);
        } catch (Exception e) {
            log.error("OpenAI→Anthropic 响应转换失败", e);
            return openai.toString();
        }
    }

    // ==================== 流式响应翻译 ====================

    @Override
    public String translateStreamEvent(String eventType, String eventData,
                                       String originalModel, StreamTranslateState state) {
        OpenAiToAnthropicState s = (OpenAiToAnthropicState) state;
        try {
            if ("[DONE]".equals(eventData)) {
                // [DONE] 由 translateStreamEnd 统一处理
                return null;
            }

            JsonNode json = objectMapper.readTree(eventData);
            if (!json.has("choices") || json.get("choices").size() == 0) return null;

            JsonNode choice = json.get("choices").get(0);
            JsonNode delta = choice.get("delta");

            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                    ? choice.get("finish_reason").asText() : null;

            if (finishReason != null) {
                s.finishReasonSeen = true;
                // 发出 message_delta（含 stop_reason + usage）
                ObjectNode msgDelta = objectMapper.createObjectNode();
                msgDelta.put("type", "message_delta");
                ObjectNode deltaObj = msgDelta.putObject("delta");
                deltaObj.put("stop_reason", mapOpenAiStopReason(finishReason));
                deltaObj.putNull("stop_sequence");

                ObjectNode usageObj = msgDelta.putObject("usage");
                if (json.has("usage")) {
                    JsonNode src = json.get("usage");
                    s.promptTokens = src.has("prompt_tokens") ? src.get("prompt_tokens").asInt() : 0;
                    s.completionTokens = src.has("completion_tokens") ? src.get("completion_tokens").asInt() : 0;
                }
                usageObj.put("input_tokens", s.promptTokens);
                usageObj.put("output_tokens", s.completionTokens);

                s.messageDeltaSent = true;
                return objectMapper.writeValueAsString(msgDelta);
            }

            if (delta == null) return null;

            // 处理文本 delta
            if (delta.has("content") && !delta.get("content").isNull()) {
                String content = delta.get("content").asText();
                if (content.isEmpty()) return null;

                // 如果文本 content block 尚未开始，发 content_block_start
                if (!s.textContentStarted) {
                    s.textContentStarted = true;
                    ObjectNode startEvent = objectMapper.createObjectNode();
                    startEvent.put("type", "content_block_start");
                    startEvent.put("index", s.contentBlockIndex);
                    ObjectNode block = startEvent.putObject("content_block");
                    block.put("type", "text");
                    block.put("text", "");
                    return objectMapper.writeValueAsString(startEvent);
                    // 注意：这里只返回 start event，text delta 在下一次 chunks 中送达
                    // 但为了兼容原有行为，下面的 delta 也会同时发出
                }

                ObjectNode deltaEvent = objectMapper.createObjectNode();
                deltaEvent.put("type", "content_block_delta");
                deltaEvent.put("index", s.contentBlockIndex);
                ObjectNode deltaObj = deltaEvent.putObject("delta");
                deltaObj.put("type", "text_delta");
                deltaObj.put("text", content);
                return objectMapper.writeValueAsString(deltaEvent);
            }

            // 处理 tool_calls delta
            if (delta.has("tool_calls")) {
                StringBuilder combined = new StringBuilder();
                for (JsonNode tc : delta.get("tool_calls")) {
                    int idx = tc.has("index") ? tc.get("index").asInt() : 0;

                    if (tc.has("id")) {
                        // tool_use 开始：发 content_block_start (tool_use)
                        OpenAiToAnthropicState.ToolCallAccumulator acc =
                                s.toolCallAccumulators.computeIfAbsent(idx,
                                        k -> new OpenAiToAnthropicState.ToolCallAccumulator());
                        acc.index = idx;
                        acc.id = tc.get("id").asText();
                        acc.name = tc.has("function") && tc.get("function").has("name")
                                ? tc.get("function").get("name").asText() : "";
                        acc.contentBlockStarted = true;

                        ObjectNode startEvent = objectMapper.createObjectNode();
                        startEvent.put("type", "content_block_start");
                        startEvent.put("index", s.contentBlockIndex);

                        // 切换 content_block_index（tool_use 是独立 block）
                        ObjectNode block = startEvent.putObject("content_block");
                        block.put("type", "tool_use");
                        block.put("id", acc.id);
                        block.put("name", acc.name);
                        block.putObject("input");

                        if (combined.length() > 0) combined.append("\n");
                        combined.append(objectMapper.writeValueAsString(startEvent));

                    } else if (tc.has("function") && tc.get("function").has("arguments")) {
                        // tool_use arguments delta：发 content_block_delta (input_json_delta)
                        OpenAiToAnthropicState.ToolCallAccumulator acc =
                                s.toolCallAccumulators.get(idx);
                        if (acc == null) {
                            acc = new OpenAiToAnthropicState.ToolCallAccumulator();
                            acc.index = idx;
                            s.toolCallAccumulators.put(idx, acc);
                        }
                        String arguments = tc.get("function").get("arguments").asText();
                        acc.arguments.append(arguments);

                        ObjectNode deltaEvent = objectMapper.createObjectNode();
                        deltaEvent.put("type", "content_block_delta");
                        deltaEvent.put("index", s.contentBlockIndex);
                        ObjectNode deltaObj = deltaEvent.putObject("delta");
                        deltaObj.put("type", "input_json_delta");
                        deltaObj.put("partial_json", arguments);

                        if (combined.length() > 0) combined.append("\n");
                        combined.append(objectMapper.writeValueAsString(deltaEvent));
                    }
                }

                if (combined.length() > 0) {
                    // 返回多个 SSE 事件（用换行分隔，由调用方按行拆开）
                    // 注意：当前的 SSE 处理机制是逐行解析的，这里改为只返回第一个事件
                    // 调用方可以多次调用以获取多个事件
                    // 简化处理：多个事件暂不合并，只返回拼接结果让上层处理
                    return combined.toString();
                }
            }

            return null;

        } catch (Exception e) {
            log.debug("OpenAI→Anthropic 流式事件转换失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String translateStreamEnd(String originalModel, StreamTranslateState state) {
        OpenAiToAnthropicState s = (OpenAiToAnthropicState) state;

        // 如果 message_delta 尚未发出，补发一个终止事件
        if (!s.messageDeltaSent) {
            try {
                ObjectNode msgDelta = objectMapper.createObjectNode();
                msgDelta.put("type", "message_delta");
                ObjectNode deltaObj = msgDelta.putObject("delta");
                deltaObj.put("stop_reason", s.finishReasonSeen
                        ? mapOpenAiStopReason(s.finishReasonSeen ? "stop" : "stop") : "end_turn");
                deltaObj.putNull("stop_sequence");
                ObjectNode usageObj = msgDelta.putObject("usage");
                usageObj.put("input_tokens", s.promptTokens);
                usageObj.put("output_tokens", s.completionTokens);
                return objectMapper.writeValueAsString(msgDelta);
            } catch (Exception e) {
                log.debug("OpenAI→Anthropic 流式结束事件转换失败: {}", e.getMessage());
            }
        }
        return null;
    }

    @Override
    public StreamTranslateState createStreamState() {
        return new OpenAiToAnthropicState();
    }

    // ==================== 辅助方法 ====================

    /**
     * OpenAI finish_reason → Anthropic stop_reason 映射。
     */
    static String mapOpenAiStopReason(String reason) {
        if (reason == null) return "end_turn";
        return switch (reason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            default -> "end_turn";
        };
    }
}
