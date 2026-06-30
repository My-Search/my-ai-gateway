package com.myai.gateway.relay.transformer.protocol.anthropic_to_openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.relay.transformer.InternalMessage;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.registry.ProtocolTranslator;
import com.myai.gateway.relay.transformer.registry.StreamTranslateState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Anthropic Messages API → OpenAI Chat Completion API 协议翻译器。
 *
 * <p>处理方向：Anthropic（上游）→ OpenAI（客户端）。
 * 包括请求转换（Anthropic 请求 → OpenAI 请求）、
 * 流式响应转换（Anthropic SSE → OpenAI SSE chunk）、
 * 非流式响应转换。
 *
 * <p>CPA 参考：对应 Go 中 {@code internal/translator/claude/openai/chat-completions/} 包的流式状态机。
 *
 * <p>流式事件映射：
 * <pre>
 * Anthropic event                  → OpenAI chunk
 * ──────────────────────────────────────────────────
 * message_start                    → 首 chunk (id, created, model)
 * content_block_start (text)       → 跳过（文本通过 delta 增量到达）
 * content_block_start (tool_use)   → tool_calls chunk (id + name, arguments="")
 * content_block_delta (text_delta) → delta.content chunk
 * content_block_delta (input_json) → tool_calls[0].function.arguments chunk
 * content_block_stop               → 跳过
 * message_delta                    → 最终 chunk (finish_reason + usage)
 * message_stop                     → [DONE]
 * </pre>
 */
@Component
public class AnthropicToOpenAiTranslator implements ProtocolTranslator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicToOpenAiTranslator.class);

    private final ObjectMapper objectMapper;

    public AnthropicToOpenAiTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceFormat() {
        return "anthropic";
    }

    @Override
    public String targetFormat() {
        return "openai";
    }

    // ==================== 请求翻译 ====================

    @Override
    public String translateRequest(InternalRequest request, String targetModel) {
        // 走内部模型桥接：buildOpenAiRequest 已处理 Anthropic→OpenAI 的请求格式转换
        // 此方法在 MessageTransformer.buildOpenAiRequest() 中有完整实现
        throw new UnsupportedOperationException("Anthropic→OpenAI 请求转换由 MessageTransformer.buildOpenAiRequest() 处理");
    }

    // ==================== 非流式响应翻译 ====================

    @Override
    public String translateResponse(JsonNode providerResponse, String originalModel) {
        return convertAnthropicResponseToOpenAi(providerResponse, originalModel);
    }

    /**
     * Anthropic Messages Response → OpenAI Chat Completion Response。
     */
    private String convertAnthropicResponseToOpenAi(JsonNode anthropic, String originalModel) {
        try {
            ObjectNode openai = objectMapper.createObjectNode();
            openai.put("id", anthropic.has("id") ? anthropic.get("id").asText()
                    : "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
            openai.put("object", "chat.completion");
            openai.put("created", System.currentTimeMillis() / 1000);
            openai.put("model", originalModel);

            ArrayNode choices = openai.putArray("choices");
            ObjectNode choice = choices.addObject();
            choice.put("index", 0);

            ObjectNode message = choice.putObject("message");
            message.put("role", "assistant");

            StringBuilder textContent = new StringBuilder();
            List<Map<String, Object>> toolCalls = new ArrayList<>();

            if (anthropic.has("content") && anthropic.get("content").isArray()) {
                for (JsonNode block : anthropic.get("content")) {
                    String type = block.has("type") ? block.get("type").asText() : "text";
                    if ("text".equals(type)) {
                        textContent.append(block.has("text") ? block.get("text").asText() : "");
                    } else if ("tool_use".equals(type)) {
                        Map<String, Object> tc = new LinkedHashMap<>();
                        tc.put("id", block.get("id").asText());
                        tc.put("type", "function");
                        Map<String, Object> func = new LinkedHashMap<>();
                        func.put("name", block.get("name").asText());
                        func.put("arguments", block.has("input")
                                ? objectMapper.writeValueAsString(block.get("input")) : "{}");
                        tc.put("function", func);
                        toolCalls.add(tc);
                    }
                }
            }

            message.put("content", textContent.length() > 0 ? textContent.toString() : null);
            if (!toolCalls.isEmpty()) {
                message.set("tool_calls", objectMapper.valueToTree(toolCalls));
            }

            String stopReason = anthropic.has("stop_reason")
                    ? anthropic.get("stop_reason").asText() : "stop";
            choice.put("finish_reason", mapAnthropicStopReason(stopReason));

            if (anthropic.has("usage")) {
                ObjectNode usage = openai.putObject("usage");
                JsonNode aUsage = anthropic.get("usage");
                int inputTokens = aUsage.has("input_tokens") ? aUsage.get("input_tokens").asInt() : 0;
                int outputTokens = aUsage.has("output_tokens") ? aUsage.get("output_tokens").asInt() : 0;
                usage.put("prompt_tokens", inputTokens);
                usage.put("completion_tokens", outputTokens);
                usage.put("total_tokens", inputTokens + outputTokens);
            }

            return objectMapper.writeValueAsString(openai);
        } catch (Exception e) {
            log.error("Anthropic→OpenAI 响应转换失败", e);
            return anthropic.toString();
        }
    }

    // ==================== 流式响应翻译 ====================

    @Override
    public String translateStreamEvent(String eventType, String eventData,
                                       String originalModel, StreamTranslateState state) {
        AnthropicToOpenAiState s = (AnthropicToOpenAiState) state;
        try {
            JsonNode json = objectMapper.readTree(eventData);

            if ("message_start".equals(eventType)) {
                // 提取 ID 和 model 供后续使用
                JsonNode msg = json.get("message");
                if (msg != null) {
                    s.responseId = msg.has("id") ? msg.get("id").asText() : null;
                    s.model = msg.has("model") ? msg.get("model").asText() : originalModel;
                }
                // 发出首 chunk
                ObjectNode chunk = createChunk(originalModel, null, null);
                s.messageStartSent = true;
                return objectMapper.writeValueAsString(chunk);

            } else if ("content_block_start".equals(eventType)) {
                JsonNode block = json.get("content_block");
                if (block != null && "tool_use".equals(block.has("type") ? block.get("type").asText() : null)) {
                    // tool_use 开始 → 发出 tool_calls chunk
                    int index = json.has("index") ? json.get("index").asInt() : 0;
                    s.pendingToolCallIndex = index;
                    s.pendingToolCallId = block.has("id") ? block.get("id").asText() : "";
                    s.pendingToolCallName = block.has("name") ? block.get("name").asText() : "";
                    s.pendingArguments.setLength(0);

                    ObjectNode delta = objectMapper.createObjectNode();
                    ArrayNode toolCalls = delta.putArray("tool_calls");
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("index", index);
                    tc.put("id", s.pendingToolCallId);
                    tc.put("type", "function");
                    ObjectNode func = tc.putObject("function");
                    func.put("name", s.pendingToolCallName);
                    func.put("arguments", "");
                    return objectMapper.writeValueAsString(createChunk(originalModel, null, delta));
                }
                // text block start 不单独发 chunk
                return null;

            } else if ("content_block_delta".equals(eventType)) {
                JsonNode delta = json.get("delta");
                if (delta == null) return null;

                String deltaType = delta.has("type") ? delta.get("type").asText() : "text_delta";
                if ("text_delta".equals(deltaType)) {
                    ObjectNode deltaNode = objectMapper.createObjectNode();
                    deltaNode.put("content", delta.get("text").asText());
                    return objectMapper.writeValueAsString(createChunk(originalModel, null, deltaNode));

                } else if ("input_json_delta".equals(deltaType)) {
                    // 累积 partial_json
                    String partial = delta.has("partial_json") ? delta.get("partial_json").asText() : "";
                    s.pendingArguments.append(partial);

                    ObjectNode deltaObj = objectMapper.createObjectNode();
                    ArrayNode toolCalls = deltaObj.putArray("tool_calls");
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("index", json.has("index") ? json.get("index").asInt() : s.pendingToolCallIndex);
                    ObjectNode func = tc.putObject("function");
                    func.put("arguments", partial);
                    return objectMapper.writeValueAsString(createChunk(originalModel, null, deltaObj));
                }
                return null;

            } else if ("message_delta".equals(eventType)) {
                // 最终 chunk：finish_reason + usage
                String stopReason = json.has("delta") && json.get("delta").has("stop_reason")
                        ? json.get("delta").get("stop_reason").asText() : "stop";
                s.finishReason = mapAnthropicStopReason(stopReason);
                s.messageDeltaSent = true;

                ObjectNode delta = objectMapper.createObjectNode();
                ObjectNode chunk = createChunk(originalModel, s.finishReason, delta);

                if (json.has("usage")) {
                    ObjectNode usage = chunk.putObject("usage");
                    JsonNode src = json.get("usage");
                    s.promptTokens = src.has("input_tokens") ? src.get("input_tokens").asInt() : 0;
                    s.completionTokens = src.has("output_tokens") ? src.get("output_tokens").asInt() : 0;
                    usage.put("prompt_tokens", s.promptTokens);
                    usage.put("completion_tokens", s.completionTokens);
                    usage.put("total_tokens", s.promptTokens + s.completionTokens);
                }
                return objectMapper.writeValueAsString(chunk);
            }
            // content_block_stop、message_stop 等跳过
            return null;

        } catch (Exception e) {
            log.debug("Anthropic→OpenAI 流式事件转换失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String translateStreamEnd(String originalModel, StreamTranslateState state) {
        AnthropicToOpenAiState s = (AnthropicToOpenAiState) state;
        // 如果 message_delta 还没有发出（异常结束），补发一个终结 chunk
        if (!s.messageDeltaSent) {
            try {
                ObjectNode delta = objectMapper.createObjectNode();
                String finishReason = s.finishReason != null ? s.finishReason : "stop";
                ObjectNode chunk = createChunk(originalModel, finishReason, delta);
                if (s.promptTokens > 0 || s.completionTokens > 0) {
                    ObjectNode usage = chunk.putObject("usage");
                    usage.put("prompt_tokens", s.promptTokens);
                    usage.put("completion_tokens", s.completionTokens);
                    usage.put("total_tokens", s.promptTokens + s.completionTokens);
                }
                return objectMapper.writeValueAsString(chunk);
            } catch (Exception e) {
                log.debug("Anthropic→OpenAI 流式结束事件转换失败: {}", e.getMessage());
            }
        }
        return null;
    }

    @Override
    public StreamTranslateState createStreamState() {
        return new AnthropicToOpenAiState();
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建 OpenAI 流式 chunk 的基础结构。
     */
    ObjectNode createChunk(String model, String finishReason, ObjectNode delta) {
        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", model);
        ArrayNode choices = chunk.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        choice.set("delta", delta != null ? delta : objectMapper.createObjectNode());
        choice.put("finish_reason", finishReason);
        return chunk;
    }

    /**
     * Anthropic stop_reason → OpenAI finish_reason 映射。
     */
    static String mapAnthropicStopReason(String reason) {
        if (reason == null) return "stop";
        return switch (reason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "stop_sequence" -> "stop";
            default -> "stop";
        };
    }
}
