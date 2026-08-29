package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求日志服务（门面）
 * 记录请求的完整生命周期，用于在日志中体现：开始 -> 重试 -> 重新路由 -> 失败/成功。
 * <p>
 * 只读查询委托 {@link RequestLogQuerySupport}，清理/保留策略委托
 * {@link RequestLogCleanupSupport}，本类聚焦"写入/暂存原始请求数据"单一职责。
 * </p>
 */
@Service
public class RequestLogService {

    private static final Logger log = LoggerFactory.getLogger(RequestLogService.class);

    private final RequestLogMapper requestLogMapper;
    private final AsyncLogWriter asyncLogWriter;
    private final AdminConfigService adminConfigService;
    private final RequestLogQuerySupport querySupport;
    private final RequestLogCleanupSupport cleanupSupport;

    /**
     * 等待写入的原始请求数据：traceId -> 原始请求头/体 + 是否出现过真实重试。
     * <p>retryIndex 会被候选跳过（skip）递增，不能作为"发生过重试"的依据；
     * 因此在记录 retry 阶段日志时单独打标。</p>
     */
    private final ConcurrentHashMap<String, PendingRequestData> pendingRequestData = new ConcurrentHashMap<>();

    public RequestLogService(RequestLogMapper requestLogMapper, AsyncLogWriter asyncLogWriter,
                             AdminConfigService adminConfigService) {
        this.requestLogMapper = requestLogMapper;
        this.asyncLogWriter = asyncLogWriter;
        this.adminConfigService = adminConfigService;
        this.querySupport = new RequestLogQuerySupport(requestLogMapper);
        this.cleanupSupport = new RequestLogCleanupSupport(requestLogMapper);
    }

    /**
     * 生成追踪 ID 并设置到 MDC
     */
    public String startTrace() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("traceId", traceId);
        return traceId;
    }

    /**
     * 记录请求阶段
     *
     * @param traceId          追踪 ID
     * @param apiKeyName       API 密钥名称
     * @param modelName        自定义模型名
     * @param channelModelName 通道模型名
     * @param channelName      通道名
     * @param phase            阶段: start / retry / reroute / success / fail
     * @param message          日志消息
     * @param retryIndex       重试索引，0=首次请求，>0=重试次数
     */
    public void log(String traceId, String apiKeyName, String modelName,
                    String channelModelName, String channelName,
                    String phase, String message, int retryIndex) {
        log(traceId, apiKeyName, null, modelName, channelModelName, channelName, phase, message, retryIndex);
    }

    /**
     * 记录请求阶段（带网关 API Key id，按 id 精确过滤用）
     *
     * @param gatewayApiKeyId 网关 API Key 主键（可为 null）
     */
    public void log(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                    String channelModelName, String channelName,
                    String phase, String message, int retryIndex) {
        RequestLog record = buildLogRecord(traceId, apiKeyName, gatewayApiKeyId, modelName,
                channelModelName, channelName, phase, "pending", message, null, null, retryIndex, 0, 0, 0);
        asyncLogWriter.enqueue(record);
        markRetryIfNeeded(traceId, phase);

        String indent = "  ".repeat(retryIndex);
        String logMsg = "[{}] {}[{}] {} -> {} -> {}: {}";
        if ("fail".equals(phase)) {
            log.warn(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message);
        } else {
            log.info(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message);
        }
    }

    /** 记录请求阶段并附带思考强度。 */
    public void logWithReasoningEffort(String traceId, String apiKeyName, Long gatewayApiKeyId,
                                       String modelName, String channelModelName, String channelName,
                                       String phase, String message, int retryIndex, String reasoningEffort) {
        RequestLog record = buildLogRecord(traceId, apiKeyName, gatewayApiKeyId, modelName,
                channelModelName, channelName, phase, "pending", message, null, null, retryIndex, 0, 0, 0);
        record.setReasoningEffort(reasoningEffort);
        asyncLogWriter.enqueue(record);
        markRetryIfNeeded(traceId, phase);
    }

    /**
     * 记录请求阶段（默认 retryIndex=0）
     */
    public void log(String traceId, String apiKeyName, String modelName,
                    String channelModelName, String channelName,
                    String phase, String message) {
        log(traceId, apiKeyName, modelName, channelModelName, channelName, phase, message, 0);
    }

    /**
     * 记录请求开始阶段，同时存储原始请求头和请求体
     * 由 Controller / RelayService 在入口处调用
     */
    public void logStart(String traceId, String apiKeyName, String modelName,
                         String channelModelName, String channelName,
                         String message, int retryIndex,
                         String requestHeaders, String requestBody) {
        logStart(traceId, apiKeyName, null, modelName, channelModelName, channelName,
                message, retryIndex, requestHeaders, requestBody);
    }

    /**
     * 记录请求开始阶段
     * <p>
     * start 日志异步写入（不含原始请求头/体）。原始请求数据暂存到内存中，
     * 待请求完成 {@link #logComplete} 时根据 save level 决定是否写入数据库。
     * </p>
     */
    public void logStart(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                         String channelModelName, String channelName,
                         String message, int retryIndex,
                         String requestHeaders, String requestBody) {
        // 暂存原始请求数据，待请求完成后根据 save level 决定是否写入 DB
        if (requestHeaders != null || requestBody != null) {
            pendingRequestData.put(traceId, new PendingRequestData(requestHeaders, requestBody));
        }
        RequestLog record = buildLogRecord(traceId, apiKeyName, gatewayApiKeyId, modelName,
                channelModelName, channelName, "start", "pending", message, null, null, retryIndex, 0, 0, 0);
        record.setRequestHeaders(null);
        record.setRequestBody(null);
        asyncLogWriter.enqueue(record);
    }

    /** 记录请求开始并附带客户端思考强度。 */
    public void logStartWithReasoningEffort(String traceId, String apiKeyName, Long gatewayApiKeyId,
                                             String modelName, String channelModelName, String channelName,
                                             String message, int retryIndex, String requestHeaders,
                                             String requestBody, String reasoningEffort) {
        if (requestHeaders != null || requestBody != null) {
            pendingRequestData.put(traceId, new PendingRequestData(requestHeaders, requestBody));
        }
        RequestLog record = buildLogRecord(traceId, apiKeyName, gatewayApiKeyId, modelName,
                channelModelName, channelName, "start", "pending", message, null, null, retryIndex, 0, 0, 0);
        record.setReasoningEffort(reasoningEffort);
        record.setRequestHeaders(null);
        record.setRequestBody(null);
        asyncLogWriter.enqueue(record);
    }

    /**
     * 记录请求阶段（带响应时间），用于 start/retry/skip 等中间阶段需要展示"该次尝试耗时"的场景
     */
    public void logWithResponseTime(String traceId, String apiKeyName, String modelName,
                                    String channelModelName, String channelName,
                                    String phase, String message, int retryIndex, long responseTimeMs) {
        logWithResponseTime(traceId, apiKeyName, null, modelName, channelModelName, channelName,
                phase, message, retryIndex, responseTimeMs);
    }

    /**
     * 记录请求阶段（带响应时间 + 网关 API Key id）
     */
    public void logWithResponseTime(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                                    String channelModelName, String channelName,
                                    String phase, String message, int retryIndex, long responseTimeMs) {
        logWithResponseTimeAndReasoning(traceId, apiKeyName, gatewayApiKeyId, modelName, channelModelName,
                channelName, phase, message, retryIndex, responseTimeMs, null);
    }

    public void logWithResponseTimeAndReasoning(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                                    String channelModelName, String channelName,
                                    String phase, String message, int retryIndex, long responseTimeMs,
                                    String reasoningEffort) {
        RequestLog record = buildLogRecord(traceId, apiKeyName, gatewayApiKeyId, modelName,
                channelModelName, channelName, phase, "pending", message, (int) responseTimeMs,
                null, retryIndex, 0, 0, 0);
        record.setReasoningEffort(reasoningEffort);
        asyncLogWriter.enqueue(record);
        markRetryIfNeeded(traceId, phase);

        String indent = "  ".repeat(retryIndex);
        String logMsg = "[{}] {}[{}] {} -> {} -> {}: {} ({}ms)";
        if ("fail".equals(phase)) {
            log.warn(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message, responseTimeMs);
        } else {
            log.info(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message, responseTimeMs);
        }
    }

    /**
     * 记录请求完成（成功或最终失败），包含 token 用量
     */
    public void logComplete(String traceId, String apiKeyName, String modelName,
                            String channelModelName, String channelName,
                            String phase, String status, String message, long responseTimeMs,
                            int retryIndex, int promptTokens, int completionTokens, int totalTokens) {
        logComplete(traceId, apiKeyName, null, modelName, channelModelName, channelName,
                phase, status, message, responseTimeMs, null, retryIndex, promptTokens, completionTokens, totalTokens);
    }

    /**
     * 记录请求完成（成功或最终失败），包含 token 用量
     *
     * @param firstByteMs 首字节响应时间（毫秒，相对请求路由开始），未收到响应字节时为 null
     */
    public void logComplete(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                            String channelModelName, String channelName,
                            String phase, String status, String message, long responseTimeMs,
                            Long firstByteMs, int retryIndex, int promptTokens, int completionTokens, int totalTokens) {
        RequestLog record = buildLogRecord(traceId, apiKeyName, gatewayApiKeyId, modelName,
                channelModelName, channelName, phase, status, message, (int) responseTimeMs,
                firstByteMs != null ? (int) Math.max(0, firstByteMs) : null,
                retryIndex, promptTokens, completionTokens, totalTokens);
        asyncLogWriter.enqueue(record);

        String indent = "  ".repeat(retryIndex);
        String logMsg = "[{}] {}[{}] {} -> {} -> {}: {} ({}ms, tokens={})";
        if ("error".equals(status)) {
            log.warn(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message, responseTimeMs, totalTokens);
        } else {
            log.info(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message, responseTimeMs, totalTokens);
        }

        // 请求完成后根据 save level 决定是否持久化原始请求数据
        // 仅在 success/fail 终态时检查，避免中间状态被误处理
        if ("success".equals(phase) || "fail".equals(phase)) {
            saveRequestDataIfNeeded(traceId, phase);
        }
    }

    /**
     * 记录请求完成（默认 retryIndex=0，无 token 用量）
     */
    public void logComplete(String traceId, String apiKeyName, String modelName,
                            String channelModelName, String channelName,
                            String phase, String status, String message, long responseTimeMs) {
        logComplete(traceId, apiKeyName, modelName, channelModelName, channelName, phase, status, message, responseTimeMs, 0, 0, 0, 0);
    }

    /**
     * 记录请求完成（无 token 用量，指定 retryIndex）
     */
    public void logComplete(String traceId, String apiKeyName, String modelName,
                            String channelModelName, String channelName,
                            String phase, String status, String message, long responseTimeMs,
                            int retryIndex) {
        logComplete(traceId, apiKeyName, modelName, channelModelName, channelName, phase, status, message, responseTimeMs, retryIndex, 0, 0, 0);
    }

    /**
     * 记录请求完成（带网关 API Key id，无 token 用量，指定 retryIndex）
     */
    public void logComplete(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                            String channelModelName, String channelName,
                            String phase, String status, String message, long responseTimeMs,
                            int retryIndex) {
        logComplete(traceId, apiKeyName, gatewayApiKeyId, modelName, channelModelName, channelName,
                phase, status, message, responseTimeMs, null, retryIndex, 0, 0, 0);
    }

    /**
     * 记录请求完成（带网关 API Key id，无 token 用量，指定 retryIndex，带首字节响应时间）
     */
    public void logComplete(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                            String channelModelName, String channelName,
                            String phase, String status, String message, long responseTimeMs,
                            Long firstByteMs, int retryIndex) {
        logComplete(traceId, apiKeyName, gatewayApiKeyId, modelName, channelModelName, channelName,
                phase, status, message, responseTimeMs, firstByteMs, retryIndex, 0, 0, 0);
    }

    // ──────────────────── 只读查询（委托） ────────────────────

    /**
     * 分页获取日志（按 traceId 级别分页，保证每组 trace 的日志完整）
     *
     * @param offset 跳过的 traceId 数量
     * @param limit  返回的 traceId 数量
     * @return 完整的日志列表（trace 内按 createdAt 升序），不含 requestHeaders/requestBody
     */
    public List<RequestLog> getLogsByPage(int offset, int limit) {
        return querySupport.getLogsByPage(offset, limit);
    }

    /**
     * 获取去重后的 traceId 总数
     */
    public long getTraceCount() {
        return querySupport.getTraceCount();
    }

    /**
     * 分页获取日志（带条件过滤）
     *
     * @param offset          跳过的 traceId 数量
     * @param limit           返回的 traceId 数量
     * @param modelName       入口模型名（可选）
     * @param gatewayApiKeyId 网关 API Key 主键（可选；与 apiKeyName 互不影响，建议优先使用）
     * @param apiKeyName      API Key 名（可选，兼容旧接口：模糊匹配 api_key_name 列，存的是通道 Key 名）
     * @param startTime       开始时间（可选）
     * @param endTime         结束时间（可选）
     * @return 完整的日志列表（不含 requestHeaders/requestBody）
     */
    public List<RequestLog> getFilteredLogsByPage(int offset, int limit,
                                                  String modelName,
                                                  Long gatewayApiKeyId,
                                                  String apiKeyName,
                                                  LocalDateTime startTime,
                                                  LocalDateTime endTime) {
        return querySupport.getFilteredLogsByPage(offset, limit, modelName, gatewayApiKeyId, apiKeyName, startTime, endTime);
    }

    /** 兼容旧调用：仅传 apiKeyName */
    public List<RequestLog> getFilteredLogsByPage(int offset, int limit,
                                                  String modelName,
                                                  String apiKeyName,
                                                  LocalDateTime startTime,
                                                  LocalDateTime endTime) {
        return querySupport.getFilteredLogsByPage(offset, limit, modelName, apiKeyName, startTime, endTime);
    }

    /**
     * 获取过滤后的 traceId 总数
     */
    public long getFilteredTraceCount(String modelName,
                                      Long gatewayApiKeyId,
                                      String apiKeyName,
                                      LocalDateTime startTime,
                                      LocalDateTime endTime) {
        return querySupport.getFilteredTraceCount(modelName, gatewayApiKeyId, apiKeyName, startTime, endTime);
    }

    /** 兼容旧调用：仅传 apiKeyName */
    public long getFilteredTraceCount(String modelName,
                                      String apiKeyName,
                                      LocalDateTime startTime,
                                      LocalDateTime endTime) {
        return querySupport.getFilteredTraceCount(modelName, apiKeyName, startTime, endTime);
    }

    /**
     * 根据追踪 ID 获取日志
     */
    public List<RequestLog> getByTraceId(String traceId) {
        return querySupport.getByTraceId(traceId);
    }

    /**
     * 按主键获取原始请求数据（requestHeaders / requestBody）
     */
    public RequestLog getRequestDataByLogId(Long logId) {
        return querySupport.getRequestDataByLogId(logId);
    }

    // ──────────────────── 清理/保留（委托） ────────────────────

    /**
     * 清理过期日志
     */
    public void cleanOldLogs(int retainDays) {
        cleanupSupport.cleanOldLogs(retainDays);
    }

    /**
     * 清理过期的原始请求数据（request_headers / request_body） - 统一 TTL
     *
     * @param ttlHours 原始请求数据保留时长（小时），<=0 表示永久保留不清除
     */
    public void cleanExpiredRequestData(int ttlHours) {
        cleanupSupport.cleanExpiredRequestData(ttlHours);
    }

    /**
     * 清理过期的原始请求数据（request_headers / request_body） - 扩展接口，区分重试/失败与普通记录
     *
     * @param ttlHours          普通原始请求数据保留时长（小时），<=0 表示永久保留不清除；
     *                          调用方（LogCleanupTask）在值为 0 时已换算为 日志保留天数×24 传入
     * @param retryFailTtlHours 重试/失败请求数据保留时长（小时），<=0 表示永久保留不清除；
     *                          调用方（LogCleanupTask）在值为 0 时已换算为 日志保留天数×24 传入
     */
    public void cleanExpiredRequestData(int ttlHours, int retryFailTtlHours) {
        cleanupSupport.cleanExpiredRequestData(ttlHours, retryFailTtlHours);
    }

    // ──────────────────── 私有辅助 ────────────────────

    /**
     * 统一构建日志记录对象，避免各写入方法重复 setter；responseTimeMs / firstByteMs 为 null 时不写该字段（阶段内不展示耗时）
     */
    private RequestLog buildLogRecord(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                                      String channelModelName, String channelName,
                                      String phase, String status, String message, Integer responseTimeMs,
                                      Integer firstByteMs,
                                      int retryIndex, int promptTokens, int completionTokens, int totalTokens) {
        RequestLog record = new RequestLog();
        record.setTraceId(traceId);
        record.setApiKeyName(apiKeyName);
        record.setGatewayApiKeyId(gatewayApiKeyId);
        record.setModelName(modelName);
        record.setChannelModelName(channelModelName);
        record.setChannelName(channelName);
        record.setPhase(phase);
        record.setStatus(status);
        record.setMessage(message);
        if (responseTimeMs != null) {
            record.setResponseTimeMs(responseTimeMs);
        }
        if (firstByteMs != null) {
            record.setFirstByteMs(firstByteMs);
        }
        record.setRetryIndex(retryIndex);
        record.setPromptTokens(promptTokens);
        record.setCompletionTokens(completionTokens);
        record.setTotalTokens(totalTokens);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    /**
     * 根据 save level 检查是否需要持久化原始请求数据。
     * <p>
     * 请求完成后调用，从内存中取出暂存的原始请求数据，
     * 根据配置的保存级别决定是否写入数据库中的 start 记录：
     * <ul>
     *   <li>info：始终写入</li>
     *   <li>warn：仅出现真实重试（retry 阶段日志）或请求最终失败时写入；仅跳过（skip）候选不算</li>
     *   <li>error：仅在请求最终失败时写入</li>
     * </ul>
     * </p>
     *
     * @param traceId    追踪 ID
     * @param finalPhase 最终阶段（success / fail）
     */
    private void saveRequestDataIfNeeded(String traceId, String finalPhase) {
        PendingRequestData pending = pendingRequestData.remove(traceId);
        if (pending == null) {
            return;
        }

        String level = adminConfigService.getValueByKey(AdminConfigService.KEY_REQUEST_DATA_SAVE_LEVEL);
        if (level == null || level.isEmpty()) {
            level = "info";
        }

        boolean failed = "fail".equals(finalPhase);
        boolean shouldKeep;
        switch (level) {
            case "error":
                // 仅整体请求失败才保留
                shouldKeep = failed;
                break;
            case "warn":
                // 仅出现真实重试或最终失败才保留；仅跳过候选不算重试
                shouldKeep = failed || pending.hasRetry;
                break;
            default: // "info"
                shouldKeep = true;
                break;
        }

        if (shouldKeep) {
            // 将原始请求数据写入 start 记录
            requestLogMapper.update(null, new LambdaUpdateWrapper<RequestLog>()
                    .eq(RequestLog::getTraceId, traceId)
                    .eq(RequestLog::getPhase, "start")
                    .set(RequestLog::getRequestHeaders, pending.headers)
                    .set(RequestLog::getRequestBody, pending.body));
            log.debug("原始请求数据已持久化（saveLevel={}, finalPhase={}, hasRetry={}） - traceId={}",
                    level, finalPhase, pending.hasRetry, traceId);
        } else {
            log.debug("原始请求数据已丢弃（saveLevel={}, finalPhase={}, hasRetry={}） - traceId={}",
                    level, finalPhase, pending.hasRetry, traceId);
        }
    }

    /**
     * 记录 retry 阶段日志时，标记该 trace 出现过真实重试。
     * <p>跳过（skip）阶段不计入：熔断跳过、400 跳过、媒体类型不支持跳过等
     * 虽会递增路由的 retryIndex，但不属于"对上游的重试"，warn 级别不应据此保存原始数据。</p>
     */
    private void markRetryIfNeeded(String traceId, String phase) {
        if (!"retry".equals(phase)) {
            return;
        }
        PendingRequestData data = pendingRequestData.get(traceId);
        if (data != null) {
            data.hasRetry = true;
        }
    }

    /** 请求过程中的暂存状态：原始请求头/体 + 是否出现过真实重试（retry 阶段） */
    private static final class PendingRequestData {
        final String headers;
        final String body;
        volatile boolean hasRetry;

        PendingRequestData(String headers, String body) {
            this.headers = headers;
            this.body = body;
        }
    }
}
