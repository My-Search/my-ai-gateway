package com.myai.gateway.relay.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * OpenAI ↔ Anthropic 消息格式转换器
 *
 * 职责：
 * 1. 将客户端请求解析为 InternalRequest（统一内部格式）
 * 2. 将 InternalRequest 转换为目标渠道格式（OpenAI / Anthropic）
 * 3. 将上游响应转换回客户端格式
 * 4. 处理流式 SSE 事件的格式转换
 */
@Component
public class MessageTransformer {

    private static final Logger log = LoggerFactory.getLogger(MessageTransformer.class);
    private final ObjectMapper objectMapper;

    public MessageTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 请求解析 ====================

    /**
     * 解析 OpenAI 格式请求
     */
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

        // 解析 stop
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

        // 解析 messages
        List<InternalMessage> messages = new ArrayList<>();
        if (json.has("messages") && json.get("messages").isArray()) {
            for (JsonNode msg : json.get("messages")) {
                InternalMessage im = new InternalMessage();
                im.setRole(msg.get("role").asText());

                // content 可能是 string 或 array
                JsonNode contentNode = msg.get("content");
                if (contentNode != null) {
                    if (contentNode.isTextual()) {
                        im.setContent(contentNode.asText());
                    } else if (contentNode.isArray()) {
                        im.setContentParts(objectMapper.convertValue(contentNode, List.class));
                    }
                }

                // tool_calls (assistant)
                if (msg.has("tool_calls")) {
                    im.setToolCalls(objectMapper.convertValue(msg.get("tool_calls"), List.class));
                }

                // tool_call_id (tool)
                if (msg.has("tool_call_id")) {
                    im.setToolCallId(msg.get("tool_call_id").asText());
                }

                // name (tool/function)
                if (msg.has("name")) {
                    im.setName(msg.get("name").asText());
                }

                messages.add(im);
            }
        }
        req.setMessages(messages);

        // 解析 tools
        if (json.has("tools") && json.get("tools").isArray()) {
            req.setTools(objectMapper.convertValue(json.get("tools"), List.class));
        }

        // 解析 reasoning_effort（思考强度）
        if (json.has("reasoning_effort")) {
            req.setReasoningEffort(json.get("reasoning_effort").asText());
        }

        return req;
    }

    /**
     * 解析 Anthropic 格式请求
     */
    public InternalRequest parseAnthropicRequest(JsonNode json) {
        InternalRequest req = new InternalRequest();
        req.setClientApiFormat("anthropic");
        req.setOriginalRequestJson(json);

        req.setModel(json.has("model") ? json.get("model").asText() : "");
        req.setStream(json.has("stream") && json.get("stream").asBoolean());

        if (json.has("max_tokens")) req.setMaxTokens(json.get("max_tokens").asInt());
        if (json.has("temperature")) req.setTemperature(json.get("temperature").asDouble());
        if (json.has("top_p")) req.setTopP(json.get("top_p").asDouble());

        // system prompt (Anthropic 顶层字段)
        if (json.has("system")) {
            JsonNode systemNode = json.get("system");
            if (systemNode.isTextual()) {
                req.setSystemPrompt(systemNode.asText());
            } else if (systemNode.isArray()) {
                // Anthropic 支持 system 为 block 数组
                StringBuilder sb = new StringBuilder();
                systemNode.forEach(block -> {
                    if (block.has("text")) sb.append(block.get("text").asText());
                });
                req.setSystemPrompt(sb.toString());
            }
        }

        // 解析 stop_sequences
        if (json.has("stop_sequences")) {
            List<String> stop = new ArrayList<>();
            json.get("stop_sequences").forEach(s -> stop.add(s.asText()));
            req.setStop(stop);
        }

        // 解析 messages
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
                        // Anthropic content blocks: [{type:"text", text:"..."}, {type:"image", source:...}]
                        im.setContentParts(objectMapper.convertValue(contentNode, List.class));
                    }
                }

                // Anthropic tool_use → tool_calls 映射
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

                // Anthropic tool_result message
                if ("tool".equals(im.getRole()) || 
                    (msg.has("content") && msg.get("content").isArray() && 
                     hasToolResultBlock(msg.get("content")))) {
                    // 从 tool_result block 提取 tool_call_id
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

        // 解析 reasoning_effort（思考强度）
        if (json.has("reasoning_effort")) {
            req.setReasoningEffort(json.get("reasoning_effort").asText());
        }

        // 解析 tools
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

    /**
     * 构建 OpenAI 格式请求 JSON
     */
    public String buildOpenAiRequest(InternalRequest req) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", req.getModel());

        // 构建 messages
        ArrayNode messagesNode = root.putArray("messages");

        // system prompt → system message
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
                // 转换 Anthropic content blocks → OpenAI 格式
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
                        // Anthropic image block → OpenAI image_url
                        partNode.put("type", "image_url");
                        ObjectNode imageUrl = partNode.putObject("image_url");
                        ObjectNode source = objectMapper.valueToTree(part.get("source"));
                        if (source != null) {
                            String mediaType = source.has("media_type") ? source.get("media_type").asText() : "image/jpeg";
                            String data = source.has("data") ? source.get("data").asText() : "";
                            imageUrl.put("url", "data:" + mediaType + ";base64," + data);
                        }
                    } else {
                        // 透传其他类型
                        partNode.setAll((ObjectNode) objectMapper.valueToTree(part));
                    }
                }
            }

            // tool_calls
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                msgNode.set("tool_calls", objectMapper.valueToTree(msg.getToolCalls()));
            }

            // tool_call_id
            if (msg.getToolCallId() != null) {
                msgNode.put("tool_call_id", msg.getToolCallId());
            }

            // name
            if (msg.getName() != null) {
                msgNode.put("name", msg.getName());
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
            // 确保 tools 是 OpenAI 格式
            ArrayNode toolsNode = root.putArray("tools");
            for (Map<String, Object> tool : req.getTools()) {
                if ("function".equals(tool.get("type"))) {
                    toolsNode.add(objectMapper.valueToTree(tool));
                } else {
                    // Anthropic tool → OpenAI tool
                    ObjectNode toolNode = toolsNode.addObject();
                    toolNode.put("type", "function");
                    ObjectNode func = toolNode.putObject("function");
                    func.put("name", (String) tool.get("name"));
                    if (tool.containsKey("description")) func.put("description", (String) tool.get("description"));
                    if (tool.containsKey("input_schema")) func.set("parameters", objectMapper.valueToTree(tool.get("input_schema")));
                }
            }
        }

        if (req.getToolChoice() != null) {
            root.set("tool_choice", objectMapper.valueToTree(req.getToolChoice()));
        }

        // 思考强度
        if (req.getReasoningEffort() != null && !req.getReasoningEffort().isEmpty()) {
            root.put("reasoning_effort", req.getReasoningEffort());
        }

        // 透传额外参数
        if (req.getExtraParams() != null) {
            for (Map.Entry<String, Object> entry : req.getExtraParams().entrySet()) {
                root.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
            }
        }

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("构建 OpenAI 请求失败", e);
            return req.getOriginalRequestJson().toString();
        }
    }

    /**
     * 构建 Anthropic 格式请求 JSON
     */
    public String buildAnthropicRequest(InternalRequest req) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", req.getModel());

        if (req.getMaxTokens() != null) {
            root.put("max_tokens", req.getMaxTokens());
        } else {
            root.put("max_tokens", 4096); // Anthropic 必须提供 max_tokens
        }

        // system prompt (Anthropic 顶层字段)
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isEmpty()) {
            root.put("system", req.getSystemPrompt());
        }

        // 构建 messages (排除 system 角色)
        ArrayNode messagesNode = root.putArray("messages");
        for (InternalMessage msg : req.getMessages()) {
            String role = msg.getRole();
            if ("system".equals(role)) {
                // system 消息 → Anthropic 顶层 system 字段
                if (req.getSystemPrompt() == null || req.getSystemPrompt().isEmpty()) {
                    root.put("system", msg.getContent());
                }
                continue;
            }

            ObjectNode msgNode = messagesNode.addObject();

            // tool role → user with tool_result block
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
                // 转换 OpenAI content parts → Anthropic content blocks
                ArrayNode contentArray = msgNode.putArray("content");
                for (Map<String, Object> part : msg.getContentParts()) {
                    ObjectNode partNode = contentArray.addObject();
                    String type = (String) part.get("type");
                    if ("text".equals(type)) {
                        partNode.put("type", "text");
                        partNode.put("text", (String) part.get("text"));
                    } else if ("image_url".equals(type)) {
                        // OpenAI image_url → Anthropic image block
                        partNode.put("type", "image");
                        ObjectNode source = partNode.putObject("source");
                        source.put("type", "base64");
                        String url = part.containsKey("image_url") ?
                                String.valueOf(((Map<?, ?>) part.get("image_url")).get("url")) : "";
                        if (url.startsWith("data:")) {
                            String[] parts = url.split(";base64,", 2);
                            String mediaType = parts[0].replace("data:", "");
                            source.put("media_type", mediaType);
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

            // assistant with tool_calls → Anthropic content blocks with tool_use
            if ("assistant".equals(role) && msg.getToolCalls() != null) {
                ArrayNode contentArr = msgNode.putArray("content");
                // 先加文本内容
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
                        try {
                            toolUse.set("input", objectMapper.readTree(args));
                        } catch (Exception e) {
                            toolUse.putObject("input");
                        }
                    }
                }
            }

            // user message with tool_calls (unlikely but handle)
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

        // tools: 确保是 Anthropic 格式
        if (req.getTools() != null && !req.getTools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (Map<String, Object> tool : req.getTools()) {
                ObjectNode toolNode = toolsNode.addObject();
                if ("function".equals(tool.get("type"))) {
                    // OpenAI tool → Anthropic tool
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
                    // 已经是 Anthropic 格式
                    toolNode.put("name", (String) tool.get("name"));
                    if (tool.containsKey("description")) toolNode.put("description", (String) tool.get("description"));
                    if (tool.containsKey("input_schema")) {
                        toolNode.set("input_schema", objectMapper.valueToTree(tool.get("input_schema")));
                    }
                }
            }
        }

        // 思考强度
        if (req.getReasoningEffort() != null && !req.getReasoningEffort().isEmpty()) {
            root.put("reasoning_effort", req.getReasoningEffort());
        }

        if (req.getToolChoice() != null) {
            root.set("tool_choice", objectMapper.valueToTree(req.getToolChoice()));
        }

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("构建 Anthropic 请求失败", e);
            return req.getOriginalRequestJson().toString();
        }
    }

    // ==================== 响应转换 ====================

    /**
     * 将上游 OpenAI 响应转换为客户端格式
     */
    public String transformOpenAiResponseToClient(JsonNode openAiResp, String clientFormat, String originalModel) {
        if ("openai".equals(clientFormat)) {
            // 同格式，只需替换 model
            return replaceModelInJson(openAiResp, originalModel);
        }
        // OpenAI → Anthropic
        return convertOpenAiResponseToAnthropic(openAiResp, originalModel);
    }

    /**
     * 将上游 Anthropic 响应转换为客户端格式
     */
    public String transformAnthropicResponseToClient(JsonNode anthropicResp, String clientFormat, String originalModel) {
        if ("anthropic".equals(clientFormat)) {
            return replaceModelInJson(anthropicResp, originalModel);
        }
        // Anthropic → OpenAI
        return convertAnthropicResponseToOpenAi(anthropicResp, originalModel);
    }

    /**
     * Anthropic 响应 → OpenAI 格式
     */
    private String convertAnthropicResponseToOpenAi(JsonNode anthropic, String originalModel) {
        try {
            ObjectNode openai = objectMapper.createObjectNode();
            openai.put("id", anthropic.has("id") ? anthropic.get("id").asText() : "chatcmpl-" + UUID.randomUUID());
            openai.put("object", "chat.completion");
            openai.put("created", System.currentTimeMillis() / 1000);
            openai.put("model", originalModel);

            // choices
            ArrayNode choices = openai.putArray("choices");
            ObjectNode choice = choices.addObject();
            choice.put("index", 0);

            ObjectNode message = choice.putObject("message");
            message.put("role", "assistant");

            // 提取文本和 tool_use
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
                        func.put("arguments", block.has("input") ? objectMapper.writeValueAsString(block.get("input")) : "{}");
                        tc.put("function", func);
                        toolCalls.add(tc);
                    }
                }
            }

            message.put("content", textContent.length() > 0 ? textContent.toString() : null);
            if (!toolCalls.isEmpty()) {
                message.set("tool_calls", objectMapper.valueToTree(toolCalls));
            }

            // stop_reason 映射
            String stopReason = anthropic.has("stop_reason") ? anthropic.get("stop_reason").asText() : "stop";
            choice.put("finish_reason", mapAnthropicStopReason(stopReason));

            // usage 映射
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

    /**
     * OpenAI 响应 → Anthropic 格式
     */
    private String convertOpenAiResponseToAnthropic(JsonNode openai, String originalModel) {
        try {
            ObjectNode anthropic = objectMapper.createObjectNode();
            anthropic.put("id", openai.has("id") ? openai.get("id").asText() : "msg_" + UUID.randomUUID());
            anthropic.put("type", "message");
            anthropic.put("role", "assistant");
            anthropic.put("model", originalModel);

            // 提取 content blocks
            ArrayNode contentBlocks = anthropic.putArray("content");

            if (openai.has("choices") && openai.get("choices").isArray() && openai.get("choices").size() > 0) {
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

                // finish_reason → stop_reason
                String finishReason = firstChoice.has("finish_reason") ? firstChoice.get("finish_reason").asText() : "stop";
                anthropic.put("stop_reason", mapOpenAiStopReason(finishReason));
            }

            anthropic.putNull("stop_sequence");

            // usage 映射
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

    // ==================== 流式事件转换 ====================

    /**
     * 转换 OpenAI SSE 事件到客户端格式
     */
    public String transformOpenAiStreamEvent(String eventData, String clientFormat, String originalModel) {
        if ("[DONE]".equals(eventData)) return "[DONE]";
        if ("openai".equals(clientFormat)) {
            // 同格式，替换 model
            try {
                JsonNode json = objectMapper.readTree(eventData);
                if (json.has("model")) {
                    ObjectNode obj = (ObjectNode) json;
                    obj.put("model", originalModel);
                    return objectMapper.writeValueAsString(obj);
                }
                return eventData;
            } catch (Exception e) {
                return eventData;
            }
        }
        // OpenAI → Anthropic 流式
        return convertOpenAiStreamEventToAnthropic(eventData, originalModel);
    }

    /**
     * 转换 Anthropic SSE 事件到客户端格式
     */
    public String transformAnthropicStreamEvent(String eventType, String eventData, String clientFormat, String originalModel) {
        if ("anthropic".equals(clientFormat)) {
            // 同格式，替换 model
            try {
                JsonNode json = objectMapper.readTree(eventData);
                if (json.has("model")) {
                    ObjectNode obj = (ObjectNode) json;
                    obj.put("model", originalModel);
                    return objectMapper.writeValueAsString(obj);
                }
                return eventData;
            } catch (Exception e) {
                return eventData;
            }
        }
        // Anthropic → OpenAI 流式
        return convertAnthropicStreamEventToOpenAi(eventType, eventData, originalModel);
    }

    /**
     * Anthropic SSE → OpenAI SSE chunk
     */
    private String convertAnthropicStreamEventToOpenAi(String eventType, String eventData, String originalModel) {
        try {
            JsonNode json = objectMapper.readTree(eventData);

            if ("message_start".equals(eventType)) {
                // 发送初始 chunk（类似 OpenAI 的第一个 chunk）
                ObjectNode chunk = createOpenAiStreamChunk(originalModel, null, null);
                return objectMapper.writeValueAsString(chunk);
            } else if ("content_block_start".equals(eventType)) {
                JsonNode block = json.get("content_block");
                if (block != null && "tool_use".equals(block.get("type").asText())) {
                    // tool_use 开始
                    ObjectNode delta = objectMapper.createObjectNode();
                    ArrayNode toolCalls = delta.putArray("tool_calls");
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("index", json.has("index") ? json.get("index").asInt() : 0);
                    tc.put("id", block.get("id").asText());
                    tc.put("type", "function");
                    ObjectNode func = tc.putObject("function");
                    func.put("name", block.get("name").asText());
                    func.put("arguments", "");
                    ObjectNode chunk = createOpenAiStreamChunk(originalModel, null, delta);
                    return objectMapper.writeValueAsString(chunk);
                }
                return null; // text block start 不单独发 chunk
            } else if ("content_block_delta".equals(eventType)) {
                JsonNode delta = json.get("delta");
                if (delta != null) {
                    String deltaType = delta.has("type") ? delta.get("type").asText() : "text_delta";
                    if ("text_delta".equals(deltaType)) {
                        ObjectNode deltaNode = objectMapper.createObjectNode();
                        deltaNode.put("content", delta.get("text").asText());
                        ObjectNode chunk = createOpenAiStreamChunk(originalModel, null, deltaNode);
                        return objectMapper.writeValueAsString(chunk);
                    } else if ("input_json_delta".equals(deltaType)) {
                        // tool_use input delta
                        ObjectNode deltaObj = objectMapper.createObjectNode();
                        ArrayNode toolCalls = deltaObj.putArray("tool_calls");
                        ObjectNode tc = toolCalls.addObject();
                        tc.put("index", json.has("index") ? json.get("index").asInt() : 0);
                        ObjectNode func = tc.putObject("function");
                        func.put("arguments", delta.has("partial_json") ? delta.get("partial_json").asText() : "");
                        ObjectNode chunk = createOpenAiStreamChunk(originalModel, null, deltaObj);
                        return objectMapper.writeValueAsString(chunk);
                    }
                }
            } else if ("message_delta".equals(eventType)) {
                // 结束信号：拷贝 usage（OpenAI 格式使用 prompt_tokens / completion_tokens / total_tokens）
                String stopReason = json.has("delta") && json.get("delta").has("stop_reason") ?
                        json.get("delta").get("stop_reason").asText() : "stop";
                ObjectNode delta = objectMapper.createObjectNode();
                ObjectNode chunk = createOpenAiStreamChunk(originalModel, mapAnthropicStopReason(stopReason), delta);
                if (json.has("usage")) {
                    ObjectNode usage = chunk.putObject("usage");
                    JsonNode src = json.get("usage");
                    int inTokens = src.has("input_tokens") ? src.get("input_tokens").asInt() : 0;
                    int outTokens = src.has("output_tokens") ? src.get("output_tokens").asInt() : 0;
                    usage.put("prompt_tokens", inTokens);
                    usage.put("completion_tokens", outTokens);
                    usage.put("total_tokens", inTokens + outTokens);
                }
                return objectMapper.writeValueAsString(chunk);
            }
            return null;
        } catch (Exception e) {
            log.debug("Anthropic→OpenAI 流式事件转换失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * OpenAI SSE chunk → Anthropic SSE event
     */
    private String convertOpenAiStreamEventToAnthropic(String eventData, String originalModel) {
        try {
            if ("[DONE]".equals(eventData)) return null;
            JsonNode json = objectMapper.readTree(eventData);

            if (!json.has("choices") || json.get("choices").size() == 0) return null;
            JsonNode choice = json.get("choices").get(0);
            JsonNode delta = choice.get("delta");

            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull() ?
                    choice.get("finish_reason").asText() : null;

            if (finishReason != null) {
                // 结束事件：拷贝 usage（Anthropic 格式使用 input_tokens / output_tokens）
                ObjectNode msgDelta = objectMapper.createObjectNode();
                msgDelta.put("type", "message_delta");
                ObjectNode deltaObj = msgDelta.putObject("delta");
                deltaObj.put("stop_reason", mapOpenAiStopReason(finishReason));
                deltaObj.putNull("stop_sequence");
                ObjectNode usageObj = msgDelta.putObject("usage");
                if (json.has("usage")) {
                    JsonNode src = json.get("usage");
                    usageObj.put("input_tokens", src.has("prompt_tokens") ? src.get("prompt_tokens").asInt() : 0);
                    usageObj.put("output_tokens", src.has("completion_tokens") ? src.get("completion_tokens").asInt() : 0);
                }
                return objectMapper.writeValueAsString(msgDelta);
            }

            if (delta != null) {
                // 文本内容
                if (delta.has("content") && !delta.get("content").isNull()) {
                    ObjectNode event = objectMapper.createObjectNode();
                    event.put("type", "content_block_delta");
                    event.put("index", 0);
                    ObjectNode deltaObj = event.putObject("delta");
                    deltaObj.put("type", "text_delta");
                    deltaObj.put("text", delta.get("content").asText());
                    return objectMapper.writeValueAsString(event);
                }

                // tool_calls
                if (delta.has("tool_calls")) {
                    for (JsonNode tc : delta.get("tool_calls")) {
                        int idx = tc.has("index") ? tc.get("index").asInt() : 0;
                        if (tc.has("id")) {
                            // tool_use start
                            ObjectNode event = objectMapper.createObjectNode();
                            event.put("type", "content_block_start");
                            event.put("index", idx);
                            ObjectNode block = event.putObject("content_block");
                            block.put("type", "tool_use");
                            block.put("id", tc.get("id").asText());
                            JsonNode func = tc.get("function");
                            block.put("name", func != null && func.has("name") ? func.get("name").asText() : "");
                            block.putObject("input");
                            return objectMapper.writeValueAsString(event);
                        } else if (tc.has("function")) {
                            // tool_use input delta
                            ObjectNode event = objectMapper.createObjectNode();
                            event.put("type", "content_block_delta");
                            event.put("index", idx);
                            ObjectNode deltaObj = event.putObject("delta");
                            deltaObj.put("type", "input_json_delta");
                            deltaObj.put("partial_json", tc.get("function").get("arguments").asText());
                            return objectMapper.writeValueAsString(event);
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("OpenAI→Anthropic 流式事件转换失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 错误响应构建 ====================

    /**
     * 构建符合客户端格式的错误响应
     */
    public String buildErrorResponse(String clientFormat, String message, String errorType, int statusCode) {
        try {
            ObjectNode errorJson = objectMapper.createObjectNode();
            if ("anthropic".equals(clientFormat)) {
                errorJson.put("type", "error");
                ObjectNode error = errorJson.putObject("error");
                error.put("type", errorType != null ? errorType : mapStatusToAnthropicErrorType(statusCode));
                error.put("message", message);
            } else {
                // OpenAI 格式
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

    private ObjectNode createOpenAiStreamChunk(String model, String finishReason, ObjectNode delta) {
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

    private String replaceModelInJson(JsonNode json, String originalModel) {
        try {
            if (json.has("model")) {
                ObjectNode obj = json.deepCopy();
                obj.put("model", originalModel);
                return objectMapper.writeValueAsString(obj);
            }
            return json.toString();
        } catch (Exception e) {
            return json.toString();
        }
    }

    private String mapAnthropicStopReason(String reason) {
        if (reason == null) return "stop";
        return switch (reason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "stop_sequence" -> "stop";
            default -> "stop";
        };
    }

    private String mapOpenAiStopReason(String reason) {
        if (reason == null) return "end_turn";
        return switch (reason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            default -> "end_turn";
        };
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
