package com.myai.gateway.relay.transformer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 协议转换器
 *
 * 核心职责：
 * 1. 将 OpenAI Chat Completion 格式 ↔ 内部格式 双向转换
 * 2. 将 Anthropic Messages 格式 ↔ 内部格式 双向转换
 * 3. 流式 SSE 事件格式的转换
 *
 * 设计原则：
 * - 如果 client API 格式与 channel 类型相同，走透传路径（仅替换 model 名）
 * - 如果不同，经过 内部格式 桥接（client → internal → provider）
 * - 响应同样反向转换（provider → internal → client）
 */
@Component
public class ProtocolTransformer {

    private static final Logger log = LoggerFactory.getLogger(ProtocolTransformer.class);

    private final ObjectMapper objectMapper;

    public ProtocolTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    // 入站：客户端请求 → 内部格式
    // ========================================================================

    /**
     * 将客户端请求解析为内部统一格式
     *
     * @param requestBody 原始请求体 JSON
     * @param clientApiType 客户端 API 类型: "openai" 或 "anthropic"
     * @return 内部统一请求
     */
    public InternalRequest parseToInternal(String requestBody, String clientApiType) {
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            if ("anthropic".equals(clientApiType)) {
                return parseAnthropicRequest(root);
            } else {
                return parseOpenAiRequest(root);
            }
        } catch (Exception e) {
            throw new RuntimeException("解析请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * OpenAI Chat Completion → InternalRequest
     */
    private InternalRequest parseOpenAiRequest(JsonNode root) {
        InternalRequest req = new InternalRequest();
        req.setClientApiFormat("openai");
        req.setOriginalRequestJson(root);

        if (root.has("model")) req.setModel(root.get("model").asText());
        if (root.has("stream")) req.setStream(root.get("stream").asBoolean());
        if (root.has("max_tokens") && !root.get("max_tokens").isNull())
            req.setMaxTokens(root.get("max_tokens").asInt());
        if (root.has("temperature") && !root.get("temperature").isNull())
            req.setTemperature(root.get("temperature").asDouble());
        if (root.has("top_p") && !root.get("top_p").isNull())
            req.setTopP(root.get("top_p").asDouble());

        // Stop sequences
        if (root.has("stop")) {
            JsonNode stopNode = root.get("stop");
            List<String> stopList = new ArrayList<>();
            if (stopNode.isTextual()) {
                stopList.add(stopNode.asText());
            } else if (stopNode.isArray()) {
                for (JsonNode s : stopNode) stopList.add(s.asText());
            }
            req.setStop(stopList);
        }

        // Tools
        if (root.has("tools")) {
            req.setTools(parseTools(root.get("tools")));
        }
        if (root.has("tool_choice")) {
            req.setToolChoice(objectMapper.convertValue(root.get("tool_choice"), Object.class));
        }

        // Messages + System Prompt
        List<InternalMessage> messages = new ArrayList<>();
        StringBuilder systemBuilder = new StringBuilder();

        if (root.has("messages") && root.get("messages").isArray()) {
            for (JsonNode msgNode : root.get("messages")) {
                String role = msgNode.has("role") ? msgNode.get("role").asText() : "user";

                if ("system".equals(role)) {
                    // OpenAI 的 system 消息收集到 systemPrompt
                    if (systemBuilder.length() > 0) systemBuilder.append("\n");
                    systemBuilder.append(getMessageContent(msgNode));
                } else {
                    InternalMessage msg = new InternalMessage();
                    msg.setRole(role);
                    msg.setContent(getMessageContent(msgNode));

                    // Content parts (for multimodal)
                    if (msgNode.has("content") && msgNode.get("content").isArray()) {
                        msg.setContentParts(parseContentParts(msgNode.get("content")));
                    }

                    // Tool calls
                    if (msgNode.has("tool_calls")) {
                        msg.setToolCalls(parseToolCalls(msgNode.get("tool_calls")));
                    }

                    // Tool call ID
                    if (msgNode.has("tool_call_id")) {
                        msg.setToolCallId(msgNode.get("tool_call_id").asText());
                    }

                    // Name
                    if (msgNode.has("name")) {
                        msg.setName(msgNode.get("name").asText());
                    }

                    messages.add(msg);
                }
            }
        }

        req.setMessages(messages);
        if (systemBuilder.length() > 0) {
            req.setSystemPrompt(systemBuilder.toString());
        }

        return req;
    }

    /**
     * Anthropic Messages → InternalRequest
     */
    private InternalRequest parseAnthropicRequest(JsonNode root) {
        InternalRequest req = new InternalRequest();
        req.setClientApiFormat("anthropic");
        req.setOriginalRequestJson(root);

        if (root.has("model")) req.setModel(root.get("model").asText());
        if (root.has("stream")) req.setStream(root.get("stream").asBoolean());
        if (root.has("max_tokens") && !root.get("max_tokens").isNull())
            req.setMaxTokens(root.get("max_tokens").asInt());
        if (root.has("temperature") && !root.get("temperature").isNull())
            req.setTemperature(root.get("temperature").asDouble());
        if (root.has("top_p") && !root.get("top_p").isNull())
            req.setTopP(root.get("top_p").asDouble());

        // Stop sequences (Anthropic calls it stop_sequences)
        if (root.has("stop_sequences")) {
            List<String> stopList = new ArrayList<>();
            for (JsonNode s : root.get("stop_sequences")) stopList.add(s.asText());
            req.setStop(stopList);
        }

        // Tools
        if (root.has("tools")) {
            req.setTools(parseTools(root.get("tools")));
        }
        if (root.has("tool_choice")) {
            req.setToolChoice(objectMapper.convertValue(root.get("tool_choice"), Object.class));
        }

        // System prompt (Anthropic top-level field)
        if (root.has("system")) {
            JsonNode sysNode = root.get("system");
            if (sysNode.isTextual()) {
                req.setSystemPrompt(sysNode.asText());
            } else if (sysNode.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : sysNode) {
                    if (block.has("text")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(block.get("text").asText());
                    }
                }
                req.setSystemPrompt(sb.toString());
            }
        }

        // Messages
        List<InternalMessage> messages = new ArrayList<>();
        if (root.has("messages") && root.get("messages").isArray()) {
            for (JsonNode msgNode : root.get("messages")) {
                String role = msgNode.has("role") ? msgNode.get("role").asText() : "user";
                InternalMessage msg = new InternalMessage();
                msg.setRole(role);

                // Anthropic content can be string or array of content blocks
                if (msgNode.has("content")) {
                    JsonNode contentNode = msgNode.get("content");
                    if (contentNode.isTextual()) {
                        msg.setContent(contentNode.asText());
                    } else if (contentNode.isArray()) {
                        StringBuilder textBuilder = new StringBuilder();
                        List<Map<String, Object>> parts = new ArrayList<>();
                        for (JsonNode block : contentNode) {
                            String type = block.has("type") ? block.get("type").asText() : "text";
                            if ("text".equals(type) && block.has("text")) {
                                textBuilder.append(block.get("text").asText());
                            }
                            Map<String, Object> part = new HashMap<>();
                            part.put("type", type);
                            if (block.has("text")) part.put("text", block.get("text").asText());
                            if (block.has("source")) {
                                part.put("source", objectMapper.convertValue(block.get("source"), Map.class));
                            }
                            parts.add(part);
                        }
                        msg.setContent(textBuilder.toString());
                        msg.setContentParts(parts);
                    }
                }

                // Tool use content blocks
                if (msgNode.has("content") && msgNode.get("content").isArray()) {
                    List<Map<String, Object>> toolCalls = new ArrayList<>();
                    for (JsonNode block : msgNode.get("content")) {
                        String type = block.has("type") ? block.get("type").asText() : "";
                        if ("tool_use".equals(type)) {
                            Map<String, Object> tc = new HashMap<>();
                            tc.put("id", block.has("id") ? block.get("id").asText() : "");
                            tc.put("type", "function");
                            Map<String, Object> func = new HashMap<>();
                            if (block.has("name")) func.put("name", block.get("name").asText());
                            if (block.has("input")) func.put("arguments",
                                    block.get("input").isTextual() ? block.get("input").asText()
                                            : block.get("input").toString());
                            tc.put("function", func);
                            toolCalls.add(tc);
                        }
                        if ("tool_result".equals(type)) {
                            msg.setToolCallId(block.has("tool_use_id") ? block.get("tool_use_id").asText() : "");
                            if (block.has("content")) {
                                JsonNode tc = block.get("content");
                                if (tc.isTextual()) msg.setContent(tc.asText());
                                else if (tc.isArray() && tc.size() > 0 && tc.get(0).has("text")) {
                                    msg.setContent(tc.get(0).get("text").asText());
                                }
                            }
                        }
                    }
                    if (!toolCalls.isEmpty()) {
                        msg.setToolCalls(toolCalls);
                    }
                }

                messages.add(msg);
            }
        }

        req.setMessages(messages);
        return req;
    }

    // ========================================================================
    // 出站：内部格式 → 提供商请求 JSON
    // ========================================================================

    /**
     * 将内部请求转换为目标提供商格式的 JSON 请求体
     *
     * @param request     内部请求
     * @param channelType 上游渠道类型: "openai" 或 "anthropic"
     * @param upstreamModelName 上游模型名
     * @return 转换后的 JSON 字符串
     */
    public String toProviderRequest(InternalRequest request, String channelType, String upstreamModelName) {
        try {
            if ("anthropic".equals(channelType)) {
                return toAnthropicRequest(request, upstreamModelName);
            } else {
                return toOpenAiRequest(request, upstreamModelName);
            }
        } catch (Exception e) {
            log.error("转换请求失败 channelType={}", channelType, e);
            // 降级：只替换 model 名
            return fallbackRequest(request, upstreamModelName);
        }
    }

    /**
     * InternalRequest → OpenAI Chat Completion 格式
     */
    private String toOpenAiRequest(InternalRequest req, String upstreamModelName) throws JsonProcessingException {
        // 如果原始请求就是 OpenAI 格式，直接在原始 JSON 上改 model 名
        if ("openai".equals(req.getClientApiFormat()) && req.getOriginalRequestJson() != null) {
            ObjectNode modified = req.getOriginalRequestJson().deepCopy();
            modified.put("model", upstreamModelName);
            if (req.isStream() && !modified.has("stream_options")) {
                modified.putObject("stream_options").put("include_usage", true);
            }
            // 处理 system 字段：从 systemPrompt 注入
            if (req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()) {
                ensureOpenAiSystemMessage(modified, req.getSystemPrompt());
            }
            return objectMapper.writeValueAsString(modified);
        }

        // 从内部格式构造
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", upstreamModelName);
        if (req.getMaxTokens() != null) root.put("max_tokens", req.getMaxTokens());
        if (req.getTemperature() != null) root.put("temperature", req.getTemperature());
        if (req.getTopP() != null) root.put("top_p", req.getTopP());
        root.put("stream", req.isStream());
        if (req.isStream()) {
            root.putObject("stream_options").put("include_usage", true);
        }

        // Stop
        if (req.getStop() != null && !req.getStop().isEmpty()) {
            if (req.getStop().size() == 1) {
                root.put("stop", req.getStop().get(0));
            } else {
                ArrayNode stopArr = root.putArray("stop");
                req.getStop().forEach(stopArr::add);
            }
        }

        // Messages
        ArrayNode messagesArr = root.putArray("messages");

        // System prompt as first message
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()) {
            ObjectNode sysMsg = messagesArr.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", req.getSystemPrompt());
        }

        // Other messages
        if (req.getMessages() != null) {
            for (InternalMessage msg : req.getMessages()) {
                ObjectNode msgNode = messagesArr.addObject();
                msgNode.put("role", msg.getRole());

                // Content: use contentParts if available (multimodal)
                if (msg.getContentParts() != null && !msg.getContentParts().isEmpty()) {
                    ArrayNode contentArr = msgNode.putArray("content");
                    for (Map<String, Object> part : msg.getContentParts()) {
                        contentArr.add(objectMapper.valueToTree(part));
                    }
                } else if (msg.getContent() != null) {
                    msgNode.put("content", msg.getContent());
                } else {
                    msgNode.put("content", "");
                }

                // Tool calls
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    msgNode.set("tool_calls", objectMapper.valueToTree(msg.getToolCalls()));
                }

                // Tool call ID
                if (msg.getToolCallId() != null) {
                    msgNode.put("tool_call_id", msg.getToolCallId());
                }

                // Name
                if (msg.getName() != null) {
                    msgNode.put("name", msg.getName());
                }
            }
        }

        // Tools
        if (req.getTools() != null && !req.getTools().isEmpty()) {
            root.set("tools", objectMapper.valueToTree(req.getTools()));
        }
        if (req.getToolChoice() != null) {
            root.set("tool_choice", objectMapper.valueToTree(req.getToolChoice()));
        }

        // 思考强度（透传）
        if (req.getReasoningEffort() != null && !req.getReasoningEffort().isEmpty()) {
            root.put("reasoning_effort", req.getReasoningEffort());
        }

        return objectMapper.writeValueAsString(root);
    }

    /**
     * InternalRequest → Anthropic Messages 格式
     */
    private String toAnthropicRequest(InternalRequest req, String upstreamModelName) throws JsonProcessingException {
        // 如果原始请求就是 Anthropic 格式，直接在原始 JSON 上改 model 名
        if ("anthropic".equals(req.getClientApiFormat()) && req.getOriginalRequestJson() != null) {
            ObjectNode modified = req.getOriginalRequestJson().deepCopy();
            modified.put("model", upstreamModelName);
            // 确保 system 字段存在
            if (req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()) {
                modified.put("system", req.getSystemPrompt());
            }
            return objectMapper.writeValueAsString(modified);
        }

        // 从内部格式构造
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", upstreamModelName);
        if (req.getMaxTokens() != null) root.put("max_tokens", req.getMaxTokens());
        if (req.getTemperature() != null) root.put("temperature", req.getTemperature());
        if (req.getTopP() != null) root.put("top_p", req.getTopP());
        root.put("stream", req.isStream());

        // System prompt (Anthropic 顶层字段)
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()) {
            root.put("system", req.getSystemPrompt());
        }

        // Stop sequences
        if (req.getStop() != null && !req.getStop().isEmpty()) {
            ArrayNode stopArr = root.putArray("stop_sequences");
            req.getStop().forEach(stopArr::add);
        }

        // Tools
        if (req.getTools() != null && !req.getTools().isEmpty()) {
            root.set("tools", objectMapper.valueToTree(req.getTools()));
        }
        if (req.getToolChoice() != null) {
            root.set("tool_choice", objectMapper.valueToTree(req.getToolChoice()));
        }

        // Messages（Anthropic 的 messages 只有 user/assistant 角色，不能有 system）
        ArrayNode messagesArr = root.putArray("messages");
        if (req.getMessages() != null) {
            for (InternalMessage msg : req.getMessages()) {
                ObjectNode msgNode = messagesArr.addObject();
                String role = msg.getRole();
                // 跳过已转为顶层 system 的消息
                if ("system".equals(role)) continue;
                msgNode.put("role", role);

                // 构造 content (Anthropic 的 content 可以是数组)
                boolean hasToolCalls = msg.getToolCalls() != null && !msg.getToolCalls().isEmpty();
                boolean hasToolResult = msg.getToolCallId() != null;

                if (hasToolCalls || hasToolResult || (msg.getContentParts() != null && !msg.getContentParts().isEmpty())) {
                    ArrayNode contentArr = msgNode.putArray("content");

                    // Text content
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        ObjectNode textBlock = contentArr.addObject();
                        textBlock.put("type", "text");
                        textBlock.put("text", msg.getContent());
                    }

                    // Content parts
                    if (msg.getContentParts() != null) {
                        for (Map<String, Object> part : msg.getContentParts()) {
                            contentArr.add(objectMapper.valueToTree(part));
                        }
                    }

                    // Tool use blocks
                    if (hasToolCalls) {
                        for (Map<String, Object> tc : msg.getToolCalls()) {
                            ObjectNode toolUseBlock = contentArr.addObject();
                            toolUseBlock.put("type", "tool_use");
                            toolUseBlock.put("id", (String) tc.getOrDefault("id", ""));
                            toolUseBlock.put("name", tc.containsKey("function")
                                    ? (String) ((Map<String, Object>) tc.get("function")).getOrDefault("name", "")
                                    : "");
                            Map<String, Object> func = (Map<String, Object>) tc.get("function");
                            if (func != null && func.containsKey("arguments")) {
                                try {
                                    String args = (String) func.get("arguments");
                                    toolUseBlock.set("input", objectMapper.readTree(args));
                                } catch (Exception e) {
                                    toolUseBlock.put("input", (String) func.get("arguments"));
                                }
                            }
                        }
                    }

                    // Tool result block
                    if (hasToolResult) {
                        ObjectNode resultBlock = contentArr.addObject();
                        resultBlock.put("type", "tool_result");
                        resultBlock.put("tool_use_id", msg.getToolCallId());
                        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                            ObjectNode textBlock = resultBlock.putArray("content").addObject();
                            textBlock.put("type", "text");
                            textBlock.put("text", msg.getContent());
                        }
                    }
                } else {
                    msgNode.put("content", msg.getContent() != null ? msg.getContent() : "");
                }
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    // ========================================================================
    // 出站响应：提供商响应 → 客户端格式
    // ========================================================================

    /**
     * 将上游响应转换为客户端期望的格式
     *
     * @param providerResponseBody 上游响应体
     * @param providerType 上游类型: "openai" 或 "anthropic"
     * @param clientApiType 客户端类型: "openai" 或 "anthropic"
     * @return 转换后的响应体
     */
    public String toClientResponse(String providerResponseBody, String providerType, String clientApiType) {
        try {
            // 如果格式一致，直接返回
            if (providerType.equals(clientApiType)) {
                return providerResponseBody;
            }

            InternalResponse internal = parseProviderResponse(providerResponseBody, providerType);
            return internalToClientFormat(internal, clientApiType);

        } catch (Exception e) {
            log.warn("响应格式转换失败, 返回原始响应: {}", e.getMessage());
            return providerResponseBody;
        }
    }

    /**
     * 解析提供商响应为内部格式
     */
    private InternalResponse parseProviderResponse(String responseBody, String providerType) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if ("anthropic".equals(providerType)) {
                return parseAnthropicResponse(root);
            } else {
                return parseOpenAiResponse(root);
            }
        } catch (Exception e) {
            throw new RuntimeException("解析响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * OpenAI Chat Completion Response → InternalResponse
     */
    private InternalResponse parseOpenAiResponse(JsonNode root) {
        InternalResponse resp = new InternalResponse();
        resp.setOriginalResponseJson(root);

        if (root.has("id")) resp.setId(root.get("id").asText());
        if (root.has("model")) resp.setModel(root.get("model").asText());

        // Content
        List<InternalResponse.InternalResponseContent> contents = new ArrayList<>();
        if (root.has("choices") && root.get("choices").isArray()) {
            for (JsonNode choice : root.get("choices")) {
                JsonNode message = choice.get("message");
                if (message != null) {
                    // Text content
                    if (message.has("content") && !message.get("content").isNull()) {
                        InternalResponse.InternalResponseContent c = new InternalResponse.InternalResponseContent();
                        c.setType("text");
                        c.setText(message.get("content").asText());
                        contents.add(c);
                    }

                    // Tool calls
                    if (message.has("tool_calls")) {
                        for (JsonNode tc : message.get("tool_calls")) {
                            InternalResponse.InternalResponseContent c = new InternalResponse.InternalResponseContent();
                            c.setType("tool_use");
                            c.setId(tc.has("id") ? tc.get("id").asText() : "");
                            if (tc.has("function")) {
                                c.setName(tc.get("function").has("name") ? tc.get("function").get("name").asText() : "");
                                c.setInput(tc.get("function").has("arguments")
                                        ? tc.get("function").get("arguments").asText() : "");
                            }
                            contents.add(c);
                        }
                    }
                }

                // Stop reason
                if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                    resp.setStopReason(choice.get("finish_reason").asText());
                }
            }
        }
        resp.setContents(contents);

        // Usage
        if (root.has("usage")) {
            InternalResponse.InternalUsage usage = new InternalResponse.InternalUsage();
            JsonNode usageNode = root.get("usage");
            if (usageNode.has("prompt_tokens")) usage.setPromptTokens(usageNode.get("prompt_tokens").asInt());
            if (usageNode.has("completion_tokens")) usage.setCompletionTokens(usageNode.get("completion_tokens").asInt());
            if (usageNode.has("total_tokens")) usage.setTotalTokens(usageNode.get("total_tokens").asInt());
            resp.setUsage(usage);
        }

        return resp;
    }

    /**
     * Anthropic Messages Response → InternalResponse
     */
    private InternalResponse parseAnthropicResponse(JsonNode root) {
        InternalResponse resp = new InternalResponse();
        resp.setOriginalResponseJson(root);

        if (root.has("id")) resp.setId(root.get("id").asText());
        if (root.has("model")) resp.setModel(root.get("model").asText());

        // Stop reason mapping
        if (root.has("stop_reason")) {
            String reason = root.get("stop_reason").asText();
            resp.setStopReason(switch (reason) {
                case "end_turn" -> "stop";
                case "max_tokens" -> "length";
                case "tool_use" -> "tool_calls";
                default -> reason;
            });
        }

        // Content blocks
        List<InternalResponse.InternalResponseContent> contents = new ArrayList<>();
        if (root.has("content") && root.get("content").isArray()) {
            for (JsonNode block : root.get("content")) {
                String type = block.has("type") ? block.get("type").asText() : "text";
                InternalResponse.InternalResponseContent c = new InternalResponse.InternalResponseContent();
                c.setType(type);

                switch (type) {
                    case "text":
                        c.setText(block.has("text") ? block.get("text").asText() : "");
                        break;
                    case "tool_use":
                        c.setId(block.has("id") ? block.get("id").asText() : "");
                        c.setName(block.has("name") ? block.get("name").asText() : "");
                        if (block.has("input")) {
                            c.setInput(block.get("input").toString());
                        }
                        break;
                }
                contents.add(c);
            }
        }
        resp.setContents(contents);

        // Usage
        if (root.has("usage")) {
            InternalResponse.InternalUsage usage = new InternalResponse.InternalUsage();
            JsonNode usageNode = root.get("usage");
            if (usageNode.has("input_tokens")) usage.setPromptTokens(usageNode.get("input_tokens").asInt());
            if (usageNode.has("output_tokens")) usage.setCompletionTokens(usageNode.get("output_tokens").asInt());
            usage.setTotalTokens(usage.getPromptTokens() + usage.getCompletionTokens());
            resp.setUsage(usage);
        }

        return resp;
    }

    /**
     * InternalResponse → 客户端格式
     */
    private String internalToClientFormat(InternalResponse resp, String clientApiType) {
        try {
            if ("anthropic".equals(clientApiType)) {
                return toAnthropicResponse(resp);
            } else {
                return toOpenAiResponse(resp);
            }
        } catch (Exception e) {
            throw new RuntimeException("转换响应格式失败: " + e.getMessage(), e);
        }
    }

    /**
     * InternalResponse → OpenAI Chat Completion Response
     */
    private String toOpenAiResponse(InternalResponse resp) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", resp.getId() != null ? resp.getId() : "chatcmpl-" + System.currentTimeMillis());
        root.put("object", "chat.completion");
        root.put("created", System.currentTimeMillis() / 1000);
        root.put("model", resp.getModel() != null ? resp.getModel() : "");

        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);

        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");

        // Build content from text blocks
        StringBuilder textContent = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        if (resp.getContents() != null) {
            for (InternalResponse.InternalResponseContent c : resp.getContents()) {
                if ("text".equals(c.getType()) && c.getText() != null) {
                    if (textContent.length() > 0) textContent.append("\n");
                    textContent.append(c.getText());
                } else if ("tool_use".equals(c.getType())) {
                    Map<String, Object> tc = new HashMap<>();
                    tc.put("id", c.getId());
                    tc.put("type", "function");
                    Map<String, Object> func = new HashMap<>();
                    func.put("name", c.getName());
                    func.put("arguments", c.getInput() != null ? c.getInput().toString() : "{}");
                    tc.put("function", func);
                    toolCalls.add(tc);
                }
            }
        }

        if (textContent.length() > 0) {
            message.put("content", textContent.toString());
        } else {
            message.putNull("content");
        }

        if (!toolCalls.isEmpty()) {
            message.set("tool_calls", objectMapper.valueToTree(toolCalls));
        }

        // Finish reason
        String finishReason = resp.getStopReason() != null ? resp.getStopReason() : "stop";
        choice.put("finish_reason", finishReason);

        // Usage
        if (resp.getUsage() != null) {
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", resp.getUsage().getPromptTokens());
            usage.put("completion_tokens", resp.getUsage().getCompletionTokens());
            usage.put("total_tokens", resp.getUsage().getTotalTokens());
        }

        return objectMapper.writeValueAsString(root);
    }

    /**
     * InternalResponse → Anthropic Messages Response
     */
    private String toAnthropicResponse(InternalResponse resp) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", resp.getId() != null ? resp.getId() : "msg_" + System.currentTimeMillis());
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", resp.getModel() != null ? resp.getModel() : "");

        // Content array
        ArrayNode contentArr = root.putArray("content");
        if (resp.getContents() != null) {
            for (InternalResponse.InternalResponseContent c : resp.getContents()) {
                if ("text".equals(c.getType())) {
                    ObjectNode textBlock = contentArr.addObject();
                    textBlock.put("type", "text");
                    textBlock.put("text", c.getText() != null ? c.getText() : "");
                } else if ("tool_use".equals(c.getType())) {
                    ObjectNode toolBlock = contentArr.addObject();
                    toolBlock.put("type", "tool_use");
                    toolBlock.put("id", c.getId() != null ? c.getId() : "");
                    toolBlock.put("name", c.getName() != null ? c.getName() : "");
                    try {
                        if (c.getInput() instanceof String) {
                            toolBlock.set("input", objectMapper.readTree((String) c.getInput()));
                        } else {
                            toolBlock.set("input", objectMapper.valueToTree(c.getInput()));
                        }
                    } catch (Exception e) {
                        toolBlock.put("input", c.getInput() != null ? c.getInput().toString() : "{}");
                    }
                }
            }
        }
        // 如果 contents 为空，加一个默认 text block
        if (contentArr.size() == 0) {
            ObjectNode textBlock = contentArr.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", "");
        }

        // Stop reason
        String stopReason = resp.getStopReason() != null ? resp.getStopReason() : "end_turn";
        root.put("stop_reason", switch (stopReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            default -> stopReason;
        });
        root.put("stop_sequence", (com.fasterxml.jackson.databind.node.NullNode) null);

        // Usage
        if (resp.getUsage() != null) {
            ObjectNode usage = root.putObject("usage");
            usage.put("input_tokens", resp.getUsage().getPromptTokens());
            usage.put("output_tokens", resp.getUsage().getCompletionTokens());
        }

        return objectMapper.writeValueAsString(root);
    }

    // ========================================================================
    // 流式 SSE 转换
    // ========================================================================

    /**
     * 将 OpenAI 流式 SSE 事件行转换为 Anthropic 格式
     */
    public String convertSseEventToClient(String rawEvent, String providerType, String clientApiType) {
        if (providerType.equals(clientApiType)) {
            return rawEvent; // 同格式，透传
        }

        // 这里只做基础 header 的转换，完整流式聚合在 StreamingHandler 中处理
        return rawEvent;
    }

    /**
     * OpenAI SSE 事件 → 统一格式事件
     */
    public String normalizeSseEvent(String eventLine, String providerType) {
        // 实现由 StreamingHandler 完成，这里保留扩展点
        return eventLine;
    }

    // ========================================================================
    // 流式响应聚合（用于非流式请求）
    // ========================================================================

    /**
     * 将流式事件列表聚合为完整的响应体
     */
    public String aggregateStreamChunk(String clientApiType, String model,
                                       String aggregatedContent, String stopReason,
                                       InternalResponse.InternalUsage usage) {
        InternalResponse resp = new InternalResponse();
        resp.setId("chatcmpl-" + System.currentTimeMillis());
        resp.setModel(model);
        resp.setStopReason(stopReason);
        resp.setUsage(usage);

        List<InternalResponse.InternalResponseContent> contents = new ArrayList<>();
        InternalResponse.InternalResponseContent c = new InternalResponse.InternalResponseContent();
        c.setType("text");
        c.setText(aggregatedContent);
        contents.add(c);
        resp.setContents(contents);

        try {
            return internalToClientFormat(resp, clientApiType);
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"聚合流式响应失败\"}}";
        }
    }

    // ========================================================================
    // 错误响应生成
    // ========================================================================

    /**
     * 根据客户端类型生成对应格式的错误响应
     */
    public String buildErrorResponse(String clientApiType, String message, int statusCode) {
        try {
            if ("anthropic".equals(clientApiType)) {
                ObjectNode errorJson = objectMapper.createObjectNode();
                ObjectNode error = errorJson.putObject("error");
                error.put("type", "api_error");
                error.put("message", message);
                return objectMapper.writeValueAsString(errorJson);
            } else {
                ObjectNode errorJson = objectMapper.createObjectNode();
                ObjectNode error = errorJson.putObject("error");
                error.put("message", message);
                error.put("type", "api_error");
                error.put("code", String.valueOf(statusCode));
                return objectMapper.writeValueAsString(errorJson);
            }
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"" + message + "\"}}";
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 获取消息的文本内容（兼容 string/array 格式）
     */
    private String getMessageContent(JsonNode msgNode) {
        if (!msgNode.has("content")) return "";
        JsonNode content = msgNode.get("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.has("text")) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    /**
     * 解析 OpenAI 多媒体 content parts
     */
    private List<Map<String, Object>> parseContentParts(JsonNode contentArr) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (JsonNode part : contentArr) {
            Map<String, Object> map = new HashMap<>();
            if (part.has("type")) map.put("type", part.get("type").asText());
            if (part.has("text")) map.put("text", part.get("text").asText());
            if (part.has("image_url")) {
                map.put("source", Map.of(
                        "type", "base64",
                        "media_type", part.get("image_url").has("detail") ? "image/jpeg" : "image/png",
                        "data", part.get("image_url").has("url") ? part.get("image_url").get("url").asText() : ""
                ));
            }
            parts.add(map);
        }
        return parts;
    }

    /**
     * 解析工具定义
     */
    private List<Map<String, Object>> parseTools(JsonNode toolsNode) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            tools.add(objectMapper.convertValue(tool, Map.class));
        }
        return tools;
    }

    /**
     * 解析工具调用
     */
    private List<Map<String, Object>> parseToolCalls(JsonNode tcNode) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (JsonNode tc : tcNode) {
            calls.add(objectMapper.convertValue(tc, Map.class));
        }
        return calls;
    }

    /**
     * 确保 OpenAI 请求体中有 system 消息
     */
    private void ensureOpenAiSystemMessage(ObjectNode root, String systemPrompt) {
        if (!root.has("messages") || !root.get("messages").isArray()) return;
        ArrayNode messages = (ArrayNode) root.get("messages");

        // 检查是否已有 system 消息
        boolean hasSystem = false;
        for (JsonNode msg : messages) {
            if (msg.has("role") && "system".equals(msg.get("role").asText())) {
                hasSystem = true;
                break;
            }
        }

        // 如果没有，在最前面插入
        if (!hasSystem) {
            ObjectNode sysMsg = messages.insertObject(0);
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
        }
    }

    /**
     * 降级：只替换 model 名
     */
    private String fallbackRequest(InternalRequest request, String upstreamModelName) {
        if (request.getOriginalRequestJson() != null) {
            ObjectNode modified = request.getOriginalRequestJson().deepCopy();
            modified.put("model", upstreamModelName);
            try {
                return objectMapper.writeValueAsString(modified);
            } catch (JsonProcessingException e) {
                return modified.toString();
            }
        }
        return "{\"model\":\"" + upstreamModelName + "\"}";
    }
}
