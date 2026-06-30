package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.stream.Collectors;

/**
 * 请求日志服务
 * 记录请求的完整生命周期，用于在日志中体现：
 * 开始 -> 重试 -> 重新路由 -> 失败/成功
 */
@Service
public class RequestLogService {

    private static final Logger log = LoggerFactory.getLogger(RequestLogService.class);

    private final RequestLogMapper requestLogMapper;
    private final AsyncLogWriter asyncLogWriter;

    public RequestLogService(RequestLogMapper requestLogMapper, AsyncLogWriter asyncLogWriter) {
        this.requestLogMapper = requestLogMapper;
        this.asyncLogWriter = asyncLogWriter;
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
     * @param traceId      追踪 ID
     * @param apiKeyName   API 密钥名称
     * @param modelName    自定义模型名
     * @param channelModelName 通道模型名
     * @param channelName  通道名
     * @param phase        阶段: start / retry / reroute / success / fail
     * @param message      日志消息
     * @param retryIndex   重试索引，0=首次请求，>0=重试次数
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
        RequestLog record = new RequestLog();
        record.setTraceId(traceId);
        record.setApiKeyName(apiKeyName);
        record.setGatewayApiKeyId(gatewayApiKeyId);
        record.setModelName(modelName);
        record.setChannelModelName(channelModelName);
        record.setChannelName(channelName);
        record.setPhase(phase);
        record.setStatus("pending");
        record.setMessage(message);
        record.setRetryIndex(retryIndex);
        record.setCreatedAt(LocalDateTime.now());
        asyncLogWriter.enqueue(record);

        String indent = "  ".repeat(retryIndex);
        String logMsg = "[{}] {}[{}] {} -> {} -> {}: {}";
        if ("fail".equals(phase)) {
            log.warn(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message);
        } else {
            log.info(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message);
        }
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
     * 记录请求开始阶段（带网关 API Key id）
     */
    public void logStart(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                         String channelModelName, String channelName,
                         String message, int retryIndex,
                         String requestHeaders, String requestBody) {
        RequestLog record = new RequestLog();
        record.setTraceId(traceId);
        record.setApiKeyName(apiKeyName);
        record.setGatewayApiKeyId(gatewayApiKeyId);
        record.setModelName(modelName);
        record.setChannelModelName(channelModelName);
        record.setChannelName(channelName);
        record.setPhase("start");
        record.setStatus("pending");
        record.setMessage(message);
        record.setRetryIndex(retryIndex);
        record.setRequestHeaders(requestHeaders);
        record.setRequestBody(requestBody);
        record.setCreatedAt(LocalDateTime.now());
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
        RequestLog record = new RequestLog();
        record.setTraceId(traceId);
        record.setApiKeyName(apiKeyName);
        record.setGatewayApiKeyId(gatewayApiKeyId);
        record.setModelName(modelName);
        record.setChannelModelName(channelModelName);
        record.setChannelName(channelName);
        record.setPhase(phase);
        record.setStatus("pending");
        record.setMessage(message);
        record.setResponseTimeMs((int) responseTimeMs);
        record.setRetryIndex(retryIndex);
        record.setCreatedAt(LocalDateTime.now());
        asyncLogWriter.enqueue(record);

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
                phase, status, message, responseTimeMs, retryIndex, promptTokens, completionTokens, totalTokens);
    }

    /**
     * 记录请求完成（带网关 API Key id）
     */
    public void logComplete(String traceId, String apiKeyName, Long gatewayApiKeyId, String modelName,
                            String channelModelName, String channelName,
                            String phase, String status, String message, long responseTimeMs,
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
        record.setResponseTimeMs((int) responseTimeMs);
        record.setRetryIndex(retryIndex);
        record.setPromptTokens(promptTokens);
        record.setCompletionTokens(completionTokens);
        record.setTotalTokens(totalTokens);
        record.setCreatedAt(LocalDateTime.now());
        asyncLogWriter.enqueue(record);

        String indent = "  ".repeat(retryIndex);
        String logMsg = "[{}] {}[{}] {} -> {} -> {}: {} ({}ms, tokens={})";
        if ("error".equals(status)) {
            log.warn(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message, responseTimeMs, totalTokens);
        } else {
            log.info(logMsg, traceId, indent, phase, modelName, channelModelName, channelName, message, responseTimeMs, totalTokens);
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
                phase, status, message, responseTimeMs, retryIndex, 0, 0, 0);
    }

    /**
     * 分页获取日志（按 traceId 级别分页，保证每组 trace 的日志完整）
     * <p>
     * 列表查询排除 {@code request_headers} 和 {@code request_body} 两个大字段，
     * 避免列表加载时传输大量原始请求数据导致响应慢。原始请求数据通过
     * {@link #getRequestDataByLogId(Long)} 按需加载。
     * </p>
     *
     * @param offset 跳过的 traceId 数量
     * @param limit  返回的 traceId 数量
     * @return 完整的日志列表（trace 内按 createdAt 升序），不含 requestHeaders/requestBody
     */
    public List<RequestLog> getLogsByPage(int offset, int limit) {
        // 1. 获取分页后的 traceId 列表
        List<String> traceIds = requestLogMapper.selectTraceIdsByPage(offset, limit);
        if (traceIds.isEmpty()) {
            return List.of();
        }

        // 2. 获取这些 traceId 的所有日志（排除大字段）
        return requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .select(RequestLog.class, column ->
                                !"requestHeaders".equals(column.getProperty())
                                        && !"requestBody".equals(column.getProperty()))
                        .in(RequestLog::getTraceId, traceIds)
                        .orderByAsc(RequestLog::getCreatedAt));
    }

    /**
     * 获取去重后的 traceId 总数
     */
    public long getTraceCount() {
        return requestLogMapper.countDistinctTraces();
    }

    /**
     * 分页获取日志（带条件过滤）
     * <p>
     * 列表查询排除 {@code request_headers} 和 {@code request_body} 两个大字段，
     * 避免列表加载时传输大量原始请求数据导致响应慢。原始请求数据通过
     * {@link #getRequestDataByLogId(Long)} 按需加载。
     * </p>
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
        List<String> traceIds = requestLogMapper.selectTraceIdsByFilters(modelName, gatewayApiKeyId, apiKeyName, startTime, endTime, offset, limit);
        if (traceIds.isEmpty()) {
            return List.of();
        }
        return requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .select(RequestLog.class, column ->
                                !"requestHeaders".equals(column.getProperty())
                                        && !"requestBody".equals(column.getProperty()))
                        .in(RequestLog::getTraceId, traceIds)
                        .orderByAsc(RequestLog::getCreatedAt));
    }

    /** 兼容旧调用：仅传 apiKeyName */
    public List<RequestLog> getFilteredLogsByPage(int offset, int limit,
                                                  String modelName,
                                                  String apiKeyName,
                                                  LocalDateTime startTime,
                                                  LocalDateTime endTime) {
        return getFilteredLogsByPage(offset, limit, modelName, null, apiKeyName, startTime, endTime);
    }

    /**
     * 获取过滤后的 traceId 总数
     */
    public long getFilteredTraceCount(String modelName,
                                      Long gatewayApiKeyId,
                                      String apiKeyName,
                                      LocalDateTime startTime,
                                      LocalDateTime endTime) {
        return requestLogMapper.countDistinctTracesByFilters(modelName, gatewayApiKeyId, apiKeyName, startTime, endTime);
    }

    /** 兼容旧调用：仅传 apiKeyName */
    public long getFilteredTraceCount(String modelName,
                                      String apiKeyName,
                                      LocalDateTime startTime,
                                      LocalDateTime endTime) {
        return getFilteredTraceCount(modelName, null, apiKeyName, startTime, endTime);
    }

    /**
     * 根据追踪 ID 获取日志
     */
    public List<RequestLog> getByTraceId(String traceId) {
        return requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .eq(RequestLog::getTraceId, traceId)
                        .orderByAsc(RequestLog::getCreatedAt));
    }

    /**
     * 按主键获取原始请求数据（requestHeaders / requestBody）
     * <p>
     * 用于前端"查看原始请求"的按需加载。仅返回 requestHeaders 和 requestBody 字段，
     * 避免在列表查询中传输大字段导致响应缓慢。
     * </p>
     *
     * @param logId 日志主键
     * @return 仅包含 requestHeaders 和 requestBody 的 RequestLog 对象，不存在时返回 null
     */
    public RequestLog getRequestDataByLogId(Long logId) {
        return requestLogMapper.selectOne(
                new LambdaQueryWrapper<RequestLog>()
                        .select(RequestLog::getRequestHeaders, RequestLog::getRequestBody)
                        .eq(RequestLog::getId, logId));
    }

    /**
     * 清理过期日志
     */
    public void cleanOldLogs(int retainDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retainDays);
        requestLogMapper.delete(
                new LambdaQueryWrapper<RequestLog>()
                        .lt(RequestLog::getCreatedAt, cutoff));
    }

    /**
     * 清理过期的原始请求数据（request_headers / request_body） - 旧接口，统一 TTL
     * <p>
     * 使用同一个 TTL 值处理所有记录（包括重试/失败和普通记录）。
     * 内部委托给 {@link #cleanExpiredRequestData(int, int)}。
     * </p>
     *
     * @param ttlHours 原始请求数据保留时长（小时），<=0 表示永久保留不清除
     */
    public void cleanExpiredRequestData(int ttlHours) {
        cleanExpiredRequestData(ttlHours, ttlHours);
    }

    /**
     * 清理过期的原始请求数据（request_headers / request_body） - 新接口，区分重试/失败与普通记录
     * <p>
     * 根据配置的 TTL（小时）将超过时长的记录的 request_headers 和 request_body 置为 NULL，
     * 保留日志条目本身（trace 链路仍可正常展示），仅清除原始的请求头和请求体数据。
     * </p>
     * <p>
     * 区分两种记录的清理策略：
     * <ul>
     *   <li><b>重试/失败记录</b>（retry_index > 0 或 phase = 'fail' 或 status = 'error'）：
     *   使用 retryFailTtlHours，默认较长（48h），便于调试排查</li>
     *   <li><b>普通记录</b>（首次请求且成功的记录）：
     *   使用 ttlHours，默认较短（4h）</li>
     * </ul>
     * </p>
     * <p>
     * 处理流程：
     * <ol>
     *   <li>若两个 TTL 均 <= 0，则跳过本次清理（永久保留原始请求数据）</li>
     *   <li>若 retryFailTtlHours > 0，清理重试/失败记录中超过该时长的原始请求数据</li>
     *   <li>若 ttlHours > 0，清理普通记录中超过该时长的原始请求数据</li>
     * </ol>
     * </p>
     *
     * @param ttlHours         普通原始请求数据保留时长（小时），<=0 表示永久保留不清除
     * @param retryFailTtlHours 重试/失败请求数据保留时长（小时），<=0 表示永久保留不清除
     */
    public void cleanExpiredRequestData(int ttlHours, int retryFailTtlHours) {
        // 处理重试/失败记录的原始请求数据
        if (retryFailTtlHours > 0) {
            cleanExpiredRequestDataBatch(retryFailTtlHours, true);
        } else {
            log.debug("重试/失败请求数据永久保留（retryFailTtlHours={}），跳过清理", retryFailTtlHours);
        }

        // 处理普通记录的原始请求数据
        if (ttlHours > 0) {
            cleanExpiredRequestDataBatch(ttlHours, false);
        } else {
            log.debug("普通请求数据永久保留（ttlHours={}），跳过清理", ttlHours);
        }
    }

    /**
     * 构建清理查询条件
     * <p>
     * 每次调用返回全新的 wrapper 实例，避免 {@link com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper#last} 重复叠加。
     * </p>
     *
     * @param cutoff      截止时间
     * @param isRetryFail true=重试/失败记录，false=普通记录
     * @return 新的查询条件
     */
    private LambdaQueryWrapper<RequestLog> buildCleanupWrapper(LocalDateTime cutoff, boolean isRetryFail) {
        LambdaQueryWrapper<RequestLog> wrapper = new LambdaQueryWrapper<RequestLog>()
                .lt(RequestLog::getCreatedAt, cutoff)
                .and(w -> w.isNotNull(RequestLog::getRequestHeaders)
                        .or().isNotNull(RequestLog::getRequestBody));

        if (isRetryFail) {
            // 重试/失败记录：retry_index > 0 OR phase = 'fail' OR status = 'error'
            wrapper.and(w -> w.gt(RequestLog::getRetryIndex, 0)
                    .or().eq(RequestLog::getPhase, "fail")
                    .or().eq(RequestLog::getStatus, "error"));
        } else {
            // 普通记录：NOT (retry_index > 0 OR phase = 'fail' OR status = 'error')
            wrapper.and(w -> w.and(w2 -> w2.isNull(RequestLog::getRetryIndex)
                            .or().eq(RequestLog::getRetryIndex, 0))
                    .and(w2 -> w2.isNull(RequestLog::getPhase)
                            .or().ne(RequestLog::getPhase, "fail"))
                    .and(w2 -> w2.isNull(RequestLog::getStatus)
                            .or().ne(RequestLog::getStatus, "error")));
        }
        return wrapper;
    }

    private void cleanExpiredRequestDataBatch(int ttlHours, boolean isRetryFail) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ttlHours);
        int batchSize = 100;
        int totalCleaned = 0;

        while (true) {
            List<RequestLog> expired = requestLogMapper.selectList(
                    buildCleanupWrapper(cutoff, isRetryFail).last("LIMIT " + batchSize));

            if (expired.isEmpty()) break;

            List<Long> ids = expired.stream().map(RequestLog::getId).collect(Collectors.toList());
            requestLogMapper.update(null, new LambdaUpdateWrapper<RequestLog>()
                    .in(RequestLog::getId, ids)
                    .set(RequestLog::getRequestHeaders, null)
                    .set(RequestLog::getRequestBody, null));
            totalCleaned += expired.size();
        }

        if (totalCleaned > 0) {
            String label = isRetryFail ? "重试/失败" : "普通";
            log.info("{}原始请求数据清理完成：共清理 {} 条（TTL={}h, cutoff={}）",
                    label, totalCleaned, ttlHours, cutoff);
        }
    }
}
