package com.myai.gateway.service;

import org.springframework.context.ApplicationEvent;

/**
 * 熔断状态变更事件（Spring ApplicationEvent）
 *
 * <p>当熔断状态发生变更（触发/解除/探测开门/手动恢复）时发布，
 * 由 {@code CandidateRouter} 监听并主动失效熔断短路缓存，
 * 避免状态变更后缓存中仍持有旧值导致最多 1 秒窗口内的不一致。</p>
 *
 * <p>事件不携带完整状态，仅携带标识信息用于精确失效对应缓存 key。</p>
 */
public class CircuitBreakerStateChangedEvent extends ApplicationEvent {

    private final Long channelId;
    private final Long channelApiKeyId;
    private final Long channelModelId;

    /**
     * @param source           事件发布者（通常为 CircuitBreakerService）
     * @param channelId        渠道 ID
     * @param channelApiKeyId  渠道 API Key ID（可为 null，表示全渠道级）
     * @param channelModelId   渠道模型 ID（可为 null，表示渠道级）
     */
    public CircuitBreakerStateChangedEvent(Object source,
                                            Long channelId,
                                            Long channelApiKeyId,
                                            Long channelModelId) {
        super(source);
        this.channelId = channelId;
        this.channelApiKeyId = channelApiKeyId;
        this.channelModelId = channelModelId;
    }

    public Long getChannelId() { return channelId; }
    public Long getChannelApiKeyId() { return channelApiKeyId; }
    public Long getChannelModelId() { return channelModelId; }

    /**
     * 构建熔断缓存 key（与 {@code CandidateRouter.circuitBreakScope} 中的 key 格式一致）。
     */
    public String buildCacheKey() {
        return channelId + ":" + channelModelId + ":" + channelApiKeyId;
    }
}
