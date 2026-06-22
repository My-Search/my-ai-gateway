package com.myai.gateway.relay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LatencyTracker 单元测试
 * 验证固定 60s 超时行为
 */
class LatencyTrackerTest {

    private LatencyTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new LatencyTracker();
    }

    @Test
    void getTimeout_alwaysReturnsDefault() {
        // 无论是否调用过 record，始终返回 60s
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);
        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);
        tracker.record(1L, 100L, 50_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);
    }

    @Test
    void getLatency_returnsDefaultWhenNoData() {
        assertThat(tracker.getLatency(99L, 999L)).isEqualTo(60_000L);
    }

    @Test
    void getLatency_alwaysReturnsDefault() {
        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getLatency(1L, 100L)).isEqualTo(60_000L);
    }

    @Test
    void getStats_returnsDefaults() {
        long[] stats = tracker.getStats(1L, 100L);
        assertThat(stats).containsExactly(60_000L, 0L);

        tracker.record(1L, 100L, 5_000L);
        stats = tracker.getStats(1L, 100L);
        assertThat(stats).containsExactly(60_000L, 0L);
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
    void reset_clearsAllData() {
        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.size()).isEqualTo(0);
        tracker.reset();
        assertThat(tracker.size()).isEqualTo(0);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);
    }

    @Test
    void differentChannelModelPairs_independentButSameDefault() {
        tracker.record(1L, 100L, 5_000L);
        tracker.record(2L, 200L, 20_000L);

        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);
        assertThat(tracker.getTimeout(2L, 200L)).isEqualTo(60_000L);
        assertThat(tracker.size()).isEqualTo(0);
    }

    @Test
    void recordTimeout_doesNotChangeTimeout() {
        tracker.record(1L, 100L, 5_000L);
        tracker.recordTimeout(1L, 100L, 20_000L);
        // 固定超时模式，recordTimeout 不影响结果
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);
    }
}
