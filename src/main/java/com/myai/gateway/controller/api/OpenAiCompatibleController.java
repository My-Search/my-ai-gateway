package com.myai.gateway.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.entity.Model;
import com.myai.gateway.relay.RelayService;
import com.myai.gateway.service.ModelService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * OpenAI 兼容 API 控制器
 * 严格实现 OpenAI Chat Completions API 格式：
 * - POST /v1/chat/completions  (支持 stream=true SSE)
 * - GET  /v1/models
 * - POST /v1/embeddings
 *
 * 认证方式: Authorization: Bearer sk-myai-xxx
 *
 * 当 stream=true 时，返回标准 SSE 格式：
 *   data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[{"delta":{...},"index":0,"finish_reason":null}]}\n\n
 *   data: [DONE]\n\n
 */
@RestController
@RequestMapping("/v1")
public class OpenAiCompatibleController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleController.class);

    private final RelayService relayService;
    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleController(RelayService relayService, ModelService modelService, ObjectMapper objectMapper) {
        this.relayService = relayService;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    /**
     * OpenAI 聊天补全接口
     * POST /v1/chat/completions
     * Authorization: Bearer sk-myai-xxx
     *
     * 支持 stream=true，此时返回 SseEmitter (text/event-stream)
     */
    @PostMapping(value = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = {"application/json;charset=UTF-8", "text/event-stream;charset=UTF-8"})
    public Object chatCompletions(
            @RequestHeader("Authorization") String authHeader,
            @RequestHeader(value = "X-Internal-Client", required = false) String internalClientHeader,
            @RequestBody String requestBody,
            HttpServletResponse response) {

        // 检查是否为流式请求
        if (isStreamRequest(requestBody)) {
            // 显式设置响应头，确保浏览器使用 UTF-8 解码 SSE 流
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            // Playground 等内部客户端调用时，发送 _gateway_meta 和 _routing_progress 事件
            boolean internalClient = "playground".equals(internalClientHeader);
            return relayService.chatCompletionsStream(authHeader, requestBody, internalClient);
        }

        // 非流式
        return relayService.chatCompletions(authHeader, requestBody)
                .map(resp -> {
                    if (isErrorResponse(resp)) {
                        int statusCode = extractErrorCode(resp);
                        return ResponseEntity.status(statusCode)
                                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                                .body(resp);
                    }
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                            .body(resp);
                });
    }

    /**
     * 模型列表接口
     * GET /v1/models
     * 返回符合 OpenAI 格式的模型列表
     */
    @GetMapping("/models")
    public ResponseEntity<String> listModels() {
        try {
            List<Model> models = modelService.listVisible();
            ObjectNode root = objectMapper.createObjectNode();
            root.put("object", "list");
            ArrayNode data = root.putArray("data");

            for (Model model : models) {
                ObjectNode modelNode = data.addObject();
                modelNode.put("id", model.getModelName());
                modelNode.put("object", "model");
                modelNode.put("created", model.getCreatedAt() != null ?
                        model.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond() : 0);
                modelNode.put("owned_by", "my-ai-gateway");
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                    .body(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            log.error("获取模型列表失败", e);
            return ResponseEntity.ok("{\"object\":\"list\",\"data\":[]}");
        }
    }

    /**
     * Embeddings 接口
     * POST /v1/embeddings
     * 目前透传到上游渠道（仅支持 OpenAI 格式渠道）
     */
    @PostMapping(value = "/embeddings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> embeddings(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody String requestBody) {
        // embeddings 使用 chatCompletions 中继流程（会路由到对应模型）
        return relayService.chatCompletions(authHeader, requestBody)
                .map(response -> {
                    if (isErrorResponse(response)) {
                        int statusCode = extractErrorCode(response);
                        return ResponseEntity.status(statusCode)
                                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                                .body(response);
                    }
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                            .body(response);
                });
    }

    // ==================== 辅助方法 ====================

    private boolean isStreamRequest(String requestBody) {
        try {
            JsonNode json = objectMapper.readTree(requestBody);
            return json.has("stream") && json.get("stream").asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isErrorResponse(String body) {
        if (body == null) return true;
        try {
            JsonNode json = objectMapper.readTree(body);
            return json.has("error");
        } catch (Exception e) {
            return false;
        }
    }

    private int extractErrorCode(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            if (json.has("error") && json.get("error").has("code")) {
                return json.get("error").get("code").asInt(500);
            }
        } catch (Exception e) {
            log.warn("提取错误码失败，返回默认500: body={}", body, e);
        }
        return 500;
    }
}
