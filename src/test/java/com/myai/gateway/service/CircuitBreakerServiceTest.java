package com.myai.gateway.service;

import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.CircuitBreakerConfig;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.entity.ModelChannelRel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CircuitBreakerService 单元测试
 * 验证两级熔断语义：渠道级（按 API Key）和模型级
 */
class CircuitBreakerServiceTest {

    private CircuitCheck circuitCheck;
    private CircuitTrigger circuitTrigger;
    private CircuitGate circuitGate;
    private CircuitMark circuitMark;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private CircuitBreakerService service;

    @BeforeEach
    void setUp() {
        circuitCheck = mock(CircuitCheck.class);
        circuitTrigger = mock(CircuitTrigger.class);
        circuitGate = mock(CircuitGate.class);
        circuitMark = mock(CircuitMark.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new CircuitBreakerService(circuitCheck, circuitTrigger, circuitGate, circuitMark, eventPublisher);
    }

    // ==================== 熔断检查接口 ====================

    @Test
    void isChannelCircuitBroken_whenOpenRecordExists_returnsTrue() {
        when(circuitCheck.isChannelCircuitBroken(1L)).thenReturn(true);

        boolean result = service.isChannelCircuitBroken(1L);

        assertThat(result).isTrue();
        verify(circuitCheck).isChannelCircuitBroken(1L);
    }

    @Test
    void isChannelCircuitBroken_whenNoOpenRecord_returnsFalse() {
        when(circuitCheck.isChannelCircuitBroken(1L)).thenReturn(false);

        boolean result = service.isChannelCircuitBroken(1L);

        assertThat(result).isFalse();
    }

    @Test
    void isChannelCircuitBroken_withApiKeyId_returnsTrueWhenOpen() {
        when(circuitCheck.isChannelCircuitBroken(1L, 2L)).thenReturn(true);

        boolean result = service.isChannelCircuitBroken(1L, 2L);

        assertThat(result).isTrue();
    }

    @Test
    void isModelCircuitBroken_whenOpenRecordExists_returnsTrue() {
        when(circuitCheck.isModelCircuitBroken(10L, 2L)).thenReturn(true);

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
        when(circuitCheck.isChannelCircuitBroken(1L)).thenReturn(channelBroken);
        when(circuitCheck.isChannelCircuitBroken(1L, 2L)).thenReturn(keyBroken);
        when(circuitCheck.isModelCircuitBroken(10L, 2L)).thenReturn(modelBroken);

        boolean result = service.isAvailable(10L, 1L, 2L);

        assertThat(result).isEqualTo(expectedAvailable);
    }

    @Test
    void isAvailable_withNullApiKey_callsChannelCheckOnly() {
        when(circuitCheck.isChannelCircuitBroken(1L)).thenReturn(false);
        // 当 apiKeyId 为 null 时，isModelCircuitBroken 也会被调用（传入 null）
        when(circuitCheck.isModelCircuitBroken(10L, null)).thenReturn(false);

        boolean result = service.isAvailable(10L, 1L);

        assertThat(result).isTrue();
        verify(circuitCheck, times(1)).isChannelCircuitBroken(1L);
        verify(circuitCheck, never()).isChannelCircuitBroken(any(), any());
        verify(circuitCheck).isModelCircuitBroken(10L, null);
    }

    // ==================== 熔断触发接口 ====================

    @Test
    void triggerCircuitBreak_delegatesToTriggerAndPublishesEvent() {
        service.triggerCircuitBreak(100L, 1L, 2L, 10L);

        verify(circuitTrigger).triggerCircuitBreak(100L, 1L, 2L, 10L);
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void triggerCircuitBreak_eventFailureDoesNotAffectMainLogic() {
        doThrow(new RuntimeException("event publish failed")).when(eventPublisher)
                .publishEvent(any());

        // 不应抛出异常
        service.triggerCircuitBreak(100L, 1L, 2L, 10L);

        verify(circuitTrigger).triggerCircuitBreak(100L, 1L, 2L, 10L);
    }

    // ==================== 门状态管理接口 ====================

    @Test
    void listExpiredStates_delegatesToGate() {
        when(circuitGate.listExpiredStates()).thenReturn(List.of());

        assertThat(service.listExpiredStates()).isEmpty();
        verify(circuitGate).listExpiredStates();
    }

    @Test
    void listExpiredStatesByChannel_queriesOnlyThatChannel() {
        when(circuitGate.listExpiredStatesByChannel(1L)).thenReturn(List.of());

        assertThat(service.listExpiredStatesByChannel(1L)).isEmpty();
        verify(circuitGate).listExpiredStatesByChannel(1L);
    }

    @Test
    void listExpiredStatesByChannel_nullChannel_returnsEmpty() {
        when(circuitGate.listExpiredStatesByChannel(null)).thenReturn(List.of());

        assertThat(service.listExpiredStatesByChannel(null)).isEmpty();
    }

    @Test
    void recoverModelState_delegatesToGateAndPublishesEvent() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(1L);
        state.setChannelId(10L);
        state.setChannelApiKeyId(20L);
        state.setChannelModelId(30L);

        service.recoverModelState(state);

        verify(circuitGate).recoverModelState(state);
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void recoverModelState_withNullState_skipsEvent() {
        service.recoverModelState(null);

        verify(circuitGate).recoverModelState(null);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void renewState_delegatesToGate() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(1L);

        service.renewState(state, 60);

        verify(circuitGate).renewState(state, 60);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void removeState_delegatesToGateAndPublishesEvent() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(9L);

        service.removeState(state);

        verify(circuitGate).removeState(state);
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void cleanExpiredStates_delegatesToGate() {
        service.cleanExpiredStates();

        verify(circuitGate).cleanExpiredStates();
    }

    // ==================== 熔断标记接口 ====================

    @Test
    void isFullyBroken_delegatesToMark() {
        when(circuitMark.isFullyBroken(10L, 1L, 20L)).thenReturn(true);

        assertThat(service.isFullyBroken(10L, 1L, 20L)).isTrue();
        verify(circuitMark).isFullyBroken(10L, 1L, 20L);
    }

    @Test
    void computeRelBrokenMarks_delegatesToMark() {
        ModelChannelRel rel = new ModelChannelRel();
        rel.setId(1L);
        when(circuitMark.computeRelBrokenMarks(any())).thenReturn(Map.of());

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = service.computeRelBrokenMarks(List.of(rel));

        assertThat(marks).isEmpty();
        verify(circuitMark).computeRelBrokenMarks(any());
    }

    @Test
    void manualRecover_delegatesToMarkAndPublishesEvent() {
        when(circuitMark.manualRecover(10L, 1L, 20L)).thenReturn(1);

        int count = service.manualRecover(10L, 1L, 20L);

        assertThat(count).isEqualTo(1);
        verify(circuitMark).manualRecover(10L, 1L, 20L);
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void manualRecover_noRecordsDeleted_doesNotPublishEvent() {
        when(circuitMark.manualRecover(10L, 1L, 20L)).thenReturn(0);

        int count = service.manualRecover(10L, 1L, 20L);

        assertThat(count).isZero();
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ==================== 工具方法 ====================

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

    @Test
    void getCircuitBreakScopeDesc_nullConfig_returnsDisabled() {
        assertThat(service.getCircuitBreakScopeDesc(null)).isEqualTo("熔断已禁用");
    }

    // ==================== 门面委托验证 ====================

    @Test
    void allMethodsDelegateCorrectly() {
        // 验证所有公共方法都委托给对应的协作类
        when(circuitCheck.isChannelCircuitBroken(1L)).thenReturn(false);
        when(circuitCheck.isChannelCircuitBroken(1L, 2L)).thenReturn(false);
        when(circuitCheck.isModelCircuitBroken(10L, 2L)).thenReturn(false);
        when(circuitMark.isFullyBroken(10L, 1L, 20L)).thenReturn(false);
        when(circuitMark.computeRelBrokenMarks(any())).thenReturn(Map.of());
        when(circuitMark.manualRecover(10L, 1L, 20L)).thenReturn(0);

        // 调用所有公共方法，验证委托正确
        service.isChannelCircuitBroken(1L);
        service.isChannelCircuitBroken(1L, 2L);
        service.isModelCircuitBroken(10L, 2L);
        service.isAvailable(10L, 1L, 2L);
        service.isAvailable(10L, 1L);
        service.triggerCircuitBreak(100L, 1L, 2L, 10L);
        service.listExpiredStates();
        service.listExpiredStatesByChannel(1L);
        service.recoverModelState(new CircuitBreakerState());
        service.renewState(new CircuitBreakerState(), 60);
        service.removeState(new CircuitBreakerState());
        service.cleanExpiredStates();
        service.isFullyBroken(10L, 1L, 20L);
        service.computeRelBrokenMarks(List.of());
        service.manualRecover(10L, 1L, 20L);
        // getCircuitBreakScopeDesc 需要配置 enable 不为 null
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setEnabled(0);
        service.getCircuitBreakScopeDesc(config);

        // 验证所有协作类都被调用（isAvailable 会额外调用 isChannelCircuitBroken 和 isModelCircuitBroken）
        verify(circuitCheck, atLeast(1)).isChannelCircuitBroken(1L);
        verify(circuitCheck, atLeast(1)).isChannelCircuitBroken(1L, 2L);
        verify(circuitCheck, atLeast(1)).isModelCircuitBroken(anyLong(), anyLong());
        verify(circuitTrigger).triggerCircuitBreak(100L, 1L, 2L, 10L);
        verify(circuitGate).listExpiredStates();
        verify(circuitGate).listExpiredStatesByChannel(1L);
        verify(circuitGate).recoverModelState(any());
        verify(circuitGate).renewState(any(), eq(60));
        verify(circuitGate).removeState(any());
        verify(circuitGate).cleanExpiredStates();
        verify(circuitMark).isFullyBroken(10L, 1L, 20L);
        verify(circuitMark).computeRelBrokenMarks(any());
        verify(circuitMark).manualRecover(10L, 1L, 20L);
    }
}
