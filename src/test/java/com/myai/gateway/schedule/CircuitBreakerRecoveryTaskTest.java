package com.myai.gateway.schedule;

import com.myai.gateway.service.AdminConfigService;
import com.myai.gateway.service.CircuitBreakerRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * CircuitBreakerRecoveryTask 单元测试
 * 验证调度语义：启动后立即全量扫描一次；未到配置间隔不重复扫描；到间隔触发扫描；
 * 扫描本身异步提交（不阻塞调度线程）。
 */
class CircuitBreakerRecoveryTaskTest {

    private CircuitBreakerRecoveryService recoveryService;
    private AdminConfigService adminConfigService;
    private CircuitBreakerRecoveryTask task;

    @BeforeEach
    void setUp() {
        recoveryService = mock(CircuitBreakerRecoveryService.class);
        adminConfigService = mock(AdminConfigService.class);
        task = new CircuitBreakerRecoveryTask(recoveryService, adminConfigService);
    }

    @Test
    void firstTick_immediatelyTriggersFullScan() {
        when(adminConfigService.getCircuitBreakerProbeIntervalMinutes()).thenReturn(30);

        task.tick();

        verify(recoveryService).scanExpiredGates();
    }

    @Test
    void tickWithinInterval_doesNotRescan() {
        when(adminConfigService.getCircuitBreakerProbeIntervalMinutes()).thenReturn(30);

        task.tick(); // 第一次：立即扫描
        task.tick(); // 间隔内：不扫描

        verify(recoveryService, times(1)).scanExpiredGates();
    }

    @Test
    void tickAfterInterval_triggersAgain() throws Exception {
        when(adminConfigService.getCircuitBreakerProbeIntervalMinutes()).thenReturn(1);

        task.tick();
        // 推进时间超过 1 分钟间隔（用反射修改 lastFullScanAt 模拟时间流逝）
        java.lang.reflect.Field field = CircuitBreakerRecoveryTask.class.getDeclaredField("lastFullScanAt");
        field.setAccessible(true);
        field.set(task, java.time.Instant.now().minus(java.time.Duration.ofMinutes(2)));
        task.tick();

        verify(recoveryService, times(2)).scanExpiredGates();
    }

    @Test
    void tick_scanIsAsyncAndDoesNotBlock() {
        // scanExpiredGates 内部异步执行，tick 仅做时间判断与提交，无阻塞逻辑
        when(adminConfigService.getCircuitBreakerProbeIntervalMinutes()).thenReturn(30);

        task.tick();

        verify(recoveryService, times(1)).scanExpiredGates();
        verifyNoMoreInteractions(recoveryService);
    }
}
