package com.myai.gateway.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.entity.ApiKey;
import com.myai.gateway.relay.RelayService;
import com.myai.gateway.service.ApiKeyService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 管理后台「对话测试」REST API 控制器
 * <p>从原 {@link AdminApiController} 拆分而来（P2 架构：巨型类拆分），
 * 承载 Playground 的流式 / 非流式聊天测试接口。路径前缀与行为与原实现完全一致。</p>
 */
@RestController
@RequestMapping("/admin/api")
public class AdminChatController {

    private static final Logger log = LoggerFactory.getLogger(AdminChatController.class);

    private final ApiKeyService apiKeyService;
    private final RelayService relayService;
    private final ObjectMapper objectMapper;

    public AdminChatController(ApiKeyService apiKeyService,
                               RelayService relayService,
                               ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.relayService = relayService;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式聊天测试接口
     * POST /admin/api/chat/stream
     * <p>
     * 请求体:
     * {
     *   "model": "my-gpt4",
     *   "messages": [{"role": "user", "content": "hello"}],
     *   "api_key_id": 1  // 可选，不传则使用第一个可用的 API Key
     * }
     */
    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody String requestBody, HttpServletResponse response) {
        // 显式设置响应头，确保浏览器使用 UTF-8 解码 SSE 流
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        // SSE 响应不能被任何中间层或客户端缓冲，必须即时推送
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        // 设置极小的响应缓冲区（1KB），确保每次 flush 立即将数据推送到 TCP 栈
        response.setBufferSize(1024);

        SseEmitter emitter = new SseEmitter(0L); // 禁用墙钟超时，由 idle timeout 管控死连接

        try {
            JsonNode json = objectMapper.readTree(requestBody);
            String modelName = json.has("model") ? json.get("model").asText() : "";
            long apiKeyId = json.has("api_key_id") ? json.get("api_key_id").asLong() : 0;

            if (modelName.isEmpty()) {
                sendErrorAndComplete(emitter, "请选择要测试的模型");
                return emitter;
            }

            // 获取 API Key
            ApiKey apiKey = null;
            if (apiKeyId > 0) {
                apiKey = apiKeyService.getById(apiKeyId);
            }
            if (apiKey == null) {
                // 使用第一个可用的 API Key
                List<ApiKey> keys = apiKeyService.listAll();
                for (ApiKey k : keys) {
                    if (k.getEnabled() == 1) {
                        apiKey = k;
                        break;
                    }
                }
            }
            if (apiKey == null) {
                sendErrorAndComplete(emitter, "没有可用的 API Key，请先创建一个");
                return emitter;
            }

            // 构建 OpenAI 格式请求
            ObjectNode openAiRequest = objectMapper.createObjectNode();
            openAiRequest.put("model", modelName);
            openAiRequest.put("stream", true);

            if (json.has("messages")) {
                openAiRequest.set("messages", json.get("messages"));
            } else {
                ArrayNode msgs = openAiRequest.putArray("messages");
                ObjectNode msg = msgs.addObject();
                msg.put("role", "user");
                msg.put("content", json.has("content") ? json.get("content").asText() : "");
            }

            // 可选参数
            if (json.has("temperature")) openAiRequest.put("temperature", json.get("temperature").asDouble());
            if (json.has("max_tokens")) openAiRequest.put("max_tokens", json.get("max_tokens").asInt());

            String requestJson = objectMapper.writeValueAsString(openAiRequest);
            String authHeader = "Bearer " + apiKey.getKeyValue();

            log.info("Playground 测试: 模型={}, API Key={}", modelName, apiKey.getKeyName());

            // 调用流式中继（内部客户端，发送路由进度等自定义事件）
            return relayService.chatCompletionsStream(authHeader, requestJson, true);

        } catch (Exception e) {
            log.error("Playground 聊天请求失败", e);
            sendErrorAndComplete(emitter, "请求失败: " + e.getMessage());
            return emitter;
        }
    }

    /**
     * 非流式聊天测试接口
     * POST /admin/api/chat
     */
    @PostMapping(value = "/chat", produces = "application/json;charset=UTF-8")
    public Object chat(@RequestBody String requestBody) {
        try {
            JsonNode json = objectMapper.readTree(requestBody);
            String modelName = json.has("model") ? json.get("model").asText() : "";
            long apiKeyId = json.has("api_key_id") ? json.get("api_key_id").asLong() : 0;

            if (modelName.isEmpty()) {
                return "{\"error\":{\"message\":\"请选择要测试的模型\",\"type\":\"invalid_request_error\",\"code\":400}}";
            }

            ApiKey apiKey = null;
            if (apiKeyId > 0) {
                apiKey = apiKeyService.getById(apiKeyId);
            }
            if (apiKey == null) {
                List<ApiKey> keys = apiKeyService.listAll();
                for (ApiKey k : keys) {
                    if (k.getEnabled() == 1) {
                        apiKey = k;
                        break;
                    }
                }
            }
            if (apiKey == null) {
                return "{\"error\":{\"message\":\"没有可用的 API Key\",\"type\":\"api_error\",\"code\":401}}";
            }

            ObjectNode openAiRequest = objectMapper.createObjectNode();
            openAiRequest.put("model", modelName);
            openAiRequest.put("stream", false);

            if (json.has("messages")) {
                openAiRequest.set("messages", json.get("messages"));
            } else {
                ArrayNode msgs = openAiRequest.putArray("messages");
                ObjectNode msg = msgs.addObject();
                msg.put("role", "user");
                msg.put("content", json.has("content") ? json.get("content").asText() : "");
            }

            if (json.has("temperature")) openAiRequest.put("temperature", json.get("temperature").asDouble());
            if (json.has("max_tokens")) openAiRequest.put("max_tokens", json.get("max_tokens").asInt());

            String requestJson = objectMapper.writeValueAsString(openAiRequest);
            String authHeader = "Bearer " + apiKey.getKeyValue();

            return relayService.chatCompletions(authHeader, requestJson)
                    .map(response -> response);

        } catch (Exception e) {
            log.error("Playground 非流式聊天请求失败", e);
            return "{\"error\":{\"message\":\"" + e.getMessage() + "\",\"type\":\"api_error\",\"code\":500}}";
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("error", message);
            emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(error), MediaType.parseMediaType("application/json;charset=UTF-8")));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
