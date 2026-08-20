package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ChannelCrud 单元测试
 * 验证定时刷新任务的渠道筛选条件：仅启用 + 开启自动刷新 + 至少有一个可用 API Key
 */
class ChannelCrudTest {

    private ChannelMapper channelMapper;
    private ChannelCrud crud;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 解析实体列名需要 MyBatis-Plus 元数据
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Channel.class);
    }

    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        ChannelModelMapper channelModelMapper = mock(ChannelModelMapper.class);
        crud = new ChannelCrud(channelMapper, channelModelMapper);
    }

    @Test
    void listAutoRefreshChannels_filtersEnabledAutoRefreshWithAvailableKey() {
        when(channelMapper.selectList(any())).thenReturn(List.of());

        crud.listAutoRefreshChannels();

        ArgumentCaptor<LambdaQueryWrapper<Channel>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(channelMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        // 仅启用渠道
        assertThat(sql).contains("enabled =");
        // 仅开启自动刷新的渠道
        assertThat(sql).contains("model_refresh_enabled =");
        // 仅至少有一个启用 API Key 的渠道
        assertThat(sql).contains("id IN (SELECT channel_id FROM channel_api_keys WHERE enabled = 1)");
    }
}