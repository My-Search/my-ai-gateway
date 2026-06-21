package com.myai.gateway.relay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LatencyTracker 单元测试
 * 验证 EMA 计算、自适应超时、超时记录、样本数阈值
 */
class LatencyTrackerTest {

    private LatencyTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new LatencyTracker();
    }

    @Test
    void getTimeout_returnsDefaultWhenNoData() {
        long timeout = tracker.getTimeout(1L, 100L);
        // 无样本时返回默认 40s
        assertThat(timeout).isEqualTo(40_000L);
    }

    @Test
    void getTimeout_returnsDefaultWhenSampleCountNotExceedThreshold() {
        // 1 ~ 3 个样本时，样本数 ≤ 阈值，应返回默认 40s（而非自适应值）
        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(40_000L);

        tracker.record(1L, 100L, 10_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(40_000L);

        tracker.record(1L, 100L, 15_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(40_000L);
    }

    @Test
    void getTimeout_usesAdaptiveValueAfterThreshold() {
        // 4 个样本后（>3），应使用自适应超时
        tracker.record(1L, 100L, 5_000L);
        tracker.record(1L, 100L, 5_000L);
        tracker.record(1L, 100L, 5_000L);
        tracker.record(1L, 100L, 5_000L); // 第4个，ema=5000

        long timeout = tracker.getTimeout(1L, 100L);
        // max(min(5000*2, 40000), 5000) = 10000
        assertThat(timeout).isEqualTo(10_000L);
    }

    @Test
    void getTimeout_capsAtMax() {
        // 4 个高延迟样本，使自适应超时生效
        tracker.record(1L, 100L, 30_000L);
        tracker.record(1L, 100L, 30_000L);
        tracker.record(1L, 100L, 30_000L);
        tracker.record(1L, 100L, 30_000L);

        long timeout = tracker.getTimeout(1L, 100L);
        // ema≈30000 → 30000*2=60000 > 40000, 但上限 40s
        assertThat(timeout).isEqualTo(40_000L);
    }

    @Test
    void getTimeout_hasMinFloor() {
        // 4 个极低值样本（会被 clamp 到 MIN_LATENCY_MS=2500）
        tracker.record(1L, 100L, 500L);
        tracker.record(1L, 100L, 500L);
        tracker.record(1L, 100L, 500L);
        tracker.record(1L, 100L, 500L);

        long timeout = tracker.getTimeout(1L, 100L);
        // min(max(2500*2, 5000), 40000) = 5000
        assertThat(timeout).isEqualTo(5_000L);
    }

    @Test
    void ema_smoothOverMultipleRecords() {
        tracker.record(1L, 100L, 10_000L); // ema=10000
        tracker.record(1L, 100L, 20_000L); // ema=0.3*20000 + 0.7*10000 = 13000
        tracker.record(1L, 100L, 5_000L);  // ema=0.3*5000 + 0.7*13000 = 10600
        tracker.record(1L, 100L, 10_000L); // ema=0.3*10000 + 0.7*10600 = 10420（第4个，超过阈值）

        long timeout = tracker.getTimeout(1L, 100L);
        // ema=10420 → max(min(10420*2, 40000), 5000) = 20840
        assertThat(timeout).isEqualTo(20_840L);
    }

    @Test
    void recordTimeout_increasesTimeoutForNextCall() {
        // 累积 4 个样本使自适应生效
        tracker.record(1L, 100L, 5_000L);
        tracker.record(1L, 100L, 5_000L);
        tracker.record(1L, 100L, 5_000L);
        tracker.record(1L, 100L, 5_000L); // ema=5000, timeout=10000

        // 超时发生：记录 actual timeout 值
        tracker.recordTimeout(1L, 100L, 10_000L);
        // 新的 ema = 0.3*10000 + 0.7*5000 = 6500
        // timeout = max(min(6500*2, 40000), 5000) = 13000
        long newTimeout = tracker.getTimeout(1L, 100L);
        assertThat(newTimeout).isGreaterThan(10_000L); // 窗口扩大
        assertThat(newTimeout).isEqualTo(13_000L);
    }

    @Test
    void getLatency_returnsDefaultWhenNoData() {
        assertThat(tracker.getLatency(99L, 999L)).isEqualTo(40_000L);
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
        // 每个 pair 累积 4 个样本使自适应生效
        for (int i = 0; i < 4; i++) {
            tracker.record(1L, 100L, 5_000L);
            tracker.record(2L, 200L, 20_000L);
        }

        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(10_000L);
        assertThat(tracker.getTimeout(2L, 200L)).isEqualTo(40_000L); // 20*2=40, capped
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
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(40_000L);
    }

    @Test
    void key_withLargeIds_worksCorrectly() {
        // 验证大 ID 不会因 32-bit 溢出导致碰撞，每个 pair 累积 4 个样本
        for (int i = 0; i < 4; i++) {
            tracker.record(Integer.MAX_VALUE + 1L, 999L, 5_000L);
            tracker.record(1L, Integer.MAX_VALUE + 1L, 10_000L);
        }

        assertThat(tracker.getTimeout(Integer.MAX_VALUE + 1L, 999L)).isEqualTo(10_000L);
        assertThat(tracker.getTimeout(1L, Integer.MAX_VALUE + 1L)).isEqualTo(20_000L);
        assertThat(tracker.size()).isEqualTo(2);
    }
}
