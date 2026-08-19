package com.myai.gateway.service;

import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.CircuitBreakerConfig;
import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentMatcher;

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

    private CircuitBreakerStateMapper stateMapper;
    private ModelService modelService;
    private ChannelApiKeyService channelApiKeyService;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private CircuitBreakerService service;

    @BeforeEach
    void setUp() {
        stateMapper = mock(CircuitBreakerStateMapper.class);
        modelService = mock(ModelService.class);
        channelApiKeyService = mock(ChannelApiKeyService.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new CircuitBreakerService(stateMapper, modelService, channelApiKeyService, eventPublisher);
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
    void breakerChecks_doNotFilterExpireAt_expiredRecordsStillBlock() {
        // 到期不自动恢复：查询条件只含 isOpen=1，不含 expireAt（到期仅表示需要探测）
        service.isChannelCircuitBroken(1L);
        service.isChannelCircuitBroken(1L, 2L);
        service.isModelCircuitBroken(10L, 2L);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(stateMapper, times(3)).selectCount(captor.capture());
        for (com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState> w : captor.getAllValues()) {
            String sql = extractWrapperSql(w);
            assertThat(sql).contains("isOpen").doesNotContain("expireAt");
        }
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

    // ==================== 门状态管理 ====================

    @Test
    void listExpiredStates_returnsOpenAndExpiredRecords() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listExpiredStates()).isEmpty();

        verify(stateMapper).selectList(any());
    }

    @Test
    void listExpiredStatesByChannel_queriesOnlyThatChannel() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listExpiredStatesByChannel(1L)).isEmpty();
        verify(stateMapper, times(1)).selectList(any());
    }

    @Test
    void listExpiredStatesByChannel_nullChannel_returnsEmpty() {
        assertThat(service.listExpiredStatesByChannel(null)).isEmpty();
        verify(stateMapper, never()).selectList(any());
    }

    @Test
    void recoverModelState_deletesModelRecordAndLinkedChannelRecord() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(1L);
        state.setChannelId(10L);
        state.setChannelApiKeyId(20L);
        state.setChannelModelId(30L);
        state.setOpenedAt(LocalDateTime.now().minusSeconds(60));

        when(stateMapper.deleteById(1L)).thenReturn(1);
        when(stateMapper.delete(any())).thenReturn(1);

        service.recoverModelState(state);

        // 模型级记录删除（模型门开）
        verify(stateMapper).deleteById(1L);
        // 联动删除同 (channelId, channelApiKeyId) 的渠道级记录（渠道门跟随模型恢复）
        verify(stateMapper).delete(any());
    }

    @Test
    void recoverModelState_linkedDeleteOnlyTargetsOlderChannelGates() {
        // 验证联动删除带 openedAt 条件：只删"先于我熔断"的渠道门，
        // 防止探测期间真实请求重新触发的渠道门被误删
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(1L);
        state.setChannelId(10L);
        state.setChannelApiKeyId(20L);
        state.setChannelModelId(30L);
        state.setOpenedAt(LocalDateTime.of(2024, 1, 1, 10, 0));

        when(stateMapper.deleteById(1L)).thenReturn(1);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        service.recoverModelState(state);

        verify(stateMapper).delete(captor.capture());
        String sql = extractWrapperSql(captor.getValue());
        assertThat(sql).contains("channelId")
                .contains("channelApiKeyId")
                .contains("openedAt")
                .contains("channelModelId IS NULL");
    }

    /**
     * 提取 Wrapper 的条件 SQL（避免 getSqlSegment 依赖 MyBatis-Plus lambda 缓存，
     * 纯 mock 环境下不可用）。
     */
    @SuppressWarnings("unchecked")
    private String extractWrapperSql(com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState> wrapper) {
        try {
            // 初始化 TableInfo 缓存（getSqlSegment 的 lambda 列解析依赖它）
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, CircuitBreakerState.class);

            java.lang.reflect.Field field =
                    com.baomidou.mybatisplus.core.conditions.AbstractWrapper.class.getDeclaredField("expression");
            field.setAccessible(true);
            com.baomidou.mybatisplus.core.conditions.segments.MergeSegments segments =
                    (com.baomidou.mybatisplus.core.conditions.segments.MergeSegments) field.get(wrapper);
            String sql = segments.getSqlSegment();
            // 去除占位符参数名（{0} 等），便于纯字符串断言
            return sql.replaceAll("\\{[0-9]+\\}", "?");
        } catch (Exception e) {
            throw new IllegalStateException("提取 wrapper SQL 失败", e);
        }
    }

    @Test
    void recoverModelState_modelRecordAlreadyDeleted_skipsLinkedDelete() {
        // 探测期间真实请求再次触发熔断，旧记录已被删除：不再联动，避免误删新渠道门
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(1L);
        state.setChannelId(10L);
        state.setChannelApiKeyId(20L);
        state.setChannelModelId(30L);
        state.setOpenedAt(LocalDateTime.now().minusSeconds(60));

        when(stateMapper.deleteById(1L)).thenReturn(0);

        service.recoverModelState(state);

        verify(stateMapper, never()).delete(any());
    }

    @Test
    void recoverModelState_withNullOpenedAt_skipsLinkedDelete() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(1L);
        state.setChannelId(10L);
        state.setChannelApiKeyId(20L);
        state.setChannelModelId(30L);
        state.setOpenedAt(null);

        when(stateMapper.deleteById(1L)).thenReturn(1);

        service.recoverModelState(state);

        verify(stateMapper).deleteById(1L);
        // openedAt 未知时保守不联动（无法判断渠道门是否先于模型熔断）
        verify(stateMapper, never()).delete(any());
    }

    @Test
    void recoverModelState_withNullApiKey_doesNotDeleteChannelRecord() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(1L);
        state.setChannelId(10L);
        state.setChannelApiKeyId(null);
        state.setChannelModelId(30L);
        state.setOpenedAt(LocalDateTime.now().minusSeconds(60));

        when(stateMapper.deleteById(1L)).thenReturn(1);

        service.recoverModelState(state);

        verify(stateMapper).deleteById(1L);
        // apiKeyId 为空时不联动删渠道级记录（全渠道记录只能到期开门）
        verify(stateMapper, never()).delete(any());
    }

    @Test
    void renewState_extendsExpireAtAndIncrementsFailCount() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(1L);
        state.setChannelModelId(30L);
        state.setFailCount(2);

        service.renewState(state, 60);

        verify(stateMapper).updateById(argThat((CircuitBreakerState s) ->
                s.getId().equals(1L)
                        && s.getFailCount() == 3
                        && s.getExpireAt().isAfter(LocalDateTime.now().plusSeconds(59))
        ));
    }

    @Test
    void renewState_withNullFailCount_startsAtOne() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(1L);

        service.renewState(state, 30);

        verify(stateMapper).updateById(argThat((CircuitBreakerState s) -> s.getFailCount() == 1));
    }

    @Test
    void removeState_deletesRecord() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(9L);

        service.removeState(state);

        verify(stateMapper).deleteById(9L);
    }

    // ==================== 熔断标记与手动解除 ====================

    @Test
    void getActiveBrokenStates_queriesModelAndChannelScopes() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        service.getActiveBrokenStates(10L, 1L, 20L);

        // 模型级一次查询 + 渠道级一次查询
        verify(stateMapper, times(2)).selectList(any());
    }

    @Test
    void getActiveBrokenStates_skipQueriesForNullScopes() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        service.getActiveBrokenStates(null, 1L, 20L);
        verify(stateMapper, times(1)).selectList(any());

        service.getActiveBrokenStates(10L, null, 20L);
        verify(stateMapper, times(2)).selectList(any());
    }

    @Test
    void getActiveBrokenStates_withNullApiKey_matchesAllKeys() {
        // 渠道模型未指定 Key（channelApiKeyId=null）时，应匹配该模型/渠道下所有 Key 的熔断记录
        when(stateMapper.selectList(any())).thenReturn(List.of());

        service.getActiveBrokenStates(10L, 1L, null);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(stateMapper, times(2)).selectList(captor.capture());
        for (com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState> w : captor.getAllValues()) {
            String sql = extractWrapperSql(w);
            // 不允许出现 channelApiKeyId 过滤条件（否则带具体 Key 的熔断记录查不到）
            assertThat(sql).doesNotContain("channelApiKeyId");
            // 不看过期时间：isOpen=1 即熔断，到期仅表示需要探测
            assertThat(sql).doesNotContain("expireAt");
        }
    }

    @Test
    void getActiveBrokenStates_withApiKey_matchesKeyOrLegacyRecords() {
        // 指定 Key 时匹配该 Key 或旧数据（apiKeyId IS NULL）
        when(stateMapper.selectList(any())).thenReturn(List.of());

        service.getActiveBrokenStates(10L, 1L, 20L);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(stateMapper, times(2)).selectList(captor.capture());
        for (com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState> w : captor.getAllValues()) {
            String sql = extractWrapperSql(w);
            assertThat(sql).contains("channelApiKeyId");
            assertThat(sql).doesNotContain("expireAt");
        }
    }

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

        int count = service.manualRecover(10L, 1L, 20L);

        assertThat(count).isEqualTo(2);
        verify(stateMapper).deleteById(1L);
        verify(stateMapper).deleteById(2L);
    }

    @Test
    void manualRecover_noActiveStates_deletesNothing() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        int count = service.manualRecover(10L, 1L, 20L);

        assertThat(count).isZero();
        verify(stateMapper, never()).deleteById(anyLong());
    }

    // ==================== 熔断展示判定（全部 Key 熔断才显示） ====================

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

    @Test
    void isFullyBroken_boundKeyBroken_returnsTrue() {
        // 绑定 Key 是唯一途径：该 Key 被熔断 → 全部不可用
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        // getActiveBrokenStates 两次查询：模型级返回该 Key 的模型熔断记录，渠道级为空
        when(stateMapper.selectList(any())).thenReturn(
                List.of(state(1L, 10L, 1L, 20L, LocalDateTime.now().plusSeconds(30))), List.of());

        assertThat(service.isFullyBroken(10L, 1L, 20L)).isTrue();
        verify(channelApiKeyService, never()).getAvailableApiKeys(any());
    }

    @Test
    void isFullyBroken_boundKeyAvailable_returnsFalse() {
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        when(stateMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.isFullyBroken(10L, 1L, 20L)).isFalse();
    }

    @Test
    void isFullyBroken_boundKeyDisabledOrMissing_returnsFalse() {
        // 绑定 Key 禁用/不存在：配置问题而非熔断，不显示熔断
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 0));
        when(channelApiKeyService.getById(21L)).thenReturn(null);

        assertThat(service.isFullyBroken(10L, 1L, 20L)).isFalse();
        assertThat(service.isFullyBroken(10L, 1L, 21L)).isFalse();
        verify(stateMapper, never()).selectList(any());
    }

    @Test
    void isFullyBroken_unboundAllKeysBroken_returnsTrue() {
        // 未绑定 Key：轮询渠道下所有可用 Key，全部被熔断才显示
        when(channelApiKeyService.getAvailableApiKeys(1L)).thenReturn(List.of(key(20L, 1), key(21L, 1)));
        when(stateMapper.selectList(any())).thenReturn(
                List.of(state(1L, 10L, 1L, 20L, LocalDateTime.now().plusSeconds(30)),
                        state(2L, 10L, 1L, 21L, LocalDateTime.now().plusSeconds(60))), List.of());

        assertThat(service.isFullyBroken(10L, 1L, null)).isTrue();
        verify(channelApiKeyService, never()).getById(any());
    }

    @Test
    void isFullyBroken_unboundOneKeyAvailable_returnsFalse() {
        // 部分 Key 熔断：请求可路由到其余 Key，模型仍可用，不显示熔断
        when(channelApiKeyService.getAvailableApiKeys(1L)).thenReturn(List.of(key(20L, 1), key(21L, 1)));
        when(stateMapper.selectList(any())).thenReturn(
                List.of(state(1L, 10L, 1L, 20L, LocalDateTime.now().plusSeconds(30))), List.of());

        assertThat(service.isFullyBroken(10L, 1L, null)).isFalse();
    }

    @Test
    void isFullyBroken_unboundNoAvailableKeys_returnsFalse() {
        // 渠道无可用 Key：非熔断范畴，不显示
        when(channelApiKeyService.getAvailableApiKeys(1L)).thenReturn(List.of());

        assertThat(service.isFullyBroken(10L, 1L, null)).isFalse();
        verify(stateMapper, never()).selectList(any());
    }

    @Test
    void isFullyBroken_nullParams_returnsFalse() {
        assertThat(service.isFullyBroken(null, 1L, 20L)).isFalse();
        assertThat(service.isFullyBroken(10L, null, 20L)).isFalse();
        assertThat(service.isFullyBroken(null, null, null)).isFalse();
        verifyNoInteractions(channelApiKeyService);
    }

    @Test
    void isFullyBroken_expiredRecordStillCounts() {
        // 到期不自动恢复：记录已过期的仍视为熔断（isOpen=1 即熔断）
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        when(stateMapper.selectList(any())).thenReturn(
                List.of(state(1L, 10L, 1L, 20L, LocalDateTime.now().minusSeconds(10))), List.of());

        assertThat(service.isFullyBroken(10L, 1L, 20L)).isTrue();
    }

    @Test
    void isFullyBroken_channelLevelGateBlocksAllPaths() {
        // 渠道级按 Key 熔断：该 Key 下的模型全部途径不可用
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        when(stateMapper.selectList(any())).thenReturn(List.of(),
                List.of(state(1L, null, 1L, 20L, LocalDateTime.now().plusSeconds(30))));

        assertThat(service.isFullyBroken(10L, 1L, 20L)).isTrue();
    }

    @Test
    void isFullyBroken_fullChannelGateBlocksAllPaths() {
        // 全渠道熔断（apiKeyId IS NULL）：所有 Key 均不可用
        when(channelApiKeyService.getById(20L)).thenReturn(key(20L, 1));
        when(stateMapper.selectList(any())).thenReturn(List.of(),
                List.of(state(1L, null, 1L, null, LocalDateTime.now().plusSeconds(30))));

        assertThat(service.isFullyBroken(10L, 1L, 20L)).isTrue();
    }

    // ==================== 批量熔断标记计算（管理界面，避免 N+1） ====================

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

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = service.computeRelBrokenMarks(List.of(rel));

        assertThat(marks).containsKey(1L);
        CircuitBreakerService.RelBrokenMark mark = marks.get(1L);
        assertThat(mark.scope).isEqualTo("both");
        assertThat(mark.expireAt).isEqualTo(modelExpire); // 展示最早到期（下次探测）时间
    }

    @Test
    void computeRelBrokenMarks_expiredRecordStillCounts() {
        // 到期不自动恢复：过期记录仍计入熔断标记
        ModelChannelRel rel = rel(1L, 10L, 1L);
        when(modelService.getChannelModelsByIds(List.of(10L))).thenReturn(List.of(channelModel(10L, 1L, 20L)));
        when(channelApiKeyService.getByIds(List.of(20L))).thenReturn(List.of(key(20L, 1)));
        when(channelApiKeyService.listEnabledByChannelIds(List.of(1L))).thenReturn(Map.of());
        when(stateMapper.selectList(any())).thenReturn(List.of(
                state(1L, 10L, 1L, 20L, LocalDateTime.now().minusSeconds(10))), List.of());

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = service.computeRelBrokenMarks(List.of(rel));

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

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = service.computeRelBrokenMarks(List.of(relA, relB));

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

        CircuitBreakerService.RelBrokenMark mark = service.computeRelBrokenMarks(List.of(rel)).get(1L);

        assertThat(mark.scope).isEqualTo("channel");
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

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = service.computeRelBrokenMarks(List.of(rel));

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

        CircuitBreakerService.RelBrokenMark mark = service.computeRelBrokenMarks(List.of(rel)).get(1L);

        assertThat(mark.scope).isEqualTo("channel");
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

        Map<Long, CircuitBreakerService.RelBrokenMark> marks = service.computeRelBrokenMarks(List.of(rel));

        assertThat(marks).isEmpty(); // Key 21 仍可用 → 不显示熔断
    }

    @Test
    void computeRelBrokenMarks_emptyRels_returnsEmpty() {
        assertThat(service.computeRelBrokenMarks(List.of())).isEmpty();
        verifyNoInteractions(modelService, channelApiKeyService, stateMapper);
    }
}
