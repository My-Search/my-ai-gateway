package com.myai.gateway.service;

import com.myai.gateway.entity.CircuitBreakerConfig;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentMatcher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CircuitBreakerService 单元测试
 * 验证两级熔断语义：渠道级（按 API Key）和模型级
 */
class CircuitBreakerServiceTest {

    private CircuitBreakerStateMapper stateMapper;
    private ModelService modelService;
    private ChannelApiKeyService channelApiKeyService;
    private CircuitBreakerService service;

    @BeforeEach
    void setUp() {
        stateMapper = mock(CircuitBreakerStateMapper.class);
        modelService = mock(ModelService.class);
        channelApiKeyService = mock(ChannelApiKeyService.class);
        service = new CircuitBreakerService(stateMapper, modelService, channelApiKeyService);
    }

    @Test
    void isChannelCircuitBroken_whenOpenRecordExists_returnsTrue() {
        when(stateMapper.selectCount(any())).thenReturn(1L);

        boolean result = service.isChannelCircuitBroken(1L);

        assertThat(result).isTrue();
        verify(stateMapper).selectCount(any());
    }

    @Test
    void isChannelCircuitBroken_whenNoOpenRecord_returnsFalse() {
        when(stateMapper.selectCount(any())).thenReturn(0L);

        boolean result = service.isChannelCircuitBroken(1L);

        assertThat(result).isFalse();
    }

    @Test
    void isChannelCircuitBroken_withApiKeyId_returnsTrueWhenOpen() {
        when(stateMapper.selectCount(any())).thenReturn(1L);

        boolean result = service.isChannelCircuitBroken(1L, 2L);

        assertThat(result).isTrue();
    }

    @Test
    void isModelCircuitBroken_whenOpenRecordExists_returnsTrue() {
        when(stateMapper.selectCount(any())).thenReturn(1L);

        boolean result = service.isModelCircuitBroken(10L, 2L);

        assertThat(result).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "true, false, false, false",
            "false, true, false, false",
            "false, false, true, false",
            "false, false, false, true"
    })
    void isAvailable_reflectsChannelAndModelBreakerState(boolean channelBroken,
                                                          boolean keyBroken,
                                                          boolean modelBroken,
                                                          boolean expectedAvailable) {
        when(stateMapper.selectCount(any())).thenReturn(channelBroken ? 1L : 0L,
                keyBroken ? 1L : 0L,
                modelBroken ? 1L : 0L);

        boolean result = service.isAvailable(10L, 1L, 2L);

        assertThat(result).isEqualTo(expectedAvailable);
    }

    @Test
    void triggerCircuitBreak_channelScope_opensChannelLevelBreaker() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setEnabled(1);
        config.setCircuitBreakScope("channel");
        config.setCircuitBreakDuration(60);
        when(modelService.getCircuitBreakerConfig(100L)).thenReturn(config);

        service.triggerCircuitBreak(100L, 1L, 2L, 10L);

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

        service.triggerCircuitBreak(100L, 1L, 2L, 10L);

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
    void isModelCircuitBroken_withMatchingApiKeyId_returnsTrue() {
        when(stateMapper.selectCount(any())).thenReturn(1L);

        boolean result = service.isModelCircuitBroken(10L, 2L);

        assertThat(result).isTrue();
    }

    @Test
    void isAvailable_whenModelLevelBreakerOpenForKey_returnsFalse() {
        when(stateMapper.selectCount(any())).thenReturn(0L, 0L, 1L);

        boolean result = service.isAvailable(10L, 1L, 2L);

        assertThat(result).isFalse();
    }

    @Test
    void triggerCircuitBreak_disabledConfig_doesNothing() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setEnabled(0);
        config.setCircuitBreakDuration(60);
        config.setCircuitBreakScope("model");
        when(modelService.getCircuitBreakerConfig(100L)).thenReturn(config);

        service.triggerCircuitBreak(100L, 1L, 2L, 10L);

        verify(stateMapper, never()).delete(any());
        verify(stateMapper, never()).insert(any(CircuitBreakerState.class));
    }

    @Test
    void triggerCircuitBreak_missingConfig_doesNothing() {
        when(modelService.getCircuitBreakerConfig(100L)).thenReturn(null);

        service.triggerCircuitBreak(100L, 1L, 2L, 10L);

        verify(stateMapper, never()).delete(any());
        verify(stateMapper, never()).insert(any(CircuitBreakerState.class));
    }

    @Test
    void getCircuitBreakScopeDesc_disabledConfig_returnsDisabled() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setEnabled(0);

        String desc = service.getCircuitBreakScopeDesc(config);

        assertThat(desc).isEqualTo("熔断已禁用");
    }

    @Test
    void getCircuitBreakScopeDesc_channelScope_returnsChannelDescription() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setEnabled(1);
        config.setCircuitBreakScope("channel");

        String desc = service.getCircuitBreakScopeDesc(config);

        assertThat(desc).isEqualTo("渠道级（按 API Key 熔断）");
    }

    @Test
    void getCircuitBreakScopeDesc_apikeyScopeNormalizedToChannel() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setEnabled(1);
        config.setCircuitBreakScope("apikey");

        String desc = service.getCircuitBreakScopeDesc(config);

        assertThat(desc).isEqualTo("渠道级（按 API Key 熔断）");
    }
}
