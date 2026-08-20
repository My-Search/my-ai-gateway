package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 请求日志清理 - 过期日志删除与原请求数据 TTL 清理
 * <p>
 * 由 {@link RequestLogService} 组合调用（非 Spring Bean，随宿主实例化），聚焦"清理/保留策略"单一职责。
 * </p>
 */
public class RequestLogCleanupSupport {

    private static final Logger log = LoggerFactory.getLogger(RequestLogCleanupSupport.class);

    private final RequestLogMapper requestLogMapper;

    public RequestLogCleanupSupport(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
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
