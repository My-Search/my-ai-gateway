package com.myai.gateway.service;

import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * CircuitGate 单元测试
 * 验证到期记录查询、模型级探测开门（含渠道门联动规则）、续期与删记录
 */
class CircuitGateTest {

    private CircuitBreakerStateMapper stateMapper;
    private CircuitGate gate;

    @BeforeEach
    void setUp() {
        stateMapper = mock(CircuitBreakerStateMapper.class);
        gate = new CircuitGate(stateMapper);
    }

    @Test
    void listExpiredStates_returnsOpenAndExpiredRecords() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        assertThat(gate.listExpiredStates()).isEmpty();

        verify(stateMapper).selectList(any());
    }

    @Test
    void listExpiredStatesByChannel_queriesOnlyThatChannel() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        assertThat(gate.listExpiredStatesByChannel(1L)).isEmpty();
        verify(stateMapper, times(1)).selectList(any());
    }

    @Test
    void listExpiredStatesByChannel_nullChannel_returnsEmpty() {
        assertThat(gate.listExpiredStatesByChannel(null)).isEmpty();
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

        gate.recoverModelState(state);

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
        gate.recoverModelState(state);

        verify(stateMapper).delete(captor.capture());
        String sql = extractWrapperSql(captor.getValue());
        assertThat(sql).contains("channelId")
                .contains("channelApiKeyId")
                .contains("openedAt")
                .contains("channelModelId IS NULL");
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

        gate.recoverModelState(state);

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

        gate.recoverModelState(state);

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

        gate.recoverModelState(state);

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

        gate.renewState(state, 60);

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

        gate.renewState(state, 30);

        verify(stateMapper).updateById(argThat((CircuitBreakerState s) -> s.getFailCount() == 1));
    }

    @Test
    void removeState_deletesRecord() {
        CircuitBreakerState state = new CircuitBreakerState();
        state.setId(9L);

        gate.removeState(state);

        verify(stateMapper).deleteById(9L);
    }

    @Test
    void removeState_nullOrMissingId_doesNothing() {
        CircuitBreakerState nullState = null;
        gate.removeState(nullState);
        gate.removeState(new CircuitBreakerState());

        verify(stateMapper, never()).deleteById(anyLong());
    }

    /**
     * 提取 Wrapper 的条件 SQL（避免 getSqlSegment 依赖 MyBatis-Plus lambda 缓存，
     * 纯 mock 环境下不可用）。
     */
    @SuppressWarnings("unchecked")
    private static String extractWrapperSql(com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState> wrapper) {
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
}