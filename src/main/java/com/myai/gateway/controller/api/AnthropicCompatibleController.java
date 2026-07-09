package com.myai.gateway.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.relay.RelayService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

/**
 * Anthropic 兼容 API 控制器
 * 严格实现 Anthropic Messages API 格式：
 * - POST /v1/messages  (支持 stream=true SSE)
 *
 * 认证方式: x-api-key: sk-myai-xxx
 * 必要请求头: anthropic-version: 2023-06-01
 *
 * 流式响应格式 (SSE):
 *   event: message_start
 *   data: {"type":"message_start","message":{...}}
 *
 *   event: content_block_delta
 *   data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
 *
 *   event: message_delta
 *   data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}
 *
 * 错误响应格式:
 *   {"type":"error","error":{"type":"authentication_error","message":"..."}}
 */
@RestController
@RequestMapping("/v1")
public class AnthropicCompatibleController {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCompatibleController.class);

    private final RelayService relayService;
    private final ObjectMapper objectMapper;

    public AnthropicCompatibleController(RelayService relayService, ObjectMapper objectMapper) {
        this.relayService = relayService;
        this.objectMapper = objectMapper;
    }

    /**
     * Anthropic 消息接口
     * POST /v1/messages
     * x-api-key: sk-myai-xxx
     * anthropic-version: 2023-06-01
     *
     * 支持 stream=true，此时返回 Anthropic 标准 SSE 格式
     */
    @PostMapping(value = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = {"application/json;charset=UTF-8", "text/event-stream;charset=UTF-8"})
    public Object messages(
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "anthropic-version", defaultValue = "2023-06-01") String anthropicVersion,
            @RequestHeader(value = "anthropic-beta", required = false) String anthropicBeta,
            @RequestBody String requestBody,
            HttpServletResponse response) {

        // 兼容两种鉴权头：x-api-key（Anthropic 标准）与 Authorization: Bearer（OAuth / Claude Code 默认）
        // 优先使用 x-api-key；二者皆缺失时抛出异常，交由 GlobalExceptionHandler 返回 Anthropic 格式错误
        String apiKey;
        if (xApiKey != null && !xApiKey.isBlank()) {
            apiKey = xApiKey;
        } else if (authorization != null && !authorization.isBlank()) {
            apiKey = authorization;
        } else {
            throw new MissingCredentialException(
                    "Authentication required. Provide either 'x-api-key' header or 'Authorization: Bearer' header.");
        }

        // 检查是否为流式请求
        if (isStreamRequest(requestBody)) {
            // 显式设置响应头，确保浏览器使用 UTF-8 解码 SSE 流
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            return relayService.messagesStream(apiKey, requestBody, anthropicVersion, false);
        }

        // 非流式
        return relayService.messages(apiKey, requestBody, anthropicVersion)
                .map(resp -> {
                    if (isErrorResponse(resp)) {
                        int statusCode = extractAnthropicErrorCode(resp);
                        return ResponseEntity.status(statusCode)
                                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                                .body(resp);
                    }
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                            .body(resp);
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

    /**
     * 从 Anthropic 格式错误响应中提取 HTTP 状态码
     */
    private int extractAnthropicErrorCode(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            if (json.has("error")) {
                JsonNode error = json.get("error");
                String type = error.has("type") ? error.get("type").asText() : "";
                return switch (type) {
                    case "invalid_request_error" -> 400;
                    case "authentication_error" -> 401;
                    case "permission_error" -> 403;
                    case "not_found_error" -> 404;
                    case "rate_limit_error" -> 429;
                    case "overloaded_error" -> 529;
                    default -> 500;
                };
            }
        } catch (Exception ignored) {}
        return 500;
    }

    /**
     * 当 x-api-key 与 Authorization 两个鉴权头均缺失时抛出。
     * 由 GlobalExceptionHandler 捕获并转换为 Anthropic 格式的 authentication_error (401)。
     */
    public static class MissingCredentialException extends RuntimeException {
        public MissingCredentialException(String message) {
            super(message);
        }
    }
}
