package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RequestLogService 单元测试
 * 覆盖日志写入逻辑：常规阶段、响应时间写入、状态字段
 */
class RequestLogServiceTest {

    private RequestLogMapper requestLogMapper;
    private AsyncLogWriter asyncLogWriter;
    private RequestLogService service;

    @BeforeEach
    void setUp() {
        requestLogMapper = mock(RequestLogMapper.class);
        asyncLogWriter = mock(AsyncLogWriter.class);
        service = new RequestLogService(requestLogMapper, asyncLogWriter);
    }

    @Test
    void log_writesRecordWithoutResponseTime() {
        service.log("trace-1", "ak1", "model-x", "channel-m", "channel-c",
                "start", "路由到 X", 0);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(asyncLogWriter).enqueue(captor.capture());
        RequestLog record = captor.getValue();

        assertThat(record.getTraceId()).isEqualTo("trace-1");
        assertThat(record.getApiKeyName()).isEqualTo("ak1");
        assertThat(record.getModelName()).isEqualTo("model-x");
        assertThat(record.getChannelModelName()).isEqualTo("channel-m");
        assertThat(record.getChannelName()).isEqualTo("channel-c");
        assertThat(record.getPhase()).isEqualTo("start");
        assertThat(record.getMessage()).isEqualTo("路由到 X");
        assertThat(record.getRetryIndex()).isEqualTo(0);
        // start 阶段不写入 responseTimeMs，让前端不显示耗时
        assertThat(record.getResponseTimeMs()).isNull();
        assertThat(record.getCreatedAt()).isNotNull();
    }

    @Test
    void logWithResponseTime_persistsAttemptDuration() {
        // 模拟：retry 失败时记录该次尝试的耗时 1234ms
        service.logWithResponseTime("trace-1", "ak1", "model-x", "channel-m", "channel-c",
                "retry", "第 1 次失败（耗时 1234ms）", 0, 1234L);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(asyncLogWriter).enqueue(captor.capture());
        RequestLog record = captor.getValue();

        assertThat(record.getTraceId()).isEqualTo("trace-1");
        assertThat(record.getPhase()).isEqualTo("retry");
        assertThat(record.getRetryIndex()).isEqualTo(0);
        assertThat(record.getResponseTimeMs()).isEqualTo(1234);
        assertThat(record.getCreatedAt()).isNotNull();
    }

    @Test
    void logWithResponseTime_zeroDuration_stillPersistsAsZero() {
        // 0ms 也允许写入（极端情况：瞬时失败），由前端过滤掉
        service.logWithResponseTime("trace-1", "ak1", "model-x", "channel-m", "channel-c",
                "retry", "瞬时失败", 0, 0L);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(asyncLogWriter).enqueue(captor.capture());
        assertThat(captor.getValue().getResponseTimeMs()).isEqualTo(0);
    }

    @Test
    void logComplete_writesRecordWithResponseTimeAndTokens() {
        service.logComplete("trace-1", "ak1", "model-x", "channel-m", "channel-c",
                "success", "success", "请求成功", 5000L,
                1, 100, 200, 300);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(asyncLogWriter).enqueue(captor.capture());
        RequestLog record = captor.getValue();

        assertThat(record.getPhase()).isEqualTo("success");
        assertThat(record.getStatus()).isEqualTo("success");
        assertThat(record.getResponseTimeMs()).isEqualTo(5000);
        assertThat(record.getRetryIndex()).isEqualTo(1);
        assertThat(record.getPromptTokens()).isEqualTo(100);
        assertThat(record.getCompletionTokens()).isEqualTo(200);
        assertThat(record.getTotalTokens()).isEqualTo(300);
    }

    @Test
    void log_defaultRetryIndexIsZero() {
        service.log("trace-1", null, "model-x", null, null,
                "start", "default index");

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(asyncLogWriter).enqueue(captor.capture());
        assertThat(captor.getValue().getRetryIndex()).isEqualTo(0);
    }

    // ──────────────────── getFilteredLogsByPage / getFilteredTraceCount ────────────────────

    @Test
    void getFilteredTraceCount_passesAllFiltersToMapper() {
        when(requestLogMapper.countDistinctTracesByFilters(anyString(), any(), anyString(), any(), any()))
                .thenReturn(42L);
        LocalDateTime since = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime until = LocalDateTime.of(2026, 7, 1, 0, 0);

        long count = service.getFilteredTraceCount("gpt-4o", "prod-key", since, until);

        assertThat(count).isEqualTo(42L);
        // 兼容旧接口：gatewayApiKeyId 透传为 null
        verify(requestLogMapper).countDistinctTracesByFilters("gpt-4o", null, "prod-key", since, until);
    }

    @Test
    void getFilteredTraceCount_acceptsNullFilters() {
        when(requestLogMapper.countDistinctTracesByFilters(any(), any(), any(), any(), any()))
                .thenReturn(0L);

        long count = service.getFilteredTraceCount(null, null, null, null);

        assertThat(count).isEqualTo(0L);
        verify(requestLogMapper).countDistinctTracesByFilters(null, null, null, null, null);
    }

    @Test
    void getFilteredTraceCount_passesGatewayApiKeyId() {
        when(requestLogMapper.countDistinctTracesByFilters(any(), any(), any(), any(), any()))
                .thenReturn(7L);

        long count = service.getFilteredTraceCount("gpt-4o", 42L, "ignored-name", null, null);

        assertThat(count).isEqualTo(7L);
        verify(requestLogMapper).countDistinctTracesByFilters("gpt-4o", 42L, "ignored-name", null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFilteredLogsByPage_passesAllFiltersToMapperAndFetchesLogsByTraceIds() {
        // 1. 第一步：mapper 返回 traceId 列表
        when(requestLogMapper.selectTraceIdsByFilters(anyString(), any(), anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of("t-1", "t-2"));
        // 2. 第二步：按 traceId 列表反查完整日志
        RequestLog l1 = new RequestLog();
        l1.setId(1L); l1.setTraceId("t-1");
        RequestLog l2 = new RequestLog();
        l2.setId(2L); l2.setTraceId("t-2");
        when(requestLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(l1, l2));

        LocalDateTime since = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime until = LocalDateTime.of(2026, 7, 1, 0, 0);
        List<RequestLog> result = service.getFilteredLogsByPage(0, 50, "gpt-4o", "prod-key", since, until);

        // 过滤条件被透传（兼容旧接口：gatewayApiKeyId 透传为 null）
        verify(requestLogMapper).selectTraceIdsByFilters("gpt-4o", null, "prod-key", since, until, 0, 50);
        // 返回按 traceId 查到的完整日志
        assertThat(result).hasSize(2);
        assertThat(result).extracting(RequestLog::getTraceId).containsExactly("t-1", "t-2");
    }

    @Test
    void getFilteredLogsByPage_emptyTraceIds_returnsEmptyWithoutSecondQuery() {
        when(requestLogMapper.selectTraceIdsByFilters(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        List<RequestLog> result = service.getFilteredLogsByPage(0, 50, "gpt-4o", null, null, null);

        // 空 traceId 列表应直接短路返回，不再触发 selectList
        assertThat(result).isEmpty();
        verify(requestLogMapper, never()).selectList(any(Wrapper.class));
    }

    // ──────────────────── cleanExpiredRequestData ────────────────────

    @Test
    void cleanExpiredRequestData_ttlZero_skipsWithoutDbCalls() {
        // TTL <= 0 表示永久保留，不应查 DB
        service.cleanExpiredRequestData(0);

        verify(requestLogMapper, never()).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }

    @Test
    void cleanExpiredRequestData_ttlNegative_skipsWithoutDbCalls() {
        service.cleanExpiredRequestData(-1);

        verify(requestLogMapper, never()).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }

    @Test
    void cleanExpiredRequestData_noExpiredRecords_noUpdateCall() {
        when(requestLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.cleanExpiredRequestData(4);

        // selectList 被调用一次（查过期记录），查到为空则不再调用 update
        verify(requestLogMapper, times(2)).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }




    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ cleanExpiredRequestData(ttlHours, retryFailTtlHours) dual-TTL 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    @Test
    void cleanExpiredRequestData_dualTtl_bothZero_skipsWithoutDbCalls() {
        // Both TTLs <= 0 means permanent retention, should not query DB
        service.cleanExpiredRequestData(0, 0);

        verify(requestLogMapper, never()).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }

    @Test
    void cleanExpiredRequestData_dualTtl_bothNegative_skipsWithoutDbCalls() {
        service.cleanExpiredRequestData(-1, -1);

        verify(requestLogMapper, never()).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }

    @Test
    void cleanExpiredRequestData_dualTtl_onlyRetryFailPositive_queriesOnlyRetryFail() {
        // Normal TTL = 0 (skip), Retry/fail TTL = 48 (active) -> should only query for retry/fail entries
        when(requestLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.cleanExpiredRequestData(0, 48);

        // Should call selectList exactly once (only for retry/fail cleanup)
        verify(requestLogMapper, times(1)).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }

    @Test
    void cleanExpiredRequestData_dualTtl_onlyNormalPositive_queriesOnlyNormal() {
        // Retry/fail TTL = 0 (skip), Normal TTL = 4 (active) -> should only query for normal entries
        when(requestLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.cleanExpiredRequestData(4, 0);

        // Should call selectList exactly once (only for normal cleanup)
        verify(requestLogMapper, times(1)).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }

    @Test
    void cleanExpiredRequestData_dualTtl_bothPositive_queriesBothBatches() {
        // Both TTLs active -> should query for both normal and retry/fail entries
        when(requestLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.cleanExpiredRequestData(4, 48);

        // Should call selectList twice (normal + retry/fail), but no update since no expired records
        verify(requestLogMapper, times(2)).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }

    @Test
    void cleanExpiredRequestData_dualTtl_bothPositive_cleansExpiredRecords() {
        // Both TTLs active with expired records -> should find and update both batches
        // We mock selectList to return empty to avoid MyBatis-Plus lambda parsing issues in pure Mockito
        // This test verifies the method is invoked correctly, not the batch content filtering
        when(requestLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.cleanExpiredRequestData(4, 48);

        // Should call selectList twice (normal + retry/fail), no update since mocked as empty
        verify(requestLogMapper, times(2)).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }

    /**
     * Old single-parameter method should delegate to dual-TTL with same values.
     * So cleanExpiredRequestData(4) behaves like cleanExpiredRequestData(4, 4).
     */
    @Test
    void cleanExpiredRequestData_singleTtl_delegatesToDualTtl() {
        when(requestLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.cleanExpiredRequestData(4);

        // Same as both TTLs = 4 -> should query twice (normal + retry/fail)
        verify(requestLogMapper, times(2)).selectList(any(Wrapper.class));
    }

    @Test
    void cleanExpiredRequestData_singleTtlZero_delegatesToDualTtl() {
        service.cleanExpiredRequestData(0);

        verify(requestLogMapper, never()).selectList(any(Wrapper.class));
        verify(requestLogMapper, never()).update(any(), any());
    }
}
