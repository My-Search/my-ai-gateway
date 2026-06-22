package com.myai.gateway.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 固定超时追踪器
 *
 * <p>始终返回默认 60 秒超时时间，不支持自适应调整。</p>
 */
@Component
public class LatencyTracker {

    private static final Logger log = LoggerFactory.getLogger(LatencyTracker.class);

    /** 默认超时时间（60 秒） */
    static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /**
     * 记录一次延迟测量值（固定超时模式下为 no-op）
     */
    public void record(Long channelId, Long channelModelId, long latencyMs) {
        // fixed timeout mode: no-op
    }

    /**
     * 获取指定 (channel, channelModel) 的当前 EMA 延迟
     *
     * @return 始终返回默认 60 秒
     */
    public long getLatency(Long channelId, Long channelModelId) {
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * 获取指定 (channel, channelModel) 的延迟统计信息
     *
     * @return [60s, 0]
     */
    public long[] getStats(Long channelId, Long channelModelId) {
        return new long[]{DEFAULT_TIMEOUT_MS, 0};
    }

    /**
     * 获取超时时间
     *
     * @return 始终返回默认 60 秒
     */
    public long getTimeout(Long channelId, Long channelModelId) {
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * 记录一次超时事件（固定超时模式下为 no-op）
     */
    public void recordTimeout(Long channelId, Long channelModelId, long timeoutMs) {
        // fixed timeout mode: no-op
    }

    // ---- test support ----

    /** 清除所有记录（固定超时模式下为 no-op） */
    void reset() {
        // fixed timeout mode: no-op
    }

    /** 获取记录总数（固定超时模式下始终返回 0） */
    int size() {
        return 0;
    }
}
