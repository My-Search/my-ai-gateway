package com.myai.gateway.relay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LatencyTracker 单元测试
 * 验证 EMA 计算、自适应超时、超时记录
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
        // 默认延迟=40s → timeout = min(40*2, 40) = 40s
        assertThat(timeout).isEqualTo(40_000L);
    }

    @Test
    void getTimeout_usesAdaptiveValueAfterRecord() {
        tracker.record(1L, 100L, 5_000L); // 5s
        long timeout = tracker.getTimeout(1L, 100L);
        // max(min(5s*2, 40s), 5s) = 10s
        assertThat(timeout).isEqualTo(10_000L);
    }

    @Test
    void getTimeout_capsAtMax() {
        tracker.record(1L, 100L, 30_000L); // 30s
        long timeout = tracker.getTimeout(1L, 100L);
        // 30*2=60 > 40, 但上限40s
        assertThat(timeout).isEqualTo(40_000L);
    }

    @Test
    void getTimeout_hasMinFloor() {
        // 首次记录极低值
        tracker.record(1L, 100L, 500L); // 500ms, 但会 clamp 到 MIN_LATENCY_MS=2500
        long timeout = tracker.getTimeout(1L, 100L);
        // min(max(2500*2, 5000), 40000) = 5000
        assertThat(timeout).isEqualTo(5_000L);
    }

    @Test
    void ema_smoothOverMultipleRecords() {
        tracker.record(1L, 100L, 10_000L); // ema=10000
        tracker.record(1L, 100L, 20_000L); // ema=0.3*20000 + 0.7*10000 = 13000
        tracker.record(1L, 100L, 5_000L);  // ema=0.3*5000 + 0.7*13000 = 10600

        long timeout = tracker.getTimeout(1L, 100L);
        long expectedTimeout = Math.max(Math.min((long)(10_600L * 2), 40_000L), 5_000L);
        assertThat(timeout).isEqualTo(expectedTimeout);
    }

    @Test
    void recordTimeout_increasesTimeoutForNextCall() {
        // 模拟：延迟=5s → timeout=10s
        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(10_000L);

        // 超时发生：记录 actual timeout 值
        tracker.recordTimeout(1L, 100L, 10_000L);
        // 新的 ema = 0.3*10000 + 0.7*5000 = 6500
        // timeout = max(min(6500*2, 40000), 5000) = 13000
        long newTimeout = tracker.getTimeout(1L, 100L);
        assertThat(newTimeout).isGreaterThan(10_000L); // 窗口扩大
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
        tracker.record(1L, 100L, 5_000L);
        tracker.record(2L, 200L, 20_000L);

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
        // 验证大 ID 不会因 32-bit 溢出导致碰撞
        tracker.record(Integer.MAX_VALUE + 1L, 999L, 5_000L);
        tracker.record(1L, Integer.MAX_VALUE + 1L, 10_000L);

        assertThat(tracker.getTimeout(Integer.MAX_VALUE + 1L, 999L)).isEqualTo(10_000L);
        assertThat(tracker.getTimeout(1L, Integer.MAX_VALUE + 1L)).isEqualTo(20_000L);
        assertThat(tracker.size()).isEqualTo(2);
    }
}
