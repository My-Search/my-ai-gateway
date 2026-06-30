package com.myai.gateway.relay.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.relay.transformer.registry.ProtocolTranslator;
import com.myai.gateway.relay.transformer.registry.TranslatorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * OpenAI ↔ Anthropic 消息格式转换器（Facade）。
 *
 * <p>作为统一入口，兼容旧的直接方法调用方式。
 * 内部委托给 {@link TranslatorRegistry} 中的方向性翻译器做实际转换。
 *
 * <p>职责：
 * <ol>
 *   <li>将客户端请求解析为 InternalRequest（统一内部格式）</li>
 *   <li>将 InternalRequest 转换为目标渠道格式（OpenAI / Anthropic）</li>
 *   <li>将上游响应通过 {@link TranslatorRegistry} 转换回客户端格式</li>
 *   <li>流式 SSE 事件转换通过本类的无状态方法或直接使用 registry + 状态管理</li>
 * </ol>
 */
@Component
public class MessageTransformer {

    private static final Logger log = LoggerFactory.getLogger(MessageTransformer.class);

    private final ObjectMapper objectMapper;
    private final TranslatorRegistry translatorRegistry;

    public MessageTransformer(ObjectMapper objectMapper, TranslatorRegistry translatorRegistry) {
        this.objectMapper = objectMapper;
        this.translatorRegistry = translatorRegistry;
    }

    // ==================== 请求解析 ====================

    public InternalRequest parseOpenAiRequest(JsonNode json) {
        InternalRequest req = new InternalRequest();
        req.setClientApiFormat("openai");
        req.setOriginalRequestJson(json);

        req.setModel(json.has("model") ? json.get("model").asText() : "");
        req.setStream(json.has("stream") && json.get("stream").asBoolean());
        if (json.has("temperature")) req.setTemperature(json.get("temperature").asDouble());
        if (json.has("top_p")) req.setTopP(json.get("top_p").asDouble());
        if (json.has("max_tokens")) req.setMaxTokens(json.get("max_tokens").asInt());
        if (json.has("max_completion_tokens")) req.setMaxTokens(json.get("max_completion_tokens").asInt());
        if (json.has("tool_choice")) req.setToolChoice(objectMapper.convertValue(json.get("tool_choice"), Object.class));

        if (json.has("stop")) {
            JsonNode stopNode = json.get("stop");
            List<String> stop = new ArrayList<>();
            if (stopNode.isArray()) {
                stopNode.forEach(s -> stop.add(s.asText()));
            } else {
                stop.add(stopNode.asText());
            }
            req.setStop(stop);
        }

        List<InternalMessage> messages = new ArrayList<>();
        if (json.has("messages") && json.get("messages").isArray()) {
            for (JsonNode msg : json.get("messages")) {
                InternalMessage im = new InternalMessage();
                im.setRole(msg.get("role").asText());
                JsonNode contentNode = msg.get("content");
                if (contentNode != null) {
                    if (contentNode.isTextual()) {
                        im.setContent(contentNode.asText());
                    } else if (contentNode.isArray()) {
                        im.setContentParts(objectMapper.convertValue(contentNode, List.class));
                    }
                }
                if (msg.has("tool_calls")) {
                    im.setToolCalls(objectMapper.convertValue(msg.get("tool_calls"), List.class));
                }
                if (msg.has("tool_call_id")) {
                    im.setToolCallId(msg.get("tool_call_id").asText());
                }
                if (msg.has("name")) {
                    im.setName(msg.get("name").asText());
                }
                // DeepSeek thinking mode：assistant 角色的 reasoning_content 必须传回
                if ("assistant".equals(im.getRole()) && msg.has("reasoning_content")) {
                    im.setReasoningContent(msg.get("reasoning_content").asText());
                }
                messages.add(im);
            }
        }
        req.setMessages(messages);

        if (json.has("tools") && json.get("tools").isArray()) {
            req.setTools(objectMapper.convertValue(json.get("tools"), List.class));
        }
        if (json.has("reasoning_effort")) {
            req.setReasoningEffort(json.get("reasoning_effort").asText());
        }
        return req;
    }

    public InternalRequest parseAnthropicRequest(JsonNode json) {
        InternalRequest req = new InternalRequest();
        req.setClientApiFormat("anthropic");
        req.setOriginalRequestJson(json);

        req.setModel(json.has("model") ? json.get("model").asText() : "");
        req.setStream(json.has("stream") && json.get("stream").asBoolean());
        if (json.has("max_tokens")) req.setMaxTokens(json.get("max_tokens").asInt());
        if (json.has("temperature")) req.setTemperature(json.get("temperature").asDouble());
        if (json.has("top_p")) req.setTopP(json.get("top_p").asDouble());

        if (json.has("system")) {
            JsonNode systemNode = json.get("system");
            if (systemNode.isTextual()) {
                req.setSystemPrompt(systemNode.asText());
            } else if (systemNode.isArray()) {
                StringBuilder sb = new StringBuilder();
                systemNode.forEach(block -> {
                    if (block.has("text")) sb.append(block.get("text").asText());
                });
                req.setSystemPrompt(sb.toString());
            }
        }

        if (json.has("stop_sequences")) {
            List<String> stop = new ArrayList<>();
            json.get("stop_sequences").forEach(s -> stop.add(s.asText()));
            req.setStop(stop);
        }

        List<InternalMessage> messages = new ArrayList<>();
        if (json.has("messages") && json.get("messages").isArray()) {
            for (JsonNode msg : json.get("messages")) {
                InternalMessage im = new InternalMessage();
                im.setRole(msg.get("role").asText());

                JsonNode contentNode = msg.get("content");
                if (contentNode != null) {
                    if (contentNode.isTextual()) {
                        im.setContent(contentNode.asText());
                    } else if (contentNode.isArray()) {
                        im.setContentParts(objectMapper.convertValue(contentNode, List.class));
                    }
                }

                if (msg.has("content") && msg.get("content").isArray()) {
                    List<Map<String, Object>> toolCalls = new ArrayList<>();
                    for (JsonNode block : msg.get("content")) {
                        if ("tool_use".equals(block.has("type") ? block.get("type").asText() : null)) {
                            Map<String, Object> tc = new LinkedHashMap<>();
                            tc.put("id", block.get("id").asText());
                            tc.put("type", "function");
                            Map<String, Object> func = new LinkedHashMap<>();
                            func.put("name", block.get("name").asText());
                            func.put("arguments", block.has("input") ?
                                    objectMapper.convertValue(block.get("input"), String.class) : "{}");
                            tc.put("function", func);
                            toolCalls.add(tc);
                        }
                    }
                    if (!toolCalls.isEmpty()) {
                        im.setToolCalls(toolCalls);
                    }
                }

                if ("tool".equals(im.getRole()) ||
                    (msg.has("content") && msg.get("content").isArray() &&
                     hasToolResultBlock(msg.get("content")))) {
                    for (JsonNode block : msg.get("content")) {
                        if ("tool_result".equals(block.has("type") ? block.get("type").asText() : null)) {
                            im.setToolCallId(block.has("tool_use_id") ? block.get("tool_use_id").asText() : "");
                            if (block.has("content")) {
                                JsonNode resultContent = block.get("content");
                                im.setContent(resultContent.isTextual() ? resultContent.asText() : resultContent.toString());
                            }
                            im.setRole("tool");
                            break;
                        }
                    }
                }
                messages.add(im);
            }
        }
        req.setMessages(messages);

        if (json.has("reasoning_effort")) {
            req.setReasoningEffort(json.get("reasoning_effort").asText());
        }

        if (json.has("tools") && json.get("tools").isArray()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (JsonNode tool : json.get("tools")) {
                Map<String, Object> openAiTool = new LinkedHashMap<>();
                openAiTool.put("type", "function");
                Map<String, Object> func = new LinkedHashMap<>();
                func.put("name", tool.get("name").asText());
                if (tool.has("description")) func.put("description", tool.get("description").asText());
                if (tool.has("input_schema")) func.put("parameters", objectMapper.convertValue(tool.get("input_schema"), Object.class));
                openAiTool.put("function", func);
                tools.add(openAiTool);
            }
            req.setTools(tools);
        }

        if (json.has("tool_choice")) {
            req.setToolChoice(objectMapper.convertValue(json.get("tool_choice"), Object.class));
        }
        return req;
    }

    // ==================== 请求构建 ====================

    public String buildOpenAiRequest(InternalRequest req) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", req.getModel());

        ArrayNode messagesNode = root.putArray("messages");
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()) {
            ObjectNode sysMsg = messagesNode.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", req.getSystemPrompt());
        }
        for (InternalMessage msg : req.getMessages()) {
            ObjectNode msgNode = messagesNode.addObject();
            msgNode.put("role", msg.getRole());
            if (msg.getContent() != null) {
                msgNode.put("content", msg.getContent());
            } else if (msg.getContentParts() != null) {
                ArrayNode contentArray = msgNode.putArray("content");
                for (Map<String, Object> part : msg.getContentParts()) {
                    ObjectNode partNode = contentArray.addObject();
                    String type = (String) part.get("type");
                    if ("text".equals(type)) {
                        partNode.put("type", "text");
                        partNode.put("text", (String) part.get("text"));
                    } else if ("image_url".equals(type)) {
                        partNode.put("type", "image_url");
                        partNode.set("image_url", objectMapper.valueToTree(part.get("image_url")));
                    } else if ("image".equals(type)) {
                        partNode.put("type", "image_url");
                        ObjectNode imageUrl = partNode.putObject("image_url");
                        ObjectNode source = objectMapper.valueToTree(part.get("source"));
                        if (source != null) {
                            String mediaType = source.has("media_type") ? source.get("media_type").asText() : "image/jpeg";
                            String data = source.has("data") ? source.get("data").asText() : "";
                            imageUrl.put("url", "data:" + mediaType + ";base64," + data);
                        }
                    } else {
                        partNode.setAll((ObjectNode) objectMapper.valueToTree(part));
                    }
                }
            }
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                msgNode.set("tool_calls", objectMapper.valueToTree(msg.getToolCalls()));
            }
            if (msg.getToolCallId() != null) {
                msgNode.put("tool_call_id", msg.getToolCallId());
            }
            if (msg.getName() != null) {
                msgNode.put("name", msg.getName());
            }
            // DeepSeek thinking mode：assistant 角色的 reasoning_content 必须传回
            if ("assistant".equals(msg.getRole()) && msg.getReasoningContent() != null) {
                msgNode.put("reasoning_content", msg.getReasoningContent());
            }
        }

        if (req.getTemperature() != null) root.put("temperature", req.getTemperature());
        if (req.getTopP() != null) root.put("top_p", req.getTopP());
        if (req.getMaxTokens() != null) root.put("max_tokens", req.getMaxTokens());
        if (req.isStream()) {
            root.put("stream", true);
            root.putObject("stream_options").put("include_usage", true);
        }
        if (req.getStop() != null && !req.getStop().isEmpty()) {
            if (req.getStop().size() == 1) {
                root.put("stop", req.getStop().get(0));
            } else {
                root.set("stop", objectMapper.valueToTree(req.getStop()));
            }
        }

        if (req.getTools() != null && !req.getTools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (Map<String, Object> tool : req.getTools()) {
                if ("function".equals(tool.get("type"))) {
                    // 标准 OpenAI 格式：透传保留所有字段（如 strict 等扩展属性）
                    JsonNode added = toolsNode.add(objectMapper.valueToTree(tool));
                    // 对非标准格式进行修补以兼容不同提供商
                    if (added instanceof ObjectNode obj) {
                        patchToolForCompatibility(obj);
                    }
                } else {
                    // 非 function 类型（如 Anthropic 格式），手动构建为标准 OpenAI 格式
                    ObjectNode toolNode = toolsNode.addObject();
                    toolNode.put("type", "function");
                    ObjectNode funcNode = toolNode.putObject("function");
                    funcNode.put("name", (String) tool.get("name"));
                    if (tool.containsKey("description")) funcNode.put("description", (String) tool.get("description"));
                    if (tool.containsKey("input_schema")) funcNode.set("parameters", objectMapper.valueToTree(tool.get("input_schema")));
                }
            }
        }
        if (req.getToolChoice() != null) {
            root.set("tool_choice", objectMapper.valueToTree(req.getToolChoice()));
        }
        if (req.getReasoningEffort() != null && !req.getReasoningEffort().isEmpty()) {
            root.put("reasoning_effort", req.getReasoningEffort());
        }
        if (req.getExtraParams() != null) {
            for (Map.Entry<String, Object> entry : req.getExtraParams().entrySet()) {
                root.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
            }
        }

        try { return objectMapper.writeValueAsString(root); }
        catch (Exception e) { log.error("构建 OpenAI 请求失败", e); return req.getOriginalRequestJson().toString(); }
    }

    public String buildAnthropicRequest(InternalRequest req) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", req.getModel());
        if (req.getMaxTokens() != null) { root.put("max_tokens", req.getMaxTokens()); }
        else { root.put("max_tokens", 4096); }

        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()) {
            root.put("system", req.getSystemPrompt());
        }

        ArrayNode messagesNode = root.putArray("messages");
        for (InternalMessage msg : req.getMessages()) {
            String role = msg.getRole();
            if ("system".equals(role)) {
                if (req.getSystemPrompt() == null || req.getSystemPrompt().isEmpty()) {
                    root.put("system", msg.getContent());
                }
                continue;
            }
            ObjectNode msgNode = messagesNode.addObject();
            if ("tool".equals(role)) {
                msgNode.put("role", "user");
                ArrayNode contentArr = msgNode.putArray("content");
                ObjectNode toolResult = contentArr.addObject();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", msg.getToolCallId() != null ? msg.getToolCallId() : "");
                toolResult.put("content", msg.getContent() != null ? msg.getContent() : "");
                continue;
            }
            msgNode.put("role", role);
            if (msg.getContent() != null && msg.getToolCalls() == null) {
                msgNode.put("content", msg.getContent());
            } else if (msg.getContentParts() != null && msg.getToolCalls() == null) {
                ArrayNode contentArray = msgNode.putArray("content");
                for (Map<String, Object> part : msg.getContentParts()) {
                    ObjectNode partNode = contentArray.addObject();
                    String type = (String) part.get("type");
                    if ("text".equals(type)) {
                        partNode.put("type", "text");
                        partNode.put("text", (String) part.get("text"));
                    } else if ("image_url".equals(type)) {
                        partNode.put("type", "image");
                        ObjectNode source = partNode.putObject("source");
                        source.put("type", "base64");
                        String url = part.containsKey("image_url") ?
                                String.valueOf(((Map<?, ?>) part.get("image_url")).get("url")) : "";
                        if (url.startsWith("data:")) {
                            String[] parts = url.split(";base64,", 2);
                            source.put("media_type", parts[0].replace("data:", ""));
                            source.put("data", parts.length > 1 ? parts[1] : "");
                        } else {
                            source.put("media_type", "image/jpeg");
                            source.put("data", url);
                        }
                    } else {
                        partNode.setAll((ObjectNode) objectMapper.valueToTree(part));
                    }
                }
            }
            if ("assistant".equals(role) && msg.getToolCalls() != null) {
                ArrayNode contentArr = msgNode.putArray("content");
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    ObjectNode textBlock = contentArr.addObject();
                    textBlock.put("type", "text");
                    textBlock.put("text", msg.getContent());
                }
                for (Map<String, Object> tc : msg.getToolCalls()) {
                    ObjectNode toolUse = contentArr.addObject();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", (String) tc.get("id"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> func = (Map<String, Object>) tc.get("function");
                    if (func != null) {
                        toolUse.put("name", (String) func.get("name"));
                        String args = (String) func.get("arguments");
                        try { toolUse.set("input", objectMapper.readTree(args)); }
                        catch (Exception e) { toolUse.putObject("input"); }
                    }
                }
            }
            if (!"assistant".equals(role) && msg.getToolCalls() != null && msg.getContent() == null) {
                msgNode.set("content", objectMapper.valueToTree(msg.getContentParts()));
            }
        }

        if (req.getTemperature() != null) root.put("temperature", req.getTemperature());
        if (req.getTopP() != null) root.put("top_p", req.getTopP());
        if (req.isStream()) root.put("stream", true);
        if (req.getStop() != null && !req.getStop().isEmpty()) {
            root.set("stop_sequences", objectMapper.valueToTree(req.getStop()));
        }
        if (req.getTools() != null && !req.getTools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (Map<String, Object> tool : req.getTools()) {
                ObjectNode toolNode = toolsNode.addObject();
                if ("function".equals(tool.get("type"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> func = (Map<String, Object>) tool.get("function");
                    if (func != null) {
                        toolNode.put("name", (String) func.get("name"));
                        if (func.containsKey("description")) toolNode.put("description", (String) func.get("description"));
                        if (func.containsKey("parameters")) {
                            toolNode.set("input_schema", objectMapper.valueToTree(func.get("parameters")));
                        }
                    }
                } else {
                    toolNode.put("name", (String) tool.get("name"));
                    if (tool.containsKey("description")) toolNode.put("description", (String) tool.get("description"));
                    if (tool.containsKey("input_schema")) {
                        toolNode.set("input_schema", objectMapper.valueToTree(tool.get("input_schema")));
                    }
                }
            }
        }
        if (req.getReasoningEffort() != null && !req.getReasoningEffort().isEmpty()) {
            root.put("reasoning_effort", req.getReasoningEffort());
        }
        if (req.getToolChoice() != null) {
            root.set("tool_choice", objectMapper.valueToTree(req.getToolChoice()));
        }

        try { return objectMapper.writeValueAsString(root); }
        catch (Exception e) { log.error("构建 Anthropic 请求失败", e); return req.getOriginalRequestJson().toString(); }
    }

    // ==================== 响应转换（委托人注册表） ====================

    /**
     * 将上游 OpenAI 响应转换为客户端格式。
     * 委托给 {@link TranslatorRegistry} 中对应的方向性翻译器。
     */
    public String transformOpenAiResponseToClient(JsonNode openAiResp, String clientFormat, String originalModel) {
        if ("openai".equals(clientFormat)) {
            return replaceModelInJson(openAiResp, originalModel);
        }
        // openai → anthropic
        ProtocolTranslator translator = translatorRegistry.find("openai", "anthropic");
        if (translator != null) {
            return translator.translateResponse(openAiResp, originalModel);
        }
        log.warn("未找到 openai→anthropic 翻译器，降级为 model 替换");
        return replaceModelInJson(openAiResp, originalModel);
    }

    /**
     * 将上游 Anthropic 响应转换为客户端格式。
     * 委托给 {@link TranslatorRegistry} 中对应的方向性翻译器。
     */
    public String transformAnthropicResponseToClient(JsonNode anthropicResp, String clientFormat, String originalModel) {
        if ("anthropic".equals(clientFormat)) {
            return replaceModelInJson(anthropicResp, originalModel);
        }
        // anthropic → openai
        ProtocolTranslator translator = translatorRegistry.find("anthropic", "openai");
        if (translator != null) {
            return translator.translateResponse(anthropicResp, originalModel);
        }
        log.warn("未找到 anthropic→openai 翻译器，降级为 model 替换");
        return replaceModelInJson(anthropicResp, originalModel);
    }

    // ==================== 流式事件转换（无状态版本，向后兼容） ====================

    /**
     * 转换 OpenAI SSE 事件到客户端格式（无状态版本）。
     *
     * <p>注意：此方法为向后兼容保留。推荐使用 {@link TranslatorRegistry} 配合
     * {@link com.myai.gateway.relay.transformer.registry.StreamTranslateState} 的有状态方式。
     */
    public String transformOpenAiStreamEvent(String eventData, String clientFormat, String originalModel) {
        if ("[DONE]".equals(eventData)) return "[DONE]";
        if ("openai".equals(clientFormat)) {
            return replaceModelInRawJson(eventData, originalModel);
        }
        // openai → anthropic
        ProtocolTranslator translator = translatorRegistry.find("openai", "anthropic");
        if (translator != null) {
            return translator.translateStreamEvent(null, eventData, originalModel,
                    translator.createStreamState());
        }
        log.warn("未找到 openai→anthropic 流式翻译器，降级为 model 替换");
        return replaceModelInRawJson(eventData, originalModel);
    }

    /**
     * 转换 Anthropic SSE 事件到客户端格式（无状态版本）。
     *
     * <p>注意：此方法为向后兼容保留。推荐使用 {@link TranslatorRegistry} 配合
     * {@link com.myai.gateway.relay.transformer.registry.StreamTranslateState} 的有状态方式。
     */
    public String transformAnthropicStreamEvent(String eventType, String eventData, String clientFormat, String originalModel) {
        if ("anthropic".equals(clientFormat)) {
            return replaceModelInRawJson(eventData, originalModel);
        }
        // anthropic → openai
        ProtocolTranslator translator = translatorRegistry.find("anthropic", "openai");
        if (translator != null) {
            return translator.translateStreamEvent(eventType, eventData, originalModel,
                    translator.createStreamState());
        }
        log.warn("未找到 anthropic→openai 流式翻译器，降级为 model 替换");
        return replaceModelInRawJson(eventData, originalModel);
    }

    // ==================== 错误响应构建 ====================

    public String buildErrorResponse(String clientFormat, String message, String errorType, int statusCode) {
        try {
            ObjectNode errorJson = objectMapper.createObjectNode();
            if ("anthropic".equals(clientFormat)) {
                errorJson.put("type", "error");
                ObjectNode error = errorJson.putObject("error");
                error.put("type", errorType != null ? errorType : mapStatusToAnthropicErrorType(statusCode));
                error.put("message", message);
            } else {
                ObjectNode error = errorJson.putObject("error");
                error.put("message", message);
                error.put("type", errorType != null ? errorType : mapStatusToOpenAiErrorType(statusCode));
                error.put("code", statusCode);
            }
            return objectMapper.writeValueAsString(errorJson);
        } catch (Exception e) {
            if ("anthropic".equals(clientFormat)) {
                return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + escapeJson(message) + "\"}}";
            }
            return "{\"error\":{\"message\":\"" + escapeJson(message) + "\",\"type\":\"api_error\",\"code\":" + statusCode + "}}";
        }
    }

    // ==================== 辅助方法 ====================

    private String replaceModelInJson(JsonNode json, String originalModel) {
        try {
            if (json.has("model")) {
                ObjectNode obj = json.deepCopy();
                obj.put("model", originalModel);
                return objectMapper.writeValueAsString(obj);
            }
            return json.toString();
        } catch (Exception e) { return json.toString(); }
    }

    private String replaceModelInRawJson(String rawJson, String originalModel) {
        try {
            JsonNode json = objectMapper.readTree(rawJson);
            if (json.has("model")) {
                ObjectNode obj = (ObjectNode) json;
                obj.put("model", originalModel);
                return objectMapper.writeValueAsString(obj);
            }
            return rawJson;
        } catch (Exception e) { return rawJson; }
    }

    private String mapStatusToOpenAiErrorType(int statusCode) {
        return switch (statusCode) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            default -> "api_error";
        };
    }

    private String mapStatusToAnthropicErrorType(int statusCode) {
        return switch (statusCode) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            case 529 -> "overloaded_error";
            default -> "api_error";
        };
    }

    /**
     * 对非标准 OpenAI 格式的 tool 定义进行修补以兼容不同提供商。
     * <p>
     * 某些 AI 提供商的 tool 定义可能不符合标准 OpenAI 格式，
     * 例如 name 字段在 tool 外层而非 function.name，
     * 该方法尝试自动补齐缺失字段，确保上游 API 正确识别。
     *
     * @param toolNode 已序列化的 tool JSON 节点（会被原地修改）
     */
    private void patchToolForCompatibility(ObjectNode toolNode) {
        JsonNode funcNode = toolNode.get("function");
        if (funcNode instanceof ObjectNode funcObj) {
            // 兜底补齐：有些提供商的 name 字段在 tool 外层而非 function.name
            if (!funcObj.has("name") || funcObj.get("name").isNull()) {
                if (toolNode.has("name")) {
                    funcObj.put("name", toolNode.get("name").asText());
                }
            }
        }
    }

    private boolean hasToolResultBlock(JsonNode contentArray) {
        if (!contentArray.isArray()) return false;
        for (JsonNode block : contentArray) {
            if (block.has("type") && "tool_result".equals(block.get("type").asText())) return true;
        }
        return false;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
