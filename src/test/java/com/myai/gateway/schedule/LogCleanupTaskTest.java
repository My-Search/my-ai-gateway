package com.myai.gateway.schedule;

import com.myai.gateway.service.AdminConfigService;
import com.myai.gateway.service.RequestLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * LogCleanupTask 单元测试
 * 覆盖原始请求数据保留时长的解析与跟随语义：
 * request_body_ttl_hours 为 0 时跟随日志保留天数（天数×24）；
 * retry_fail_ttl_hours 为空时跟随 request_body_ttl_hours，为 0 时同样跟随日志保留天数
 */
class LogCleanupTaskTest {

    private AdminConfigService adminConfigService;
    private RequestLogService requestLogService;
    private LogCleanupTask task;

    @BeforeEach
    void setUp() {
        adminConfigService = mock(AdminConfigService.class);
        requestLogService = mock(RequestLogService.class);
        task = new LogCleanupTask(adminConfigService, requestLogService);
    }

    private void stubConfig(String retentionDays, String requestBodyTtl, String retryFailTtl) {
        when(adminConfigService.getValueByKey(AdminConfigService.KEY_LOG_CLEANUP_ENABLED)).thenReturn("1");
        when(adminConfigService.getValueByKey(AdminConfigService.KEY_LOG_RETENTION_DAYS)).thenReturn(retentionDays);
        when(adminConfigService.getValueByKey(AdminConfigService.KEY_REQUEST_BODY_TTL_HOURS)).thenReturn(requestBodyTtl);
        when(adminConfigService.getValueByKey(AdminConfigService.KEY_RETRY_FAIL_TTL_HOURS)).thenReturn(retryFailTtl);
    }

    @Test
    void retryFailTtlEmpty_followsRequestBodyTtl() {
        stubConfig("7", "4", "");

        task.cleanExpiredData();

        verify(requestLogService).cleanOldLogs(7);
        verify(requestLogService).cleanExpiredRequestData(4, 4);
    }

    @Test
    void retryFailTtlBlank_followsRequestBodyTtl() {
        stubConfig("7", "4", "  ");

        task.cleanExpiredData();

        verify(requestLogService).cleanExpiredRequestData(4, 4);
    }

    @Test
    void retryFailTtlZero_followsLogRetentionDays() {
        stubConfig("7", "4", "0");

        task.cleanExpiredData();

        verify(requestLogService).cleanExpiredRequestData(4, 7 * 24);
    }

    @Test
    void retryFailTtlPositive_usedAsIs() {
        stubConfig("7", "4", "12");

        task.cleanExpiredData();

        verify(requestLogService).cleanExpiredRequestData(4, 12);
    }

    @Test
    void retryFailTtlInvalid_fallsBackToLogRetentionDays() {
        stubConfig("7", "4", "abc");

        task.cleanExpiredData();

        verify(requestLogService).cleanExpiredRequestData(4, 7 * 24);
    }

    @Test
    void requestBodyTtlZero_followsLogRetentionDays() {
        stubConfig("7", "0", "");

        task.cleanExpiredData();

        // 普通 TTL 为 0 跟随日志保留天数，重试/失败留空跟随普通 TTL，两者一致
        verify(requestLogService).cleanExpiredRequestData(7 * 24, 7 * 24);
    }

    @Test
    void requestBodyTtlInvalid_fallsBackToLogRetentionDays() {
        stubConfig("7", "abc", "5");

        task.cleanExpiredData();

        verify(requestLogService).cleanExpiredRequestData(7 * 24, 5);
    }

    @Test
    void cleanupDisabled_skipsAll() {
        when(adminConfigService.getValueByKey(AdminConfigService.KEY_LOG_CLEANUP_ENABLED)).thenReturn("0");

        task.cleanExpiredData();

        verify(requestLogService, never()).cleanOldLogs(anyInt());
        verify(requestLogService, never()).cleanExpiredRequestData(anyInt(), anyInt());
    }
}
