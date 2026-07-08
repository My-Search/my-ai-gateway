package com.myai.gateway.relay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LatencyTracker 单元测试
 * 验证 EMA 计算、自适应超时（ema×3, 20s~60s）、样本数阈值（5）
 */
class LatencyTrackerTest {

    private LatencyTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new LatencyTracker(null); // null AdminConfigService → 使用硬编码默认值
    }

    @Test
    void getTimeout_returnsDefaultWhenNoData() {
        long timeout = tracker.getTimeout(1L, 100L);
        // 无样本时返回默认 60s
        assertThat(timeout).isEqualTo(60_000L);
    }

    @Test
    void getTimeout_returnsDefaultWhenSampleCountNotExceedThreshold() {
        // 1 ~ 5 个样本时，样本数 ≤ 阈值（5），应返回默认 60s
        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);

        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);

        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);

        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);

        tracker.record(1L, 100L, 5_000L); // 第5个，仍未超过阈值
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);
    }

    @Test
    void getTimeout_usesAdaptiveValueAfterThreshold() {
        // 6 个样本后（>5），应使用自适应超时
        for (int i = 0; i < 6; i++) {
            tracker.record(1L, 100L, 5_000L); // ema ≈ 5000
        }

        long timeout = tracker.getTimeout(1L, 100L);
        // clamp(5000*3=15000, 20000, 60000) = 20000（受最小超时 20s 限制）
        assertThat(timeout).isEqualTo(20_000L);
    }

    @Test
    void getTimeout_adaptiveFormula_emaTimes3() {
        // 6 个样本，ema ≈ 10000
        for (int i = 0; i < 6; i++) {
            tracker.record(1L, 100L, 10_000L);
        }

        long timeout = tracker.getTimeout(1L, 100L);
        // clamp(10000*3=30000, 20000, 60000) = 30000
        assertThat(timeout).isEqualTo(30_000L);
    }

    @Test
    void getTimeout_capsAtMax() {
        // 6 个高延迟样本，ema ≈ 30000
        for (int i = 0; i < 6; i++) {
            tracker.record(1L, 100L, 30_000L);
        }

        long timeout = tracker.getTimeout(1L, 100L);
        // clamp(30000*3=90000, 20000, 60000) = 60000（受最大超时 60s 限制）
        assertThat(timeout).isEqualTo(60_000L);
    }

    @Test
    void getTimeout_hasMinFloor() {
        // 6 个极低值样本（会被 clamp 到 MIN_LATENCY_MS=2500）
        for (int i = 0; i < 6; i++) {
            tracker.record(1L, 100L, 500L);
        }

        long timeout = tracker.getTimeout(1L, 100L);
        // ema ≈ 2500 → 2500*3=7500 → clamp(7500, 20000, 60000) = 20000
        assertThat(timeout).isEqualTo(20_000L);
    }

    @Test
    void ema_smoothOverMultipleRecords() {
        tracker.record(1L, 100L, 10_000L); // ema=10000
        tracker.record(1L, 100L, 20_000L); // ema=0.3*20000 + 0.7*10000 = 13000
        tracker.record(1L, 100L, 5_000L);  // ema=0.3*5000 + 0.7*13000 = 10600
        tracker.record(1L, 100L, 10_000L); // ema=0.3*10000 + 0.7*10600 = 10420
        tracker.record(1L, 100L, 10_000L); // ema=0.3*10000 + 0.7*10420 = 10294
        tracker.record(1L, 100L, 10_000L); // ema=0.3*10000 + 0.7*10294 = 10206（第6个，超过阈值）

        long timeout = tracker.getTimeout(1L, 100L);
        // ema≈10205.8 → clamp(floor(10205.8)*3=30615, 20000, 60000) = 30615
        assertThat(timeout).isEqualTo(30_615L);
    }

    @Test
    void recordTimeout_increasesTimeoutForNextCall() {
        // 累积 6 个样本使自适应生效
        for (int i = 0; i < 6; i++) {
            tracker.record(1L, 100L, 5_000L); // ema=5000, timeout=20000
        }

        // 超时发生：记录实际 timeout 值
        tracker.recordTimeout(1L, 100L, 20_000L);
        // 新的 ema = 0.3*20000 + 0.7*5000 = 9500
        // timeout = clamp(9500*3=28500, 20000, 60000) = 28500
        long newTimeout = tracker.getTimeout(1L, 100L);
        assertThat(newTimeout).isGreaterThan(20_000L); // 窗口扩大
        assertThat(newTimeout).isEqualTo(28_500L);
    }

    @Test
    void getLatency_returnsDefaultWhenNoData() {
        assertThat(tracker.getLatency(99L, 999L)).isEqualTo(60_000L);
    }

    @Test
    void recordNullChannelId_doesNothing() {
        // 不应抛异常
        tracker.record(null, 100L, 5_000L);
        tracker.record(1L, null, 5_000L);
        tracker.recordTimeout(null, 100L, 5_000L);
        tracker.recordTimeout(1L, null, 5_000L);
        assertThat(tracker.size()).isEqualTo(0);
    }

    @Test
    void differentChannelModelPairs_areIndependent() {
        // 每个 pair 累积 6 个样本使自适应生效
        for (int i = 0; i < 6; i++) {
            tracker.record(1L, 100L, 5_000L);
            tracker.record(2L, 200L, 20_000L);
        }

        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(20_000L); // 5*3=15 → 20(min)
        assertThat(tracker.getTimeout(2L, 200L)).isEqualTo(60_000L); // 20*3=60 → 60(max)
        assertThat(tracker.size()).isEqualTo(2);
    }

    @Test
    void record_lowerBoundClamp() {
        // 1ms 被 clamp 到 MIN_LATENCY_MS=2500
        tracker.record(1L, 100L, 1L);
        assertThat(tracker.getLatency(1L, 100L)).isEqualTo(2_500L);
    }

    @Test
    void reset_clearsAllData() {
        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.size()).isEqualTo(1);
        tracker.reset();
        assertThat(tracker.size()).isEqualTo(0);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);
    }

    // ==================== 可配置超时上下限 ====================

    @Test
    void getMinTimeoutMs_returnsDefaultWhenServiceIsNull() {
        assertThat(tracker.getMinTimeoutMs()).isEqualTo(20_000L);
    }

    @Test
    void getMaxTimeoutMs_returnsDefaultWhenServiceIsNull() {
        assertThat(tracker.getMaxTimeoutMs()).isEqualTo(60_000L);
    }

    @Test
    void getTimeout_usesDefaultBounds() {
        // 6 个低延迟样本 → ema≈5000 → timeout=clamp(5000*3=15000, 20000, 60000)=20000
        for (int i = 0; i < 6; i++) {
            tracker.record(1L, 100L, 5_000L);
        }
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(20_000L);

        // 6 个高延迟样本 → ema≈30000 → timeout=clamp(30000*3=90000, 20000, 60000)=60000
        for (int i = 0; i < 6; i++) {
            tracker.record(2L, 200L, 30_000L);
        }
        assertThat(tracker.getTimeout(2L, 200L)).isEqualTo(60_000L);
    }

    @Test
    void key_withLargeIds_worksCorrectly() {
        // 验证大 ID 不会因 32-bit 溢出导致碰撞，每个 pair 累积 6 个样本
        for (int i = 0; i < 6; i++) {
            tracker.record(Integer.MAX_VALUE + 1L, 999L, 5_000L);
            tracker.record(1L, Integer.MAX_VALUE + 1L, 10_000L);
        }

        assertThat(tracker.getTimeout(Integer.MAX_VALUE + 1L, 999L)).isEqualTo(20_000L); // 5*3=15 → 20(min)
        assertThat(tracker.getTimeout(1L, Integer.MAX_VALUE + 1L)).isEqualTo(30_000L);   // 10*3=30
        assertThat(tracker.size()).isEqualTo(2);
    }
}
