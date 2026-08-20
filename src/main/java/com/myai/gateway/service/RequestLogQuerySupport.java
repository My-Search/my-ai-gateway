package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 请求日志查询 - 分页/过滤/trace 明细/原始请求数据按需加载
 * <p>
 * 由 {@link RequestLogService} 组合调用（非 Spring Bean，随宿主实例化），聚焦"只读查询"单一职责。
 * </p>
 */
public class RequestLogQuerySupport {

    private final RequestLogMapper requestLogMapper;

    public RequestLogQuerySupport(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
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

    /**
     * 兼容旧调用：仅传 apiKeyName
     */
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

    /**
     * 兼容旧调用：仅传 apiKeyName
     */
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
}
