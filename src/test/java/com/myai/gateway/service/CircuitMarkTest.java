package com.myai.gateway.service;

import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CircuitMark 单元测试
 * 验证"全部调用途径被熔断才显示熔断"的展示判定（isFullyBroken）、批量标记计算
 * 与手动解除。查询路径用真 CircuitCheck + mock Mapper，保持 SQL 语义被真实执行。
 */
class CircuitMarkTest {

    private CircuitBreakerStateMapper stateMapper;
    private CircuitCheck circuitCheck;
    private CircuitGate circuitGate;
    private ModelService modelService;
    private ChannelApiKeyService channelApiKeyService;
    private CircuitMark mark;

    @BeforeEach
    void setUp() {
        stateMapper = mock(CircuitBreakerStateMapper.class);
        circuitCheck = new CircuitCheck(stateMapper);
        circuitGate = mock(CircuitGate.class);
        modelService = mock(ModelService.class);
        channelApiKeyService = mock(ChannelApiKeyService.class);
        mark = new CircuitMark(circuitCheck, circuitGate, modelService, channelApiKeyService);
    }

    // ==================== 工厂 ====================

    private ChannelApiKey key(long id, int enabled) {
        ChannelApiKey key = new ChannelApiKey();
        key.setId(id);
        key.setEnabled(enabled);
        return key;
    }

    /** 构造熔断记录 */
    private CircuitBreakerState state(long id, Long channelModelId, long channelId, Long apiKeyId, LocalDateTime expireAt) {
        CircuitBreakerState s = new CircuitBreakerState();
        s.setId(id);
        s.setChannelModelId(channelModelId);
        s.setChannelId(channelId);
        s.setChannelApiKeyId(apiKeyId);
        s.setExpireAt(expireAt);
        s.setIsOpen(1);
        return s;
    }

    private ModelChannelRel rel(long id, Long channelModelId, Long channelId) {
        ModelChannelRel rel = new ModelChannelRel();
        rel.setId(id);
        rel.setChannelModelId(channelModelId);
        rel.setChannelId(channelId);
        return rel;
    }

    private ChannelModel channelModel(long id, Long channelId, Long apiKeyId) {
        ChannelModel cm = new ChannelModel();
        cm.setId(id);
        cm.setChannelId(channelId);
        cm.setChannelApiKeyId(apiKeyId);
        cm.setEnabled(1);
        return cm;
    }

    // ==================== isFullyBroken：全部 Key 熔断才显示 ====================

    @Test
    void isFullyBroken_boundKeyBroken_returnsTrue() {
        // 绑定 Key 是唯一途径：该 Key 被熔断 → 全部不可用
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        // getActiveBrokenStates 两次查询：模型级返回该 Key 的模型熔断记录，渠道级为空
        when(stateMapper.selectList(any())).thenReturn(
                List.of(state(1L, 10L, 1L, 20L, LocalDateTime.now().plusSeconds(30))), List.of());

        assertThat(mark.isFullyBroken(10L, 1L, 20L)).isTrue();
        verify(channelApiKeyService, never()).getAvailableApiKeys(any());
    }

    @Test
    void isFullyBroken_boundKeyAvailable_returnsFalse() {
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        when(stateMapper.selectList(any())).thenReturn(List.of());

        assertThat(mark.isFullyBroken(10L, 1L, 20L)).isFalse();
    }

    @Test
    void isFullyBroken_boundKeyDisabledOrMissing_returnsFalse() {
        // 绑定 Key 禁用/不存在：配置问题而非熔断，不显示熔断
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 0));
        when(channelApiKeyService.getById(21L)).thenReturn(null);

        assertThat(mark.isFullyBroken(10L, 1L, 20L)).isFalse();
        assertThat(mark.isFullyBroken(10L, 1L, 21L)).isFalse();
        verify(stateMapper, never()).selectList(any());
    }

    @Test
    void isFullyBroken_unboundAllKeysBroken_returnsTrue() {
        // 未绑定 Key：轮询渠道下所有可用 Key，全部被熔断才显示
        when(channelApiKeyService.getAvailableApiKeys(1L)).thenReturn(List.of(key(20L, 1), key(21L, 1)));
        when(stateMapper.selectList(any())).thenReturn(
                List.of(state(1L, 10L, 1L, 20L, LocalDateTime.now().plusSeconds(30)),
                        state(2L, 10L, 1L, 21L, LocalDateTime.now().plusSeconds(60))), List.of());

        assertThat(mark.isFullyBroken(10L, 1L, null)).isTrue();
        verify(channelApiKeyService, never()).getById(any());
    }

    @Test
    void isFullyBroken_unboundOneKeyAvailable_returnsFalse() {
        // 部分 Key 熔断：请求可路由到其余 Key，模型仍可用，不显示熔断
        when(channelApiKeyService.getAvailableApiKeys(1L)).thenReturn(List.of(key(20L, 1), key(21L, 1)));
        when(stateMapper.selectList(any())).thenReturn(
                List.of(state(1L, 10L, 1L, 20L, LocalDateTime.now().plusSeconds(30))), List.of());

        assertThat(mark.isFullyBroken(10L, 1L, null)).isFalse();
    }

    @Test
    void isFullyBroken_unboundNoAvailableKeys_returnsFalse() {
        // 渠道无可用 Key：非熔断范畴，不显示
        when(channelApiKeyService.getAvailableApiKeys(1L)).thenReturn(List.of());

        assertThat(mark.isFullyBroken(10L, 1L, null)).isFalse();
        verify(stateMapper, never()).selectList(any());
    }

    @Test
    void isFullyBroken_nullParams_returnsFalse() {
        assertThat(mark.isFullyBroken(null, 1L, 20L)).isFalse();
        assertThat(mark.isFullyBroken(10L, null, 20L)).isFalse();
        assertThat(mark.isFullyBroken(null, null, null)).isFalse();
        verifyNoInteractions(channelApiKeyService);
    }

    @Test
    void isFullyBroken_expiredRecordStillCounts() {
        // 到期不自动恢复：记录已过期的仍视为熔断（isOpen=1 即熔断）
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        when(stateMapper.selectList(any())).thenReturn(
                List.of(state(1L, 10L, 1L, 20L, LocalDateTime.now().minusSeconds(10))), List.of());

        assertThat(mark.isFullyBroken(10L, 1L, 20L)).isTrue();
    }

    @Test
    void isFullyBroken_channelLevelGateBlocksAllPaths() {
        // 渠道级按 Key 熔断：该 Key 下的模型全部途径不可用
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        when(stateMapper.selectList(any())).thenReturn(List.of(),
                List.of(state(1L, null, 1L, 20L, LocalDateTime.now().plusSeconds(30))));

        assertThat(mark.isFullyBroken(10L, 1L, 20L)).isTrue();
    }

    @Test
    void isFullyBroken_fullChannelGateBlocksAllPaths() {
        // 全渠道熔断（apiKeyId IS NULL）：所有 Key 均不可用
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        when(stateMapper.selectList(any())).thenReturn(List.of(),
                List.of(state(1L, null, 1L, null, LocalDateTime.now().plusSeconds(30))));

        assertThat(mark.isFullyBroken(10L, 1L, 20L)).isTrue();
    }

    // ==================== computeRelBrokenMarks：批量熔断标记 ====================

    @Test
    void computeRelBrokenMarks_modelAndChannelStates_bothScopeAndEarliestExpire() {
        ModelChannelRel rel = rel(1L, 10L, 1L);
        when(modelService.getChannelModelsByIds(List.of(10L))).thenReturn(List.of(channelModel(10L, 1L, 20L)));
        when(channelApiKeyService.getByIds(List.of(20L))).thenReturn(List.of(key(20L, 1)));
        when(channelApiKeyService.listEnabledByChannelIds(List.of(1L))).thenReturn(Map.of());
        LocalDateTime modelExpire = LocalDateTime.now().plusSeconds(30);
        LocalDateTime channelExpire = LocalDateTime.now().plusSeconds(60);
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state(1L, 10L, 1L, 20L, modelExpire),
                state(2L, null, 1L, 20L, channelExpire)));

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = mark.computeRelBrokenMarks(List.of(rel));

        assertThat(marks).containsKey(1L);
        CircuitBreakerService.RelBrokenMark brokenMark = marks.get(1L);
        assertThat(brokenMark.scope).isEqualTo("both");
        assertThat(brokenMark.expireAt).isEqualTo(modelExpire); // 展示最早到期（下次探测）时间
    }

    @Test
    void computeRelBrokenMarks_expiredRecordStillCounts() {
        // 到期不自动恢复：过期记录仍计入熔断标记
        ModelChannelRel rel = rel(1L, 10L, 1L);
        when(modelService.getChannelModelsByIds(List.of(10L))).thenReturn(List.of(channelModel(10L, 1L, 20L)));
        when(channelApiKeyService.getByIds(List.of(20L))).thenReturn(List.of(key(20L, 1)));
        when(channelApiKeyService.listEnabledByChannelIds(List.of(1L))).thenReturn(Map.of());
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state(1L, 10L, 1L, 20L, LocalDateTime.now().minusSeconds(10))));

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = mark.computeRelBrokenMarks(List.of(rel));

        assertThat(marks.get(1L).scope).isEqualTo("model");
    }

    @Test
    void computeRelBrokenMarks_otherModelStateDoesNotAffectRel() {
        // 渠道 1 下 a（10）、b（11）两模型：b 已恢复（无记录），a 仍熔断 → 只有 a 被标记
        ModelChannelRel relA = rel(1L, 10L, 1L);
        ModelChannelRel relB = rel(2L, 11L, 1L);
        when(modelService.getChannelModelsByIds(List.of(10L, 11L)))
                .thenReturn(List.of(channelModel(10L, 1L, 20L), channelModel(11L, 1L, 20L)));
        when(channelApiKeyService.getByIds(List.of(20L))).thenReturn(List.of(key(20L, 1)));
        when(channelApiKeyService.listEnabledByChannelIds(List.of(1L))).thenReturn(Map.of());
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state(1L, 10L, 1L, 20L, LocalDateTime.now().plusSeconds(30))));

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = mark.computeRelBrokenMarks(List.of(relA, relB));

        assertThat(marks).containsOnlyKeys(1L);
        assertThat(marks.get(1L).scope).isEqualTo("model");
    }

    @Test
    void computeRelBrokenMarks_channelScopeGate_marksChannel() {
        ModelChannelRel rel = rel(1L, 10L, 1L);
        when(modelService.getChannelModelsByIds(List.of(10L))).thenReturn(List.of(channelModel(10L, 1L, 20L)));
        when(channelApiKeyService.getByIds(List.of(20L))).thenReturn(List.of(key(20L, 1)));
        when(channelApiKeyService.listEnabledByChannelIds(List.of(1L))).thenReturn(Map.of());
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state(2L, null, 1L, 20L, LocalDateTime.now().plusSeconds(60))));

        CircuitBreakerService.RelBrokenMark brokenMark = mark.computeRelBrokenMarks(List.of(rel)).get(1L);

        assertThat(brokenMark.scope).isEqualTo("channel");
    }

    @Test
    void computeRelBrokenMarks_boundKeyDisabled_noMark() {
        // 绑定 Key 已禁用：配置问题而非熔断，不显示
        ModelChannelRel rel = rel(1L, 10L, 1L);
        when(modelService.getChannelModelsByIds(List.of(10L))).thenReturn(List.of(channelModel(10L, 1L, 20L)));
        when(channelApiKeyService.getByIds(List.of(20L))).thenReturn(List.of(key(20L, 0)));
        when(channelApiKeyService.listEnabledByChannelIds(List.of(1L))).thenReturn(Map.of());
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state(1L, 10L, 1L, 20L, LocalDateTime.now().plusSeconds(30))));

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = mark.computeRelBrokenMarks(List.of(rel));

        assertThat(marks).isEmpty();
    }

    @Test
    void computeRelBrokenMarks_unboundRel_usesEnabledKeysOfChannel() {
        // 未绑定 Key 的关联：以渠道全部启用 Key 为调用途径
        ModelChannelRel rel = rel(1L, 10L, 1L);
        when(modelService.getChannelModelsByIds(List.of(10L))).thenReturn(List.of(channelModel(10L, 1L, null)));
        when(channelApiKeyService.getByIds(any())).thenReturn(List.of());
        when(channelApiKeyService.listEnabledByChannelIds(List.of(1L)))
                .thenReturn(Map.of(1L, List.of(key(20L, 1), key(21L, 1))));
        // 两个 Key 均有渠道级熔断记录 → 全部途径熔断
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state(1L, null, 1L, 20L, LocalDateTime.now().plusSeconds(30)),
                state(2L, null, 1L, 21L, LocalDateTime.now().plusSeconds(60))));

        CircuitBreakerService.RelBrokenMark brokenMark = mark.computeRelBrokenMarks(List.of(rel)).get(1L);

        assertThat(brokenMark.scope).isEqualTo("channel");
    }

    @Test
    void computeRelBrokenMarks_unboundRel_oneKeyAvailable_noMark() {
        ModelChannelRel rel = rel(1L, 10L, 1L);
        when(modelService.getChannelModelsByIds(List.of(10L))).thenReturn(List.of(channelModel(10L, 1L, null)));
        when(channelApiKeyService.getByIds(any())).thenReturn(List.of());
        when(channelApiKeyService.listEnabledByChannelIds(List.of(1L)))
                .thenReturn(Map.of(1L, List.of(key(20L, 1), key(21L, 1))));
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state(1L, null, 1L, 20L, LocalDateTime.now().plusSeconds(30))));

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = mark.computeRelBrokenMarks(List.of(rel));

        assertThat(marks).isEmpty(); // Key 21 仍可用 → 不显示熔断
    }

    @Test
    void computeRelBrokenMarks_emptyRels_returnsEmpty() {
        assertThat(mark.computeRelBrokenMarks(List.of())).isEmpty();
        verifyNoInteractions(modelService, channelApiKeyService, stateMapper);
    }

    // ==================== manualRecover：手动解除 ====================

    @Test
    void manualRecover_deletesAllActiveStates() {
        CircuitBreakerState modelState = new CircuitBreakerState();
        modelState.setId(1L);
        modelState.setChannelModelId(10L);
        CircuitBreakerState channelState = new CircuitBreakerState();
        channelState.setId(2L);
        channelState.setChannelId(1L);
        channelState.setChannelModelId(null);

        when(stateMapper.selectList(any())).thenReturn(List.of(modelState), List.of(channelState));

        int count = mark.manualRecover(10L, 1L, 20L);

        assertThat(count).isEqualTo(2);
        verify(circuitGate).removeState(argThat(s -> s.getId().equals(1L)));
        verify(circuitGate).removeState(argThat(s -> s.getId().equals(2L)));
    }

    @Test
    void manualRecover_noActiveStates_deletesNothing() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        int count = mark.manualRecover(10L, 1L, 20L);

        assertThat(count).isZero();
        verify(circuitGate, never()).removeState(any());
    }
}