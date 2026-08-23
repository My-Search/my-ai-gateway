package com.myai.gateway.relay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LatencyTracker 单元测试
 * 验证最近 30 次滑动窗口平均、自适应超时（avg×3, 20s~60s）、最小样本数阈值（3，不足用 max）
 */
class LatencyTrackerTest {

    private LatencyTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new LatencyTracker(null); // null AdminConfigService → 使用硬编码默认值
    }

    @Test
    void getTimeout_returnsMaxWhenNoData() {
        long timeout = tracker.getTimeout(1L, 100L);
        // 无样本时返回最大超时 60s
        assertThat(timeout).isEqualTo(60_000L);
    }

    @Test
    void getTimeout_returnsMaxWhenSampleCountBelowThreshold() {
        // 1 ~ 2 个样本时，样本数 < 最小样本数（3），应返回最大超时 60s
        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);

        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(60_000L);
    }

    @Test
    void getTimeout_usesAdaptiveValueAtMinSampleCount() {
        // 3 个样本即启用自适应超时
        for (int i = 0; i < 3; i++) {
            tracker.record(1L, 100L, 5_000L); // avg = 5000
        }

        long timeout = tracker.getTimeout(1L, 100L);
        // clamp(5000*3=15000, 20000, 60000) = 20000（受最小超时 20s 限制）
        assertThat(timeout).isEqualTo(20_000L);
    }

    @Test
    void getTimeout_adaptiveFormula_avgTimes3() {
        // 3 个样本，avg = 10000
        for (int i = 0; i < 3; i++) {
            tracker.record(1L, 100L, 10_000L);
        }

        long timeout = tracker.getTimeout(1L, 100L);
        // clamp(10000*3=30000, 20000, 60000) = 30000
        assertThat(timeout).isEqualTo(30_000L);
    }

    @Test
    void getTimeout_capsAtMax() {
        // 高延迟样本，avg = 30000
        for (int i = 0; i < 3; i++) {
            tracker.record(1L, 100L, 30_000L);
        }

        long timeout = tracker.getTimeout(1L, 100L);
        // clamp(30000*3=90000, 20000, 60000) = 60000（受最大超时 60s 限制）
        assertThat(timeout).isEqualTo(60_000L);
    }

    @Test
    void getTimeout_hasMinFloor() {
        // 极低值样本
        for (int i = 0; i < 3; i++) {
            tracker.record(1L, 100L, 500L);
        }

        long timeout = tracker.getTimeout(1L, 100L);
        // avg = 500 → 500*3=1500 → clamp(1500, 20000, 60000) = 20000
        assertThat(timeout).isEqualTo(20_000L);
    }

    @Test
    void window_simpleAverage_overSamples() {
        // 简单平均：非 EMA 加权
        tracker.record(1L, 100L, 10_000L);
        tracker.record(1L, 100L, 20_000L);
        tracker.record(1L, 100L, 30_000L);

        long timeout = tracker.getTimeout(1L, 100L);
        // avg = 20000 → clamp(60000, 20000, 60000) = 60000
        assertThat(timeout).isEqualTo(60_000L);

        // 追加低值后平均值被拉低（滑动平均对最新数据同等对待）
        tracker.record(1L, 100L, 0L);
        // avg = (10000+20000+30000+0)/4 = 15000 → clamp(45000, 20000, 60000) = 45000
        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(45_000L);
    }

    @Test
    void window_evictsOldestBeyondSize() {
        // 灌满窗口：30 条 60_000
        for (int i = 0; i < 30; i++) {
            tracker.record(1L, 100L, 60_000L);
        }
        assertThat(tracker.getStats(1L, 100L)[1]).isEqualTo(30);
        assertThat(tracker.getLatency(1L, 100L)).isEqualTo(60_000L);

        // 再记录 30 条 0ms，旧样本全部被淘汰，均值变为 0
        for (int i = 0; i < 30; i++) {
            tracker.record(1L, 100L, 0L);
        }
        long[] stats = tracker.getStats(1L, 100L);
        assertThat(stats[1]).isEqualTo(30);
        assertThat(stats[0]).isEqualTo(0L);
    }

    @Test
    void recordTimeout_increasesTimeoutForNextCall() {
        // 累积 3 个样本使自适应生效
        for (int i = 0; i < 3; i++) {
            tracker.record(1L, 100L, 5_000L); // avg=5000, timeout=clamp(15000)=20000(min)
        }

        // 超时发生：记录实际 timeout 值
        tracker.recordTimeout(1L, 100L, 20_000L);
        // 新的 avg = (5000*3+20000)/4 = 8750
        // timeout = clamp(8750*3=26250, 20000, 60000) = 26250
        long newTimeout = tracker.getTimeout(1L, 100L);
        assertThat(newTimeout).isGreaterThan(20_000L); // 窗口扩大
        assertThat(newTimeout).isEqualTo(26_250L);
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
        // 每个 pair 累积 3 个样本使自适应生效
        for (int i = 0; i < 3; i++) {
            tracker.record(1L, 100L, 5_000L);
            tracker.record(2L, 200L, 20_000L);
        }

        assertThat(tracker.getTimeout(1L, 100L)).isEqualTo(20_000L); // 5*3=15 → 20(min)
        assertThat(tracker.getTimeout(2L, 200L)).isEqualTo(60_000L); // 20*3=60 → 60(max)
        assertThat(tracker.size()).isEqualTo(2);
    }

    @Test
    void reset_clearsAllData() {
        tracker.record(1L, 100L, 5_000L);
        assertThat(tracker.size()).isEqualTo(1);
        tracker.reset();
        assertThat(tracker.size()).isEqualTo(0);
        // 清空后回到"样本不足"状态，返回最大超时
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
    void key_withLargeIds_worksCorrectly() {
        // 验证大 ID 不会因 32-bit 溢出导致碰撞，每个 pair 累积 3 个样本
        for (int i = 0; i < 3; i++) {
            tracker.record(Integer.MAX_VALUE + 1L, 999L, 5_000L);
            tracker.record(1L, Integer.MAX_VALUE + 1L, 10_000L);
        }

        assertThat(tracker.getTimeout(Integer.MAX_VALUE + 1L, 999L)).isEqualTo(20_000L); // 5*3=15 → 20(min)
        assertThat(tracker.getTimeout(1L, Integer.MAX_VALUE + 1L)).isEqualTo(30_000L);   // 10*3=30
        assertThat(tracker.size()).isEqualTo(2);
    }
}
