package com.myai.gateway.relay;

import com.myai.gateway.service.AdminConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自适应超时追踪器
 *
 * <p>维护每个 {@code (channelId, channelModelId)} 组合的<strong>最近 {@value #WINDOW_SIZE} 次
 * 首字节响应时间的简单平均</strong>（滑动窗口），用于计算自适应超时时间。测量输入为请求收到
 * 首个响应字节的耗时（首字节语义：流式为首个 SSE 事件到达耗时；非流式响应一次到位，
 * 首字节耗时即完整响应耗时）。</p>
 *
 * <p>超时计算：</p>
 * <ul>
 *   <li>窗口内样本数 &lt; {@link #MIN_SAMPLE_COUNT} 时：直接返回最大超时
 *       （系统配置项 {@code timeout_max_seconds}，默认 60s）</li>
 *   <li>样本数 ≥ {@link #MIN_SAMPLE_COUNT} 时：
 *       {@code timeout = clamp(avg × 3, minTimeout, maxTimeout)}</li>
 *   <li>最终超时限制在配置的最小～最大超时之间（通过系统配置项
 *       {@code timeout_min_seconds} / {@code timeout_max_seconds} 设置，默认 20s ~ 60s）</li>
 * </ul>
 *
 * <p>更新规则：</p>
 * <ul>
 *   <li>每次记录追加到窗口尾部，窗口超过 {@value #WINDOW_SIZE} 条时淘汰最旧样本</li>
 *   <li>超时时：将实际等待的超时时间作为测量值记录，使平均值逐渐上移以扩大窗口</li>
 * </ul>
 */
@Component
public class LatencyTracker {

    private static final Logger log = LoggerFactory.getLogger(LatencyTracker.class);

    /** 滑动窗口大小：取最近 30 次首字节响应时间的简单平均 */
    static final int WINDOW_SIZE = 30;

    /** 最小样本数：窗口内样本数低于该值时直接使用最大超时 */
    static final int MIN_SAMPLE_COUNT = 3;

    /** 最小超时默认值（20 秒），当系统配置未设置时使用 */
    static final long DEFAULT_MIN_TIMEOUT_MS = 20_000L;

    /** 最大超时默认值（60 秒），当系统配置未设置时使用 */
    static final long DEFAULT_MAX_TIMEOUT_MS = 60_000L;

    private final ConcurrentHashMap<Key, Deque<Long>> map = new ConcurrentHashMap<>();

    private final AdminConfigService adminConfigService;

    public LatencyTracker(AdminConfigService adminConfigService) {
        this.adminConfigService = adminConfigService;
    }

    /**
     * (channelId, channelModelId) 复合键，避免 32-bit 溢出
     */
    record Key(long channelId, long channelModelId) {}

    /**
     * 记录一次延迟测量值（追加到滑动窗口，超出 {@link #WINDOW_SIZE} 时淘汰最旧样本）
     *
     * @param channelId      渠道 ID（不可为 null）
     * @param channelModelId 渠道模型 ID（不可为 null）
     * @param latencyMs      本次测量的延迟（毫秒）
     */
    public void record(Long channelId, Long channelModelId, long latencyMs) {
        if (channelId == null || channelModelId == null) {
            return;
        }
        Key k = new Key(channelId, channelModelId);
        Deque<Long> window = map.computeIfAbsent(k, key -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(latencyMs);
            while (window.size() > WINDOW_SIZE) {
                window.removeFirst();
            }
        }
    }

    /**
     * 获取指定 (channel, channelModel) 的最近 {@value #WINDOW_SIZE} 次首字节平均延迟
     *
     * @return 平均延迟（毫秒），无样本时返回 0
     */
    public long getLatency(Long channelId, Long channelModelId) {
        return getStats(channelId, channelModelId)[0];
    }

    /**
     * 获取指定 (channel, channelModel) 的延迟统计信息
     *
     * @return [avgLatencyMs, sampleCount]，无样本时 avgLatencyMs 为 0、sampleCount 为 0
     */
    public long[] getStats(Long channelId, Long channelModelId) {
        if (channelId == null || channelModelId == null) {
            return new long[]{0, 0};
        }
        Deque<Long> window = map.get(new Key(channelId, channelModelId));
        if (window == null) {
            return new long[]{0, 0};
        }
        synchronized (window) {
            if (window.isEmpty()) {
                return new long[]{0, 0};
            }
            double sum = 0;
            for (Long v : window) {
                sum += v;
            }
            return new long[]{Math.round(sum / window.size()), window.size()};
        }
    }

    /**
     * 获取自适应超时时间
     *
     * <p>窗口内样本数不足 {@link #MIN_SAMPLE_COUNT} 时直接返回最大超时
     * （{@code timeout_max_seconds}，默认 60 秒），避免数据稀疏时产生过小的超时窗口。</p>
     *
     * <p>样本数足够后：{@code timeout = clamp(avg × 3, minTimeout, maxTimeout)}，
     * 即基于最近 30 次首字节平均延迟的 3 倍计算，最终限制在系统配置的范围内
     * （默认 20 秒 ~ 60 秒）。</p>
     *
     * @return 超时时间（毫秒），介于 {@link #getMinTimeoutMs()} ~ {@link #getMaxTimeoutMs()} 之间
     */
    public long getTimeout(Long channelId, Long channelModelId) {
        long maxTimeout = getMaxTimeoutMs();
        long[] stats = getStats(channelId, channelModelId);
        long avgLatency = stats[0];
        int sampleCount = (int) stats[1];
        // 样本数不足时返回最大超时，避免数据稀疏导致超时窗口过小
        if (sampleCount < MIN_SAMPLE_COUNT) {
            return maxTimeout;
        }
        long timeout = Math.min(avgLatency * 3, maxTimeout);
        return Math.max(timeout, getMinTimeoutMs());
    }

    /**
     * 获取配置的最小超时时间（毫秒）
     * <p>从系统配置 {@code timeout_min_seconds} 读取，未配置时返回默认值 {@value #DEFAULT_MIN_TIMEOUT_MS}ms。</p>
     */
    long getMinTimeoutMs() {
        if (adminConfigService == null) {
            return DEFAULT_MIN_TIMEOUT_MS;
        }
        String val = adminConfigService.getValueByKey(AdminConfigService.KEY_TIMEOUT_MIN_SECONDS);
        if (val == null) {
            return DEFAULT_MIN_TIMEOUT_MS;
        }
        try {
            return Long.parseLong(val) * 1000L;
        } catch (NumberFormatException e) {
            return DEFAULT_MIN_TIMEOUT_MS;
        }
    }

    /**
     * 获取配置的最大超时时间（毫秒）
     * <p>从系统配置 {@code timeout_max_seconds} 读取，未配置时返回默认值 {@value #DEFAULT_MAX_TIMEOUT_MS}ms。</p>
     */
    long getMaxTimeoutMs() {
        if (adminConfigService == null) {
            return DEFAULT_MAX_TIMEOUT_MS;
        }
        String val = adminConfigService.getValueByKey(AdminConfigService.KEY_TIMEOUT_MAX_SECONDS);
        if (val == null) {
            return DEFAULT_MAX_TIMEOUT_MS;
        }
        try {
            return Long.parseLong(val) * 1000L;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_TIMEOUT_MS;
        }
    }

    /**
     * 记录一次超时事件
     *
     * <p>将实际等待的超时时间作为测量值记录到滑动窗口中，使平均值逐渐上移，
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
}
