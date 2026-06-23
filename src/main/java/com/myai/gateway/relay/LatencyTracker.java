package com.myai.gateway.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 自适应超时追踪器
 *
 * <p>维护每个 {@code (channelId, channelModelId)} 组合的平均响应时间（EMA），
 * 用于计算自适应超时时间。</p>
 *
 * <p>超时计算：</p>
 * <ul>
 *   <li>样本数 ≤ {@link #SAMPLE_THRESHOLD} 时：返回默认 {@value #DEFAULT_TIMEOUT_MS}ms 超时</li>
 *   <li>样本数 > {@link #SAMPLE_THRESHOLD} 时：{@code timeout = clamp(ema × 3, MIN_TIMEOUT, MAX_TIMEOUT)}</li>
 *   <li>最终超时限制在 {@link #MIN_TIMEOUT_MS} ~ {@link #MAX_TIMEOUT_MS} 之间</li>
 * </ul>
 *
 * <p>更新规则：</p>
 * <ul>
 *   <li>首次记录：直接使用测量值（但不低于 {@link #MIN_LATENCY_MS}）</li>
 *   <li>后续更新：{@code ema = α × measurement + (1 - α) × ema}，α = {@value #ALPHA}</li>
 *   <li>超时时：将实际等待的超时时间作为测量值记录，使平均值逐渐上移以扩大窗口</li>
 *   <li>无历史时返回默认值 {@value #DEFAULT_TIMEOUT_MS}ms</li>
 * </ul>
 */
@Component
public class LatencyTracker {

    private static final Logger log = LoggerFactory.getLogger(LatencyTracker.class);

    /** EMA 平滑因子 */
    static final double ALPHA = 0.3;

    /** 默认超时时间（样本数不足时使用，60 秒） */
    static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /** 最小延迟（2.5 秒），避免 ema 过低 */
    static final long MIN_LATENCY_MS = 2_500L;

    /** 最小超时时间（20 秒） */
    static final long MIN_TIMEOUT_MS = 20_000L;

    /** 最大超时时间（60 秒） */
    static final long MAX_TIMEOUT_MS = 60_000L;

    /** 样本数阈值：样本数超过此值才启用自适应超时，否则返回默认超时 */
    static final int SAMPLE_THRESHOLD = 5;

    private final ConcurrentHashMap<Key, Entry> map = new ConcurrentHashMap<>();

    /**
     * (channelId, channelModelId) 复合键，避免 32-bit 溢出
     */
    record Key(long channelId, long channelModelId) {}

    /**
     * 记录一次延迟测量值
     *
     * @param channelId      渠道 ID（不可为 null）
     * @param channelModelId 渠道模型 ID（不可为 null）
     * @param latencyMs      本次测量的延迟（毫秒），低于 {@link #MIN_LATENCY_MS} 会被钳位
     */
    public void record(Long channelId, Long channelModelId, long latencyMs) {
        if (channelId == null || channelModelId == null) {
            return;
        }
        Key k = new Key(channelId, channelModelId);
        long clampedMs = Math.max(latencyMs, MIN_LATENCY_MS);
        map.compute(k, (key, entry) -> {
            if (entry == null) {
                return new Entry(clampedMs, 1);
            }
            // EMA: ema = α × measurement + (1 - α) × ema
            double newEma = ALPHA * clampedMs + (1.0 - ALPHA) * entry.ema;
            return new Entry(newEma, entry.sampleCount + 1);
        });
    }

    /**
     * 获取指定 (channel, channelModel) 的当前 EMA 延迟
     *
     * @return EMA 延迟（毫秒），无历史时返回 {@link #DEFAULT_TIMEOUT_MS}
     */
    public long getLatency(Long channelId, Long channelModelId) {
        if (channelId == null || channelModelId == null) {
            return DEFAULT_TIMEOUT_MS;
        }
        Entry entry = map.get(new Key(channelId, channelModelId));
        return entry != null ? (long) entry.ema : DEFAULT_TIMEOUT_MS;
    }

    /**
     * 获取指定 (channel, channelModel) 的延迟统计信息
     *
     * @return [latencyMs, sampleCount]，无历史时 latencyMs 为 {@link #DEFAULT_TIMEOUT_MS}，sampleCount 为 0
     */
    public long[] getStats(Long channelId, Long channelModelId) {
        if (channelId == null || channelModelId == null) {
            return new long[]{DEFAULT_TIMEOUT_MS, 0};
        }
        Entry entry = map.get(new Key(channelId, channelModelId));
        if (entry != null) {
            return new long[]{(long) entry.ema, entry.sampleCount};
        }
        return new long[]{DEFAULT_TIMEOUT_MS, 0};
    }

    /**
     * 获取自适应超时时间
     *
     * <p>样本数不超过 {@link #SAMPLE_THRESHOLD} 时返回默认超时 {@value #DEFAULT_TIMEOUT_MS}ms（60 秒），
     * 避免数据稀疏时产生激进的超时窗口。</p>
     *
     * <p>样本数超过阈值后：{@code timeout = clamp(ema × 3, MIN_TIMEOUT, MAX_TIMEOUT)}，
     * 即基于 EMA 平均延迟的 3 倍计算，最终限制在 20 秒 ~ 60 秒之间。</p>
     *
     * @return 超时时间（毫秒），介于 {@link #MIN_TIMEOUT_MS} ~ {@link #MAX_TIMEOUT_MS} 之间
     */
    public long getTimeout(Long channelId, Long channelModelId) {
        long[] stats = getStats(channelId, channelModelId);
        long latency = stats[0];
        int sampleCount = (int) stats[1];
        // 样本数不足时返回默认超时（60s），避免数据稀疏导致激进的超时窗口
        if (sampleCount <= SAMPLE_THRESHOLD) {
            return DEFAULT_TIMEOUT_MS;
        }
        long timeout = Math.min(latency * 3, MAX_TIMEOUT_MS);
        return Math.max(timeout, MIN_TIMEOUT_MS);
    }

    /**
     * 记录一次超时事件
     *
     * <p>将实际等待的超时时间作为测量值记录到 EMA 中，使平均值逐渐上移，
     * 从而在下一次请求时为该模型提供更大的超时窗口。</p>
     *
     * @param channelId      渠道 ID
     * @param channelModelId 渠道模型 ID
     * @param timeoutMs      实际等待的超时时间（毫秒）
     */
    public void recordTimeout(Long channelId, Long channelModelId, long timeoutMs) {
        if (channelId == null || channelModelId == null) {
            return;
        }
        log.info("延迟超时记录: channelId={} channelModelId={} timeoutMs={}",
                channelId, channelModelId, timeoutMs);
        record(channelId, channelModelId, timeoutMs);
    }

    // ---- test support ----

    /** 清除所有记录（仅用于测试） */
    void reset() {
        map.clear();
    }

    /** 获取记录总数（仅用于测试/监控） */
    int size() {
        return map.size();
    }

    /**
     * EMA 条目
     */
    static class Entry {
        final double ema;
        final int sampleCount;

        Entry(double ema, int sampleCount) {
            this.ema = ema;
            this.sampleCount = sampleCount;
        }
    }
}
