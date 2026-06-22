package com.myai.gateway.service;

import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RequestLogService 单元测试
 * 覆盖日志写入逻辑：常规阶段、响应时间写入、状态字段
 */
class RequestLogServiceTest {

    private RequestLogMapper requestLogMapper;
    private LogSseService logSseService;
    private RequestLogService service;

    @BeforeEach
    void setUp() {
        requestLogMapper = mock(RequestLogMapper.class);
        logSseService = mock(LogSseService.class);
        service = new RequestLogService(requestLogMapper, logSseService);
    }

    @Test
    void log_writesRecordWithoutResponseTime() {
        service.log("trace-1", "ak1", "model-x", "channel-m", "channel-c",
                "start", "路由到 X", 0);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogMapper).insert(captor.capture());
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
        // 推送到 SSE
        verify(logSseService).publish(record);
    }

    @Test
    void logWithResponseTime_persistsAttemptDuration() {
        // 模拟：retry 失败时记录该次尝试的耗时 1234ms
        service.logWithResponseTime("trace-1", "ak1", "model-x", "channel-m", "channel-c",
                "retry", "第 1 次失败（耗时 1234ms）", 0, 1234L);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogMapper).insert(captor.capture());
        RequestLog record = captor.getValue();

        assertThat(record.getTraceId()).isEqualTo("trace-1");
        assertThat(record.getPhase()).isEqualTo("retry");
        assertThat(record.getRetryIndex()).isEqualTo(0);
        assertThat(record.getResponseTimeMs()).isEqualTo(1234);
        assertThat(record.getCreatedAt()).isNotNull();
        verify(logSseService).publish(record);
    }

    @Test
    void logWithResponseTime_zeroDuration_stillPersistsAsZero() {
        // 0ms 也允许写入（极端情况：瞬时失败），由前端过滤掉
        service.logWithResponseTime("trace-1", "ak1", "model-x", "channel-m", "channel-c",
                "retry", "瞬时失败", 0, 0L);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getResponseTimeMs()).isEqualTo(0);
    }

    @Test
    void logComplete_writesRecordWithResponseTimeAndTokens() {
        service.logComplete("trace-1", "ak1", "model-x", "channel-m", "channel-c",
                "success", "success", "请求成功", 5000L,
                1, 100, 200, 300);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogMapper).insert(captor.capture());
        RequestLog record = captor.getValue();

        assertThat(record.getPhase()).isEqualTo("success");
        assertThat(record.getStatus()).isEqualTo("success");
        assertThat(record.getResponseTimeMs()).isEqualTo(5000);
        assertThat(record.getRetryIndex()).isEqualTo(1);
        assertThat(record.getPromptTokens()).isEqualTo(100);
        assertThat(record.getCompletionTokens()).isEqualTo(200);
        assertThat(record.getTotalTokens()).isEqualTo(300);
        verify(logSseService).publish(record);
    }

    @Test
    void log_defaultRetryIndexIsZero() {
        service.log("trace-1", null, "model-x", null, null,
                "start", "default index");

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getRetryIndex()).isEqualTo(0);
    }
}
