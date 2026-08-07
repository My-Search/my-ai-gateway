package com.myai.gateway.service;

import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.relay.CircuitBreakerProbeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * CircuitBreakerRecoveryService 单元测试
 * <p>验证门恢复语义：模型级探测成功开门（联动渠道门）/ 失败续期；渠道级到期开门；
 * 触发式探测的异步投递与 6s（测试注入小值）节流；单常驻工作线程消费队列。</p>
 */
class CircuitBreakerRecoveryServiceTest {

    private CircuitBreakerService circuitBreakerService;
    private CircuitBreakerProbeService probeService;
    private ModelService modelService;
    private ChannelApiKeyService channelApiKeyService;
    private AdminConfigService adminConfigService;
    private CircuitBreakerRecoveryService service;

    private CircuitBreakerState modelState;
    private CircuitBreakerState channelState;
    private Channel channel;
    private ChannelModel channelModel;
    private ChannelApiKey apiKey;

    @BeforeEach
    void setUp() {
        circuitBreakerService = mock(CircuitBreakerService.class);
        probeService = mock(CircuitBreakerProbeService.class);
        modelService = mock(ModelService.class);
        channelApiKeyService = mock(ChannelApiKeyService.class);
        adminConfigService = mock(AdminConfigService.class);
        // 默认不节流，节流相关测试单独 stub
        when(adminConfigService.getCircuitBreakerProbeThrottleSeconds()).thenReturn(0d);
        service = new CircuitBreakerRecoveryService(
                circuitBreakerService, probeService, modelService, channelApiKeyService, adminConfigService);

        channel = new Channel();
        channel.setId(1L);
        channel.setName("test-channel");
        channel.setEnabled(1);

        channelModel = new ChannelModel();
        channelModel.setId(10L);
        channelModel.setChannelId(1L);
        channelModel.setModelName("gpt-test");
        channelModel.setEnabled(1);

        apiKey = new ChannelApiKey();
        apiKey.setId(20L);
        apiKey.setChannelId(1L);
        apiKey.setKeyName("key-1");
        apiKey.setEnabled(1);

        modelState = new CircuitBreakerState();
        modelState.setId(100L);
        modelState.setChannelId(1L);
        modelState.setChannelApiKeyId(20L);
        modelState.setChannelModelId(10L);
        modelState.setIsOpen(1);

        channelState = new CircuitBreakerState();
        channelState.setId(200L);
        channelState.setChannelId(1L);
        channelState.setChannelApiKeyId(20L);
        channelState.setIsOpen(1);
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    // ==================== 触发式探测（调用时异步） ====================

    @Test
    void triggerProbeByChannel_modelGateProbeSuccess_opensModelGate() {
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(modelState));
        when(modelService.getChannelModelById(10L)).thenReturn(channelModel);
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(true);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).recoverModelState(modelState);
        verify(circuitBreakerService, never()).renewState(any(), anyInt());
    }

    @Test
    void triggerProbeByChannel_modelGateProbeFailed_renewsWithConfiguredDuration() {
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(modelState));
        when(modelService.getChannelModelById(10L)).thenReturn(channelModel);
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(false);
        when(modelService.getCircuitBreakDurationByChannelModelId(10L)).thenReturn(120);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).renewState(modelState, 120);
        verify(circuitBreakerService, never()).recoverModelState(any());
    }

    @Test
    void triggerProbeByChannel_channelGate_probeSuccess_opensGate() {
        // 渠道级门到期不自动开门：探测成功才开门（删除记录）
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(channelState));
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(modelService.getFirstEnabledChannelModelByChannelId(1L)).thenReturn(channelModel);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(true);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).removeState(channelState);
        verify(circuitBreakerService, never()).renewState(any(), anyInt());
        verify(probeService).probe(channel, channelModel, apiKey);
    }

    @Test
    void triggerProbeByChannel_channelGate_probeFailed_renews() {
        // 探测失败：门保持关闭，按探测模型的熔断配置时长续期
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(channelState));
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(modelService.getFirstEnabledChannelModelByChannelId(1L)).thenReturn(channelModel);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(false);
        when(modelService.getCircuitBreakDurationByChannelModelId(10L)).thenReturn(120);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).renewState(channelState, 120);
        verify(circuitBreakerService, never()).removeState(any());
    }

    @Test
    void triggerProbeByChannel_channelGate_disabledChannel_cleansRecord() {
        // 渠道已禁用：记录无意义，直接清理（不探测）
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(channelState));
        channel.setEnabled(0);
        when(modelService.getChannelById(1L)).thenReturn(channel);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).removeState(channelState);
        verify(probeService, never()).probe(any(), any(), any());
    }

    @Test
    void triggerProbeByChannel_channelGate_keyDeleted_cleansRecord() {
        // 门对应 Key 已删除：无法探测，直接清理
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(channelState));
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(null);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).removeState(channelState);
        verify(probeService, never()).probe(any(), any(), any());
    }

    @Test
    void triggerProbeByChannel_channelGate_noEnabledModel_cleansRecord() {
        // 渠道下无启用模型可探测：直接清理
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(channelState));
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(modelService.getFirstEnabledChannelModelByChannelId(1L)).thenReturn(null);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).removeState(channelState);
        verify(probeService, never()).probe(any(), any(), any());
    }

    @Test
    void triggerProbeByChannel_fullChannelGate_usesFirstAvailableKey() {
        // 全渠道门（apiKeyId 为空）：取渠道任一启用 Key 探测
        channelState.setChannelApiKeyId(null);
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(channelState));
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getAvailableApiKeys(1L)).thenReturn(List.of(apiKey));
        when(modelService.getFirstEnabledChannelModelByChannelId(1L)).thenReturn(channelModel);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(true);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).removeState(channelState);
        verify(probeService).probe(channel, channelModel, apiKey);
    }

    @Test
    void triggerProbeByChannel_throttled_withinIntervalSkipsRepeatedTriggers() throws Exception {
        // 节流 0.2 秒：连续触发只执行一次
        when(adminConfigService.getCircuitBreakerProbeThrottleSeconds()).thenReturn(0.2d);
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(modelState));
        when(modelService.getChannelModelById(10L)).thenReturn(channelModel);
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(true);

        service.triggerProbeByChannel(1L);
        service.triggerProbeByChannel(1L);
        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000).times(1)).recoverModelState(modelState);

        // 超过节流间隔后再触发：执行第二次
        Thread.sleep(300);
        service.triggerProbeByChannel(1L);
        verify(circuitBreakerService, timeout(2000).times(2)).recoverModelState(modelState);
    }

    @Test
    void triggerProbeByChannel_throttleZero_disabled() throws Exception {
        // 节流配置为 0：不节流，每次触发都执行
        when(adminConfigService.getCircuitBreakerProbeThrottleSeconds()).thenReturn(0d);
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(modelState));
        when(modelService.getChannelModelById(10L)).thenReturn(channelModel);
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(true);

        service.triggerProbeByChannel(1L);
        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000).times(2)).recoverModelState(modelState);
    }

    @Test
    void triggerProbeByChannel_throttleIsPerChannel() throws Exception {
        // 不同渠道互不节流：渠道 1 触发后，渠道 2 立即可执行
        CircuitBreakerState state2 = new CircuitBreakerState();
        state2.setId(101L);
        state2.setChannelId(2L);
        state2.setChannelApiKeyId(20L);
        state2.setChannelModelId(10L);
        state2.setIsOpen(1);

        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(modelState));
        when(circuitBreakerService.listExpiredStatesByChannel(2L)).thenReturn(List.of(state2));
        when(modelService.getChannelModelById(10L)).thenReturn(channelModel);
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(true);

        service.triggerProbeByChannel(1L);
        service.triggerProbeByChannel(2L);

        verify(circuitBreakerService, timeout(2000).times(1)).recoverModelState(modelState);
        verify(circuitBreakerService, timeout(2000).times(1)).recoverModelState(state2);
    }

    @Test
    void triggerProbeByChannel_nullChannel_doesNothing() {
        service.triggerProbeByChannel(null);

        verify(probeService, never()).probe(any(), any(), any());
        verify(circuitBreakerService, never()).listExpiredStatesByChannel(any());
    }

    @Test
    void triggerProbeByChannel_noExpiredRecords_skipsProbe() {
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of());

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).listExpiredStatesByChannel(1L);
        verify(probeService, never()).probe(any(), any(), any());
    }

    // ==================== 模型级门解析与清理 ====================

    @Test
    void triggerProbeByChannel_channelModelDeleted_cleansRecord() {
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(modelState));
        when(modelService.getChannelModelById(10L)).thenReturn(null);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).removeState(modelState);
        verify(probeService, never()).probe(any(), any(), any());
    }

    @Test
    void triggerProbeByChannel_disabledChannel_cleansRecord() {
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(modelState));
        when(modelService.getChannelModelById(10L)).thenReturn(channelModel);
        channel.setEnabled(0);
        when(modelService.getChannelById(1L)).thenReturn(channel);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).removeState(modelState);
        verify(probeService, never()).probe(any(), any(), any());
    }

    @Test
    void triggerProbeByChannel_disabledApiKey_cleansRecord() {
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(modelState));
        when(modelService.getChannelModelById(10L)).thenReturn(channelModel);
        when(modelService.getChannelById(1L)).thenReturn(channel);
        apiKey.setEnabled(0);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).removeState(modelState);
        verify(probeService, never()).probe(any(), any(), any());
    }

    @Test
    void triggerProbeByChannel_legacyRecordWithoutApiKey_usesFirstEnabledKey() {
        modelState.setChannelApiKeyId(null);
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenReturn(List.of(modelState));
        when(modelService.getChannelModelById(10L)).thenReturn(channelModel);
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getAvailableApiKeys(1L)).thenReturn(List.of(apiKey));
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(true);

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).recoverModelState(modelState);
    }

    // ==================== 周期全量扫描 ====================

    @Test
    void scanExpiredGates_processesAllExpiredRecords() {
        when(circuitBreakerService.listExpiredStates()).thenReturn(List.of(modelState, channelState));
        when(modelService.getChannelModelById(10L)).thenReturn(channelModel);
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(true);
        when(modelService.getFirstEnabledChannelModelByChannelId(1L)).thenReturn(channelModel);

        service.scanExpiredGates();

        verify(circuitBreakerService, timeout(2000)).recoverModelState(modelState);
        verify(circuitBreakerService, timeout(2000)).removeState(channelState);
    }

    @Test
    void scanExpiredGates_noExpiredRecords_doesNothing() {
        when(circuitBreakerService.listExpiredStates()).thenReturn(List.of());

        service.scanExpiredGates();

        verify(circuitBreakerService, timeout(2000)).listExpiredStates();
        verify(probeService, never()).probe(any(), any(), any());
        verify(circuitBreakerService, never()).removeState(any());
        verify(circuitBreakerService, never()).recoverModelState(any());
        verify(circuitBreakerService, never()).renewState(any(), anyInt());
    }

    @Test
    void scanExpiredGates_exceptionInOneGate_doesNotBlockOthers() {
        CircuitBreakerState broken = new CircuitBreakerState();
        broken.setId(999L);
        broken.setChannelModelId(10L);
        broken.setChannelId(1L);
        broken.setChannelApiKeyId(20L);
        broken.setIsOpen(1);

        when(circuitBreakerService.listExpiredStates()).thenReturn(List.of(broken, channelState));
        when(modelService.getChannelModelById(10L)).thenThrow(new RuntimeException("db error"));
        // 渠道级门正常处理所需的依赖
        when(modelService.getChannelById(1L)).thenReturn(channel);
        when(channelApiKeyService.getById(20L)).thenReturn(apiKey);
        when(modelService.getFirstEnabledChannelModelByChannelId(1L)).thenReturn(channelModel);
        when(probeService.probe(channel, channelModel, apiKey)).thenReturn(true);

        service.scanExpiredGates();

        // 抛异常的模型门被跳过，渠道门仍正常处理
        verify(circuitBreakerService, timeout(2000)).removeState(channelState);
    }

    @Test
    void scanExpiredGates_exceptionInList_doesNothing() {
        when(circuitBreakerService.listExpiredStates()).thenThrow(new RuntimeException("db error"));

        service.scanExpiredGates();

        verify(circuitBreakerService, timeout(2000)).listExpiredStates();
        verify(circuitBreakerService, never()).removeState(any());
        verify(probeService, never()).probe(any(), any(), any());
    }

    @Test
    void triggerProbeByChannel_exceptionInList_logsAndSkips() {
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenThrow(new RuntimeException("db error"));

        service.triggerProbeByChannel(1L);

        verify(circuitBreakerService, timeout(2000)).listExpiredStatesByChannel(1L);
        verify(circuitBreakerService, never()).removeState(any());
        verify(probeService, never()).probe(any(), any(), any());
    }

    @Test
    void worker_survivesTaskExceptions() {
        // 处理抛异常后工作线程继续存活：后续任务仍能执行
        when(circuitBreakerService.listExpiredStatesByChannel(1L)).thenThrow(new RuntimeException("db error"));
        when(circuitBreakerService.listExpiredStatesByChannel(2L)).thenReturn(List.of());

        service.triggerProbeByChannel(1L);
        service.triggerProbeByChannel(2L);

        verify(circuitBreakerService, timeout(2000)).listExpiredStatesByChannel(2L);
    }
}
