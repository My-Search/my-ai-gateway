package com.myai.gateway.schedule;

import com.myai.gateway.service.AdminConfigService;
import com.myai.gateway.service.CircuitBreakerRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 熔断门恢复定时任务（轻量调度器）
 * <p>仅负责按配置间隔触发全量探测，实际处理在 {@link CircuitBreakerRecoveryService#scanExpiredGates()}
 * 中异步投递执行（单常驻工作线程 + 队列唤醒），不阻塞本调度线程。</p>
 *
 * <ul>
 *   <li>tick 间隔 60s：检查是否到全量扫描时间。</li>
 *   <li>全量扫描间隔取系统配置 {@code circuit_breaker_probe_interval_minutes}（默认 30 分钟）。</li>
 *   <li>应用启动后立即执行一次全量扫描（lastFullScan 初始为空），之后按配置间隔执行。</li>
 *   <li>调用时触发式探测见 {@link CircuitBreakerRecoveryService#triggerProbeByChannel}，
 *       不依赖本任务。</li>
 * </ul>
 */
@Component
public class CircuitBreakerRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRecoveryTask.class);

    /** tick 间隔（毫秒）：fixedDelay，上一轮完成后隔这么久再跑 */
    private static final long TICK_INTERVAL_MS = 60_000L;

    private final CircuitBreakerRecoveryService recoveryService;
    private final AdminConfigService adminConfigService;

    /** 上次全量扫描时间（null=尚未执行，启动后立即扫描一次） */
    private volatile Instant lastFullScanAt = null;

    public CircuitBreakerRecoveryTask(CircuitBreakerRecoveryService recoveryService,
                                      AdminConfigService adminConfigService) {
        this.recoveryService = recoveryService;
        this.adminConfigService = adminConfigService;
    }

    @Scheduled(fixedDelay = TICK_INTERVAL_MS)
    public void tick() {
        int intervalMinutes = adminConfigService.getCircuitBreakerProbeIntervalMinutes();
        Instant now = Instant.now();
        if (lastFullScanAt != null
                && Duration.between(lastFullScanAt, now).toMinutes() < intervalMinutes) {
            return; // 未到全量扫描时间
        }
        lastFullScanAt = now;
        log.info("熔断全量探测触发 - 间隔{}分钟", intervalMinutes);
        recoveryService.scanExpiredGates();
    }
}
