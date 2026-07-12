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

import java.util.UUID;

/**
 * OpenAI Chat Completion API → Anthropic Messages API 协议翻译器。
 *
 * <p>处理方向：OpenAI（上游）→ Anthropic（客户端）。
 * 包括流式响应转换（OpenAI SSE chunk → Anthropic SSE event）和非流式响应转换。
 *
 * <p>流式状态机遵循 Anthropic SSE 规范：
 * <pre>
 * message_start → content_block_start → content_block_delta* → content_block_stop → message_delta → message_stop
 * </pre>
 * 类型切换时（text/thinking/tools）必须先关闭旧 block。
 * finish_reason 与 usage 解耦：若 finish_reason 帧未带 usage，延迟到 usage-only chunk 再发 message_delta。
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

    @Override
    public String translateRequest(InternalRequest request, String targetModel) {
        throw new UnsupportedOperationException("OpenAI→Anthropic 请求转换由 MessageTransformer.buildAnthropicRequest() 处理");
    }

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

                if (message != null && message.has("content") && !message.get("content").isNull()) {
                    String content = message.get("content").asText();
                    if (!content.isEmpty()) {
                        ObjectNode textBlock = contentBlocks.addObject();
                        textBlock.put("type", "text");
                        textBlock.put("text", content);
                    }
                }

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
                            log.warn("解析 tool_call arguments 失败，设为空对象: args={}", func.get("arguments"), e);
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
                return null;
            }

            JsonNode json = objectMapper.readTree(eventData);
            String chunkId = json.has("id") ? json.get("id").asText() : null;
            String chunkModel = json.has("model") ? json.get("model").asText() : originalModel;

            // 场景 D：usage-only chunk（choices 为空但带 usage）
            if (!json.has("choices") || json.get("choices").size() == 0) {
                if (json.has("usage")) {
                    extractUsage(json.get("usage"), s);
                    s.usageReceived = true;
                    if (s.finishReasonSeen && !s.messageDeltaSent) {
                        return buildMessageDeltaAndStop(s, chunkId, chunkModel);
                    }
                }
                return null;
            }

            JsonNode choice = json.get("choices").get(0);
            JsonNode delta = choice.get("delta");

            // 提前提取 usage（某些上游在任意 chunk 中携带）
            if (json.has("usage")) {
                extractUsage(json.get("usage"), s);
                s.usageReceived = true;
            }

            StringBuilder combined = new StringBuilder();

            // 第一个含 choices 的 chunk 时发出 message_start
            if (!s.messageStartSent) {
                s.messageStartSent = true;
                ObjectNode msgStart = objectMapper.createObjectNode();
                msgStart.put("type", "message_start");
                ObjectNode message = msgStart.putObject("message");
                message.put("id", chunkId != null ? chunkId : "msg_" + UUID.randomUUID().toString().replace("-", ""));
                message.put("type", "message");
                message.put("role", "assistant");
                message.put("model", chunkModel);
                ObjectNode usage = message.putObject("usage");
                usage.put("input_tokens", s.promptTokens);
                usage.put("output_tokens", 0);
                appendEvent(combined, msgStart);
            }

            // 处理 finish_reason
            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                    ? choice.get("finish_reason").asText() : null;

            if (finishReason != null) {
                s.finishReasonSeen = true;
                s.pendingFinishReason = finishReason;
                if (s.usageReceived) {
                    return buildMessageDeltaAndStop(s, chunkId, chunkModel);
                }
                // usage 尚未就绪：延迟关闭，等 usage-only chunk
                return combined.length() > 0 ? combined.toString() : null;
            }

            if (delta == null) {
                return combined.length() > 0 ? combined.toString() : null;
            }

            // 1. reasoning_content（thinking）
            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                String reasoning = delta.get("reasoning_content").asText();
                if (!reasoning.isEmpty()) {
                    switchToBlock(s, "thinking", combined);
                    ObjectNode deltaEvent = objectMapper.createObjectNode();
                    deltaEvent.put("type", "content_block_delta");
                    deltaEvent.put("index", s.contentBlockIndex);
                    ObjectNode d = deltaEvent.putObject("delta");
                    d.put("type", "thinking_delta");
                    d.put("thinking", reasoning);
                    appendEvent(combined, deltaEvent);
                }
            }

            // 2. 文本 content
            if (delta.has("content") && !delta.get("content").isNull()) {
                String content = delta.get("content").asText();
                if (!content.isEmpty()) {
                    switchToBlock(s, "text", combined);
                    ObjectNode deltaEvent = objectMapper.createObjectNode();
                    deltaEvent.put("type", "content_block_delta");
                    deltaEvent.put("index", s.contentBlockIndex);
                    ObjectNode d = deltaEvent.putObject("delta");
                    d.put("type", "text_delta");
                    d.put("text", content);
                    appendEvent(combined, deltaEvent);
                }
            }

            // 3. tool_calls
            if (delta.has("tool_calls")) {
                switchToBlock(s, "tools", combined);
                for (JsonNode tc : delta.get("tool_calls")) {
                    int idx = tc.has("index") ? tc.get("index").asInt() : 0;
                    int offset = idx;

                    if (offset > s.toolCallMaxIndexOffset) {
                        s.toolCallMaxIndexOffset = offset;
                    }

                    if (tc.has("id")) {
                        OpenAiToAnthropicState.ToolCallAccumulator acc = s.toolCallAccumulators.computeIfAbsent(idx,
                                k -> new OpenAiToAnthropicState.ToolCallAccumulator());
                        acc.index = idx;
                        acc.id = tc.get("id").asText();
                        acc.name = tc.has("function") && tc.get("function").has("name")
                                ? tc.get("function").get("name").asText() : "";

                        int blockIdx = s.toolCallBaseIndex + offset;
                        ObjectNode startEvent = objectMapper.createObjectNode();
                        startEvent.put("type", "content_block_start");
                        startEvent.put("index", blockIdx);
                        ObjectNode block = startEvent.putObject("content_block");
                        block.put("type", "tool_use");
                        block.put("id", acc.id);
                        block.put("name", acc.name);
                        block.putObject("input");
                        appendEvent(combined, startEvent);
                    }

                    if (tc.has("function") && tc.get("function").has("arguments")) {
                        String arguments = tc.get("function").get("arguments").asText();
                        OpenAiToAnthropicState.ToolCallAccumulator acc = s.toolCallAccumulators.get(idx);
                        if (acc == null) {
                            acc = new OpenAiToAnthropicState.ToolCallAccumulator();
                            acc.index = idx;
                            s.toolCallAccumulators.put(idx, acc);
                        }
                        acc.arguments.append(arguments);

                        int blockIdx = s.toolCallBaseIndex + offset;
                        ObjectNode deltaEvent = objectMapper.createObjectNode();
                        deltaEvent.put("type", "content_block_delta");
                        deltaEvent.put("index", blockIdx);
                        ObjectNode d = deltaEvent.putObject("delta");
                        d.put("type", "input_json_delta");
                        d.put("partial_json", arguments);
                        appendEvent(combined, deltaEvent);
                    }
                }
            }

            return combined.length() > 0 ? combined.toString() : null;

        } catch (Exception e) {
            log.debug("OpenAI→Anthropic 流式事件转换失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String translateStreamEnd(String originalModel, StreamTranslateState state) {
        OpenAiToAnthropicState s = (OpenAiToAnthropicState) state;
        if (!s.messageDeltaSent) {
            return buildMessageDeltaAndStop(s, null, originalModel);
        }
        return null;
    }

    @Override
    public StreamTranslateState createStreamState() {
        return new OpenAiToAnthropicState();
    }

    // ==================== 状态机辅助方法 ====================

    /**
     * 切换到指定类型的 content block。
     * 如果当前已有不同类型的 block 打开，先发送 content_block_stop 关闭。
     */
    private void switchToBlock(OpenAiToAnthropicState s, String newType, StringBuilder combined) {
        if (newType.equals(s.currentBlockType)) {
            return;
        }
        stopOpenBlocks(s, combined);

        if ("text".equals(newType)) {
            s.contentBlockIndex++;
            ObjectNode start = objectMapper.createObjectNode();
            start.put("type", "content_block_start");
            start.put("index", s.contentBlockIndex);
            ObjectNode block = start.putObject("content_block");
            block.put("type", "text");
            block.put("text", "");
            appendEvent(combined, start);
        } else if ("thinking".equals(newType)) {
            s.contentBlockIndex++;
            ObjectNode start = objectMapper.createObjectNode();
            start.put("type", "content_block_start");
            start.put("index", s.contentBlockIndex);
            ObjectNode block = start.putObject("content_block");
            block.put("type", "thinking");
            block.put("thinking", "");
            appendEvent(combined, start);
        } else if ("tools".equals(newType)) {
            s.toolCallBaseIndex = s.contentBlockIndex + 1;
            s.toolCallMaxIndexOffset = 0;
        }
        s.currentBlockType = newType;
    }

    /**
     * 关闭当前打开的所有 content block，发送 content_block_stop 事件。
     */
    private void stopOpenBlocks(OpenAiToAnthropicState s, StringBuilder combined) {
        switch (s.currentBlockType) {
            case "text":
            case "thinking":
                ObjectNode stop = objectMapper.createObjectNode();
                stop.put("type", "content_block_stop");
                stop.put("index", s.contentBlockIndex);
                appendEvent(combined, stop);
                break;
            case "tools":
                for (int offset = 0; offset <= s.toolCallMaxIndexOffset; offset++) {
                    ObjectNode st = objectMapper.createObjectNode();
                    st.put("type", "content_block_stop");
                    st.put("index", s.toolCallBaseIndex + offset);
                    appendEvent(combined, st);
                }
                s.contentBlockIndex = s.toolCallBaseIndex + s.toolCallMaxIndexOffset;
                break;
            default:
                // "none"：无 block 需要关闭
        }
    }

    /**
     * 构建 message_delta + message_stop 终结事件序列。
     * 包含 stop_reason、usage，以及关闭所有未关闭的 block。
     */
    private String buildMessageDeltaAndStop(OpenAiToAnthropicState s, String chunkId, String chunkModel) {
        try {
            StringBuilder combined = new StringBuilder();

            // 极端场景：收到 finish_reason 前从未发过 message_start，先补发
            if (!s.messageStartSent) {
                s.messageStartSent = true;
                ObjectNode msgStart = objectMapper.createObjectNode();
                msgStart.put("type", "message_start");
                ObjectNode message = msgStart.putObject("message");
                message.put("id", chunkId != null ? chunkId : "msg_" + UUID.randomUUID().toString().replace("-", ""));
                message.put("type", "message");
                message.put("role", "assistant");
                message.put("model", chunkModel != null ? chunkModel : "");
                ObjectNode usage = message.putObject("usage");
                usage.put("input_tokens", s.promptTokens);
                usage.put("output_tokens", 0);
                appendEvent(combined, msgStart);
            }

            stopOpenBlocks(s, combined);

            ObjectNode msgDelta = objectMapper.createObjectNode();
            msgDelta.put("type", "message_delta");
            ObjectNode deltaObj = msgDelta.putObject("delta");
            deltaObj.put("stop_reason", mapOpenAiStopReason(s.pendingFinishReason));
            deltaObj.putNull("stop_sequence");

            ObjectNode usageObj = msgDelta.putObject("usage");
            usageObj.put("input_tokens", s.promptTokens);
            usageObj.put("output_tokens", s.completionTokens);

            appendEvent(combined, msgDelta);

            ObjectNode msgStop = objectMapper.createObjectNode();
            msgStop.put("type", "message_stop");
            appendEvent(combined, msgStop);

            s.messageDeltaSent = true;
            return combined.toString();
        } catch (Exception e) {
            log.debug("构建 message_delta 失败: {}", e.getMessage());
            return null;
        }
    }

    private void appendEvent(StringBuilder combined, ObjectNode event) {
        try {
            if (combined.length() > 0) {
                combined.append('\n');
            }
            combined.append(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("序列化 SSE 事件失败: {}", e.getMessage());
        }
    }

    private void extractUsage(JsonNode usageNode, OpenAiToAnthropicState s) {
        s.promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0;
        s.completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0;
    }

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
