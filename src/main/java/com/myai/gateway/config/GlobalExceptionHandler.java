package com.myai.gateway.config;

import com.myai.gateway.controller.api.AnthropicCompatibleController;
import com.myai.gateway.controller.api.OpenAiCompatibleController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 将 Spring MVC 在参数解析阶段抛出的异常转换为符合各 API 协议标准的错误响应：
 * - Anthropic 端点 (/v1/messages)：
 *     {"type":"error","error":{"type":"...","message":"..."}}
 * - OpenAI 端点 (/v1/chat/completions, /v1/embeddings, /v1/models)：
 *     {"error":{"message":"...","type":"..."}}
 *
 * 错误类型与 HTTP 状态码映射（遵循 Anthropic 规范）：
 *   invalid_request_error  → 400
 *   authentication_error   → 401
 *   not_found_error        → 404
 *   api_error              → 500
 *
 * 仅作用于两个兼容控制器，不影响 /admin/api 与 /api/share。
 */
@RestControllerAdvice(assignableTypes = {AnthropicCompatibleController.class, OpenAiCompatibleController.class})
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 缺少必需的请求头
     * - 缺少 Authorization 视为 OpenAI 鉴权错误
     * - 缺少 x-api-key 视为 Anthropic 鉴权错误
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        String headerName = ex.getHeaderName();
        log.warn("[{}] Missing request header [{}]: {}", request.getRequestURI(), headerName, ex.getMessage());

        if ("Authorization".equalsIgnoreCase(headerName)) {
            return buildResponse(request, "authentication_error",
                    "Authorization header is required. Expected: Authorization: Bearer sk-myai-xxx",
                    HttpStatus.UNAUTHORIZED);
        }
        if ("x-api-key".equalsIgnoreCase(headerName)) {
            return buildResponse(request, "authentication_error",
                    "x-api-key header is required. Expected: x-api-key: sk-myai-xxx",
                    HttpStatus.UNAUTHORIZED);
        }
        return buildResponse(request, "invalid_request_error",
                "Missing required header: " + headerName, HttpStatus.BAD_REQUEST);
    }

    /**
     * Anthropic 端点两个鉴权头（x-api-key / Authorization）均缺失
     */
    @ExceptionHandler(AnthropicCompatibleController.MissingCredentialException.class)
    public ResponseEntity<Map<String, Object>> handleMissingCredential(AnthropicCompatibleController.MissingCredentialException ex, HttpServletRequest request) {
        log.warn("[{}] Missing credentials: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(request, "authentication_error", ex.getMessage(), HttpStatus.UNAUTHORIZED);
    }

    /**
     * 请求体不可读 / JSON 解析失败
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("[{}] Unreadable request body: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(request, "invalid_request_error",
                "Request body is missing or not valid JSON.", HttpStatus.BAD_REQUEST);
    }

    /**
     * 缺少必需的请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        log.warn("[{}] Missing request parameter [{}]: {}", request.getRequestURI(), ex.getParameterName(), ex.getMessage());
        return buildResponse(request, "invalid_request_error",
                "Missing required parameter: " + ex.getParameterName(), HttpStatus.BAD_REQUEST);
    }

    /**
     * 参数校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.warn("[{}] Parameter validation failed: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(request, "invalid_request_error",
                "Request parameters failed validation.", HttpStatus.BAD_REQUEST);
    }

    /**
     * 不支持的 Content-Type
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        log.warn("[{}] Unsupported media type: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(request, "invalid_request_error",
                "Unsupported Content-Type. Expected: application/json", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * 不支持的 HTTP 方法
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.warn("[{}] Method not supported: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(request, "invalid_request_error",
                "HTTP method not supported: " + ex.getMethod(), HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * 找不到对应的处理器（需配合 spring.mvc.throw-exception-if-no-handler-found=true 才会抛出）
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandler(NoHandlerFoundException ex, HttpServletRequest request) {
        log.warn("[{}] No handler found: {} {}", request.getRequestURI(), ex.getHttpMethod(), ex.getRequestURL());
        return buildResponse(request, "not_found_error",
                "No API endpoint found for: " + ex.getRequestURL(), HttpStatus.NOT_FOUND);
    }

    /**
     * 兜底处理：未预期的异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("[{}] Unexpected error processing request", request.getRequestURI(), ex);
        return buildResponse(request, "api_error",
                "An internal error occurred while processing your request.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据请求路径构建对应协议格式的错误响应
     */
    private ResponseEntity<Map<String, Object>> buildResponse(HttpServletRequest request,
                                                              String errorType,
                                                              String message,
                                                              HttpStatus status) {
        String path = request.getRequestURI();
        Map<String, Object> body;
        if (isOpenaiEndpoint(path)) {
            body = buildOpenaiError(errorType, message);
        } else {
            // 默认使用 Anthropic 格式（覆盖 /v1/messages 及其他 /v1/* 端点）
            body = buildAnthropicError(errorType, message);
        }

        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(body);
    }

    private boolean isOpenaiEndpoint(String path) {
        if (path == null) return false;
        return path.startsWith("/v1/chat/completions")
                || path.startsWith("/v1/embeddings")
                || path.startsWith("/v1/models");
    }

    /**
     * Anthropic 错误格式：
     * {"type":"error","error":{"type":"...","message":"..."}}
     */
    private Map<String, Object> buildAnthropicError(String errorType, String message) {
        Map<String, Object> errorDetail = new LinkedHashMap<>();
        errorDetail.put("type", errorType);
        errorDetail.put("message", message);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", errorDetail);
        return body;
    }

    /**
     * OpenAI 错误格式：
     * {"error":{"message":"...","type":"..."}}
     */
    private Map<String, Object> buildOpenaiError(String errorType, String message) {
        Map<String, Object> errorDetail = new LinkedHashMap<>();
        errorDetail.put("message", message);
        errorDetail.put("type", errorType);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorDetail);
        return body;
    }
}
