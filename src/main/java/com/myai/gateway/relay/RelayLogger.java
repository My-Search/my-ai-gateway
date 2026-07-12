package com.myai.gateway.relay;

import com.myai.gateway.entity.ApiKey;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.service.ApiKeyService;
import com.myai.gateway.service.RequestLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
        requestLogService.logStart(traceId, null, gatewayApiKeyId, modelName, null, null,
                "请求开始", 0, headersJson, requestBody);
        return gatewayApiKeyId;
    }

    /**
     * 记录请求阶段日志
     */
    public void logPhase(String traceId, Long gatewayApiKeyId, RoutingCandidate candidate,
                          InternalRequest req, String phase, String message, int retryIndex) {
        logPhase(traceId, gatewayApiKeyId, candidate, req, phase, message, retryIndex, null);
    }

    /**
     * 记录请求阶段日志（带响应时间）
     */
    public void logPhase(String traceId, Long gatewayApiKeyId, RoutingCandidate candidate,
                          InternalRequest req, String phase, String message, int retryIndex, Long responseTimeMs) {
        String apiKeyName = candidate != null ? candidate.getChannelApiKey().getKeyName() : null;
        String modelName = req != null ? req.getModel() : null;
        String channelModelName = candidate != null ? candidate.getChannelModel().getModelName() : null;
        String channelName = candidate != null ? candidate.getChannel().getName() : null;
        if (responseTimeMs != null) {
            requestLogService.logWithResponseTime(traceId, apiKeyName, gatewayApiKeyId, modelName,
                    channelModelName, channelName, phase, message, retryIndex, responseTimeMs);
        } else {
            requestLogService.log(traceId, apiKeyName, gatewayApiKeyId, modelName, channelModelName,
                    channelName, phase, message, retryIndex);
        }
    }

    /**
     * 从请求体中提取 model 字段（使用正则避免全量 JSON 解析）
     */
    public String extractModelFromBody(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) return null;
        Matcher m = MODEL_NAME_PATTERN.matcher(requestBody);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 构建 OpenAI 兼容格式的请求头 JSON（对 Authorization 做掩码处理）
     */
    public String buildOpenaiHeadersJson(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return "{\"Content-Type\": \"application/json\"}";
        }
        String masked;
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            masked = "Bearer " + maskBearerToken(token);
        } else {
            masked = maskBearerToken(authHeader);
        }
        return "{\"Authorization\": \"" + masked + "\", \"Content-Type\": \"application/json\"}";
    }

    /**
     * 构建 Anthropic 兼容格式的请求头 JSON（对 x-api-key 做掩码处理）
     */
    public String buildAnthropicHeadersJson(String apiKeyHeader, String anthropicVersion) {
        String masked = maskBearerToken(apiKeyHeader);
        return "{\"x-api-key\": \"" + masked + "\", \"anthropic-version\": \"" + anthropicVersion
                + "\", \"Content-Type\": \"application/json\"}";
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
