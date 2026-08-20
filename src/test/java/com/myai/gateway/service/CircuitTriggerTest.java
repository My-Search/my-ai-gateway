package com.myai.gateway.service;

import com.myai.gateway.entity.CircuitBreakerConfig;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CircuitTrigger 单元测试
 * 验证熔断触发写入：渠道级（按 API Key）与模型级，及配置禁用/缺失时的空操作
 */
class CircuitTriggerTest {

    private CircuitBreakerStateMapper stateMapper;
    private ModelService modelService;
    private ChannelApiKeyService channelApiKeyService;
    private CircuitTrigger trigger;

    @BeforeEach
    void setUp() {
        stateMapper = mock(CircuitBreakerStateMapper.class);
        modelService = mock(ModelService.class);
        channelApiKeyService = mock(ChannelApiKeyService.class);
        trigger = new CircuitTrigger(stateMapper, modelService, channelApiKeyService);
    }

    @Test
    void triggerCircuitBreak_channelScope_opensChannelLevelBreaker() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setEnabled(1);
        config.setCircuitBreakScope("channel");
        config.setCircuitBreakDuration(60);
        when(modelService.getCircuitBreakerConfig(100L)).thenReturn(config);

        trigger.triggerCircuitBreak(100L, 1L, 2L, 10L);

        verify(stateMapper).delete(any());
        verify(stateMapper).insert(argThat((CircuitBreakerState state) ->
                state.getChannelId().equals(1L)
                        && state.getChannelApiKeyId().equals(2L)
                        && state.getChannelModelId() == null
                        && state.getIsOpen() == 1
                        && state.getExpireAt().isAfter(LocalDateTime.now())
        ));
    }

    @Test
    void triggerCircuitBreak_modelScope_opensModelLevelBreakerWithKey() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setEnabled(1);
        config.setCircuitBreakScope("model");
        config.setCircuitBreakDuration(30);
        when(modelService.getCircuitBreakerConfig(100L)).thenReturn(config);

        trigger.triggerCircuitBreak(100L, 1L, 2L, 10L);

        verify(stateMapper).delete(any());
        verify(stateMapper).insert(argThat((CircuitBreakerState state) ->
                state.getChannelId().equals(1L)
                        && state.getChannelApiKeyId().equals(2L)
                        && state.getChannelModelId().equals(10L)
                        && state.getIsOpen() == 1
                        && state.getExpireAt().isAfter(LocalDateTime.now())
        ));
    }

    @Test
    void triggerCircuitBreak_disabledConfig_doesNothing() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setEnabled(0);
        config.setCircuitBreakDuration(60);
        config.setCircuitBreakScope("model");
        when(modelService.getCircuitBreakerConfig(100L)).thenReturn(config);

        trigger.triggerCircuitBreak(100L, 1L, 2L, 10L);

        verify(stateMapper, never()).delete(any());
        verify(stateMapper, never()).insert(any(CircuitBreakerState.class));
    }

    @Test
    void triggerCircuitBreak_missingConfig_doesNothing() {
        when(modelService.getCircuitBreakerConfig(100L)).thenReturn(null);

        trigger.triggerCircuitBreak(100L, 1L, 2L, 10L);

        verify(stateMapper, never()).delete(any());
        verify(stateMapper, never()).insert(any(CircuitBreakerState.class));
    }
}