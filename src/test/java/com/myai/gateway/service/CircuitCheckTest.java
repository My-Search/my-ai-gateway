package com.myai.gateway.service;

import com.myai.gateway.entity.CircuitBreakerState;
import com.myai.gateway.mapper.CircuitBreakerStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CircuitCheck 单元测试
 * 验证各级熔断状态查询的 SQL 条件：渠道级（含按 API Key）、模型级，
 * 以及"不看过期时间"（isOpen=1 即熔断）的语义
 */
class CircuitCheckTest {

    private CircuitBreakerStateMapper stateMapper;
    private CircuitCheck check;

    @BeforeEach
    void setUp() {
        stateMapper = mock(CircuitBreakerStateMapper.class);
        check = new CircuitCheck(stateMapper);
    }

    @Test
    void isChannelCircuitBroken_whenOpenRecordExists_returnsTrue() {
        when(stateMapper.selectCount(any())).thenReturn(1L);

        boolean result = check.isChannelCircuitBroken(1L);

        assertThat(result).isTrue();
        verify(stateMapper).selectCount(any());
    }

    @Test
    void isChannelCircuitBroken_whenNoOpenRecord_returnsFalse() {
        when(stateMapper.selectCount(any())).thenReturn(0L);

        boolean result = check.isChannelCircuitBroken(1L);

        assertThat(result).isFalse();
    }

    @Test
    void isChannelCircuitBroken_withApiKeyId_returnsTrueWhenOpen() {
        when(stateMapper.selectCount(any())).thenReturn(1L);

        boolean result = check.isChannelCircuitBroken(1L, 2L);

        assertThat(result).isTrue();
    }

    @Test
    void isModelCircuitBroken_whenOpenRecordExists_returnsTrue() {
        when(stateMapper.selectCount(any())).thenReturn(1L);

        boolean result = check.isModelCircuitBroken(10L, 2L);

        assertThat(result).isTrue();
    }

    @Test
    void isModelCircuitBroken_withMatchingApiKeyId_returnsTrue() {
        when(stateMapper.selectCount(any())).thenReturn(1L);

        boolean result = check.isModelCircuitBroken(10L, 2L);

        assertThat(result).isTrue();
    }

    @Test
    void breakerChecks_doNotFilterExpireAt_expiredRecordsStillBlock() {
        // 到期不自动恢复：查询条件只含 isOpen=1，不含 expireAt（到期仅表示需要探测）
        check.isChannelCircuitBroken(1L);
        check.isChannelCircuitBroken(1L, 2L);
        check.isModelCircuitBroken(10L, 2L);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(stateMapper, times(3)).selectCount(captor.capture());
        for (com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState> w : captor.getAllValues()) {
            String sql = extractWrapperSql(w);
            assertThat(sql).contains("isOpen").doesNotContain("expireAt");
        }
    }

    @Test
    void getActiveBrokenStates_queriesModelAndChannelScopes() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        check.getActiveBrokenStates(10L, 1L, 20L);

        // 模型级一次查询 + 渠道级一次查询
        verify(stateMapper, times(2)).selectList(any());
    }

    @Test
    void getActiveBrokenStates_skipQueriesForNullScopes() {
        when(stateMapper.selectList(any())).thenReturn(List.of());

        check.getActiveBrokenStates(null, 1L, 20L);
        verify(stateMapper, times(1)).selectList(any());

        check.getActiveBrokenStates(10L, null, 20L);
        verify(stateMapper, times(2)).selectList(any());
    }

    @Test
    void getActiveBrokenStates_withNullApiKey_matchesAllKeys() {
        // 渠道模型未指定 Key（channelApiKeyId=null）时，应匹配该模型/渠道下所有 Key 的熔断记录
        when(stateMapper.selectList(any())).thenReturn(List.of());

        check.getActiveBrokenStates(10L, 1L, null);

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

        check.getActiveBrokenStates(10L, 1L, 20L);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(stateMapper, times(2)).selectList(captor.capture());
        for (com.baomidou.mybatisplus.core.conditions.Wrapper<CircuitBreakerState> w : captor.getAllValues()) {
            String sql = extractWrapperSql(w);
            assertThat(sql).contains("channelApiKeyId");
            assertThat(sql).doesNotContain("expireAt");
        }
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