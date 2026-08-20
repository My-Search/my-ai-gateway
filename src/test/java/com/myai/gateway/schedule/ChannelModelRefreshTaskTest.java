package com.myai.gateway.schedule;

import com.myai.gateway.entity.Channel;
import com.myai.gateway.service.AdminConfigService;
import com.myai.gateway.service.ChannelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * ChannelModelRefreshTask 单元测试
 * 验证按配置间隔触发刷新、单渠道失败不中断等调度语义
 */
class ChannelModelRefreshTaskTest {

    private ChannelService channelService;
    private AdminConfigService adminConfigService;
    private ChannelModelRefreshTask task;

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        adminConfigService = mock(AdminConfigService.class);
        task = new ChannelModelRefreshTask(channelService, adminConfigService);
    }

    private Channel channel(long id, String name) {
        Channel c = new Channel(name, "openai", "https://example.com/v1");
        c.setId(id);
        c.setModelRefreshEnabled(1);
        return c;
    }

    @Test
    void tick_firstRun_refreshesAllAutoRefreshChannels() {
        when(adminConfigService.getChannelModelRefreshIntervalMinutes()).thenReturn(30);
        when(channelService.listAutoRefreshChannels()).thenReturn(List.of(channel(1L, "c1"), channel(2L, "c2")));

        task.tick();

        verify(channelService).reloadModels(1L);
        verify(channelService).reloadModels(2L);
    }

    @Test
    void tick_beforeInterval_skipsRefresh() {
        when(adminConfigService.getChannelModelRefreshIntervalMinutes()).thenReturn(30);
        when(channelService.listAutoRefreshChannels()).thenReturn(List.of(channel(1L, "c1")));

        // 首次执行：lastFullRefreshAt 为空，立即刷新
        task.tick();
        verify(channelService).reloadModels(1L);

        // 间隔内再次触发：未到刷新时间，跳过
        clearInvocations(channelService);
        task.tick();
        verify(channelService, never()).listAutoRefreshChannels();
        verify(channelService, never()).reloadModels(anyLong());
    }

    @Test
    void tick_channelFailure_doesNotStopOthers() {
        when(adminConfigService.getChannelModelRefreshIntervalMinutes()).thenReturn(30);
        when(channelService.listAutoRefreshChannels()).thenReturn(List.of(channel(1L, "c1"), channel(2L, "c2")));
        when(channelService.reloadModels(1L)).thenThrow(new RuntimeException("provider down"));

        task.tick();

        verify(channelService).reloadModels(1L);
        verify(channelService).reloadModels(2L);
    }

    @Test
    void tick_whenNoAutoRefreshChannels_doesNothing() {
        when(adminConfigService.getChannelModelRefreshIntervalMinutes()).thenReturn(30);
        when(channelService.listAutoRefreshChannels()).thenReturn(List.of());

        task.tick();

        // 空列表直接返回，不触发任何 reload
        verify(channelService, never()).reloadModels(anyLong());
    }
}