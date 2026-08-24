package com.myai.gateway.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.entity.ApiKey;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.service.ApiKeyService;
import com.myai.gateway.service.RequestLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中继日志辅助组件
 * <p>负责请求日志记录、API Key 掩码、请求头 JSON 构建等日志相关功能。</p>
 */
public class RelayLogger {

    private static final Logger log = LoggerFactory.getLogger(RelayLogger.class);

    private final RequestLogService requestLogService;
    private final ApiKeyService apiKeyService;

    private static final Pattern MODEL_NAME_PATTERN =
            Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    public RelayLogger(RequestLogService requestLogService, ApiKeyService apiKeyService) {
        this.requestLogService = requestLogService;
        this.apiKeyService = apiKeyService;
    }

    /**
     * 记录原始请求数据（请求头 + 请求体）
     *
     * @param traceId     追踪 ID
     * @param authHeader  原始 Authorization 头
     * @param headersJson 原始请求头（已转 JSON 字符串）
     * @param requestBody 原始请求体
     * @return 解析出的网关 API Key id
     */
    public Long logOriginalRequest(String traceId, String authHeader, String headersJson, String requestBody) {
        String modelName = extractModelFromBody(requestBody);
        Long gatewayApiKeyId = apiKeyService.resolveIdFromAuthHeader(authHeader);
        requestLogService.logStartWithReasoningEffort(traceId, null, gatewayApiKeyId, modelName, null, null,
                "请求开始", 0, headersJson, requestBody, extractReasoningEffortFromBody(requestBody));
        return gatewayApiKeyId;
    }

    /**
     * 记录请求阶段日志
     */
    public void logPhase(String traceId, Long gatewayApiKeyId, RoutingCandidate candidate,
                          InternalRequest req, String phase, String message, int retryIndex) {
        logPhase(traceId, gatewayApiKeyId, candidate, req, phase, message, retryIndex, null, null);
    }

    /**
     * 记录请求阶段日志（带响应时间）
     */
    public void logPhase(String traceId, Long gatewayApiKeyId, RoutingCandidate candidate,
                          InternalRequest req, String phase, String message, int retryIndex, Long responseTimeMs) {
        logPhase(traceId, gatewayApiKeyId, candidate, req, phase, message, retryIndex, responseTimeMs, null);
    }

    /**
     * 记录请求阶段日志（附带实际使用的思考强度）
     */
    public void logPhase(String traceId, Long gatewayApiKeyId, RoutingCandidate candidate,
                          InternalRequest req, String phase, String message, int retryIndex,
                          String reasoningEffort) {
        logPhase(traceId, gatewayApiKeyId, candidate, req, phase, message, retryIndex, null, reasoningEffort);
    }

    private void logPhase(String traceId, Long gatewayApiKeyId, RoutingCandidate candidate,
                           InternalRequest req, String phase, String message, int retryIndex,
                           Long responseTimeMs, String reasoningEffort) {
        String apiKeyName = candidate != null ? candidate.getChannelApiKey().getKeyName() : null;
        String modelName = req != null ? req.getModel() : null;
        String channelModelName = candidate != null ? candidate.getChannelModel().getModelName() : null;
        String channelName = candidate != null ? candidate.getChannel().getName() : null;
        if (responseTimeMs != null) {
            requestLogService.logWithResponseTimeAndReasoning(traceId, apiKeyName, gatewayApiKeyId, modelName,
                    channelModelName, channelName, phase, message, retryIndex, responseTimeMs, reasoningEffort);
        } else {
            requestLogService.logWithReasoningEffort(traceId, apiKeyName, gatewayApiKeyId, modelName, channelModelName,
                    channelName, phase, message, retryIndex, reasoningEffort);
        }
    }

    /**
     * 从请求体中提取 model 字段（使用正则避免全量 JSON 解析）
     */
    public String extractReasoningEffortFromBody(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) return null;
        Matcher m = Pattern.compile("\\\"reasoning_effort\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(requestBody);
        return m.find() ? m.group(1) : null;
    }

    public String extractModelFromBody(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) return null;
        Matcher m = MODEL_NAME_PATTERN.matcher(requestBody);
        return m.find() ? m.group(1) : null;
    }

    /** 敏感请求头名单：这些头的值在日志中做掩码处理，头名保留 */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "x-api-key", "proxy-authorization", "cookie", "set-cookie");

    private static final ObjectMapper HEADERS_JSON_MAPPER = new ObjectMapper();

    /**
     * 根据客户端真实请求头快照构建完整的原始请求头 JSON（保留全部请求头，敏感值掩码）
     * <p>同时附加 method / path / clientIp 等基础请求信息，
     * 保证在日志中查看原始请求数据时能看到完整请求信息。</p>
     */
    public String buildFullHeadersJson(ClientRequestInfo info) {
        try {
            ObjectNode root = HEADERS_JSON_MAPPER.createObjectNode();
            Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (info != null && info.headers() != null) {
                sorted.putAll(info.headers());
            }
            ObjectNode headersNode = root.putObject("headers");
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                headersNode.put(entry.getKey(), maskHeaderValue(entry.getKey(), entry.getValue()));
            }
            root.put("method", info != null ? info.method() : null);
            root.put("path", info != null ? info.path() : null);
            root.put("clientIp", info != null ? info.clientIp() : null);
            return HEADERS_JSON_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("构建原始请求头 JSON 失败: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 敏感请求头值掩码（Authorization / x-api-key 等），其余请求头原样保留
     */
    private String maskHeaderValue(String name, String value) {
        if (value == null) return null;
        if (name != null && SENSITIVE_HEADERS.contains(name.toLowerCase())) {
            return maskCredentialValue(value);
        }
        return value;
    }

    private String maskCredentialValue(String value) {
        if (value.isBlank()) return value;
        if (value.startsWith("Bearer ")) {
            return "Bearer " + maskBearerToken(value.substring(7));
        }
        return maskBearerToken(value);
    }

    /**
     * 对 Bearer Token / API Key 做掩码处理
     */
    public String maskBearerToken(String token) {
        if (token == null || token.isBlank()) return "";
        if (token.length() > 12) {
            return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
        }
        return token.substring(0, Math.min(6, token.length())) + "...";
    }

    /**
     * 更新网关 API Key 的最后使用时间
     */
    public void updateGatewayApiKeyLastUsed(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return;
        }
        try {
            ApiKey apiKey = apiKeyService.validateKey(authHeader);
            if (apiKey != null && apiKey.getId() != null) {
                apiKeyService.updateLastUsed(apiKey.getId());
                log.debug("已更新网关 API Key lastUsedAt: id={}, name={}", apiKey.getId(), apiKey.getKeyName());
            }
        } catch (Exception e) {
            log.warn("更新网关 API Key lastUsedAt 失败: {}", e.getMessage());
        }
    }
}
