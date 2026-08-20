package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.springframework.stereotype.Component;

/**
 * 熔断状态清理 - 负责删除渠道相关的熔断状态
 */
@Component
public class CircuitBreakerCleanup {

    private final CircuitBreakerStateMapper stateMapper;

    public CircuitBreakerCleanup(CircuitBreakerStateMapper stateMapper) {
        this.stateMapper = stateMapper;
    }

    /**
     * 删除指定渠道的所有熔断状态
     */
    public int deleteByChannelId(Long channelId) {
        return stateMapper.delete(
                new LambdaQueryWrapper<CircuitBreakerState>()
                        .eq(CircuitBreakerState::getChannelId, channelId));
    }
}
