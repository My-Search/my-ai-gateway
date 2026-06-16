package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 请求日志服务
 * 记录请求的完整生命周期，用于在日志中体现：
 * 开始 -> 重试 -> 重新路由 -> 失败/成功
 */
@Service
public class RequestLogService {

    private static final Logger log = LoggerFactory.getLogger(RequestLogService.class);

    private final RequestLogMapper requestLogMapper;
    private final LogSseService logSseService;

    public RequestLogService(RequestLogMapper requestLogMapper, LogSseService logSseService) {
        this.requestLogMapper = requestLogMapper;
        this.logSseService = logSseService;
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
     * @param channelModelName 渠道模型名
     * @param channelName  渠道名
     * @param phase        阶段: start / retry / reroute / success / fail
     * @param message      日志消息
     * @param retryIndex   重试索引（0=首次，>0=重试次数）
     */
    public void log(String traceId, String apiKeyName, String modelName,
                    String channelModelName, String channelName,
                    String phase, String message, int retryIndex) {
        RequestLog record = new RequestLog();
        record.setTraceId(traceId);
        record.setApiKeyName(apiKeyName);
        record.setModelName(modelName);
        record.setChannelModelName(channelModelName);
        record.setChannelName(channelName);
        record.setPhase(phase);
        record.setStatus("pending");
        record.setMessage(message);
        record.setRetryIndex(retryIndex);
        record.setCreatedAt(LocalDateTime.now());
        requestLogMapper.insert(record);
        logSseService.publish(record);

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
     * 记录请求完成（成功或最终失败），包含 token 用量
     */
    public void logComplete(String traceId, String apiKeyName, String modelName,
                            String channelModelName, String channelName,
                            String phase, String status, String message, long responseTimeMs,
                            int retryIndex, int promptTokens, int completionTokens, int totalTokens) {
        RequestLog record = new RequestLog();
        record.setTraceId(traceId);
        record.setApiKeyName(apiKeyName);
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
        requestLogMapper.insert(record);
        logSseService.publish(record);

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
     * 分页获取日志（按 traceId 级别分页，保证每组 trace 的日志完整）
     * @param offset 跳过的 traceId 数量
     * @param limit 返回的 traceId 数量
     * @return 完整的日志列表（trace 内按 createdAt 升序）
     */
    public List<RequestLog> getLogsByPage(int offset, int limit) {
        // 1. 获取分页后的 traceId 列表
        List<String> traceIds = requestLogMapper.selectTraceIdsByPage(offset, limit);
        if (traceIds.isEmpty()) {
            return List.of();
        }

        // 2. 获取这些 traceId 的所有日志
        return requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
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
     * 根据追踪 ID 获取日志
     */
    public List<RequestLog> getByTraceId(String traceId) {
        return requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .eq(RequestLog::getTraceId, traceId)
                        .orderByAsc(RequestLog::getCreatedAt));
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
}
