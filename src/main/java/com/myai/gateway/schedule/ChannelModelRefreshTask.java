package com.myai.gateway.schedule;

import com.myai.gateway.entity.Channel;
import com.myai.gateway.service.AdminConfigService;
import com.myai.gateway.service.ChannelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 渠道模型自动刷新定时任务（轻量调度器）
 * <p>单击程 tick，按系统配置间隔批量刷新开启了自动刷新（model_refresh_enabled=1）
 * 且已启用（enabled=1）的渠道模型列表。</p>
 *
 * <ul>
 *   <li>tick 间隔 60s：检查是否到批量刷新时间。</li>
 *   <li>刷新间隔取系统配置 {@code channel_model_refresh_interval_minutes}（默认 30 分钟）。</li>
 *   <li>应用启动后立即执行一次（lastFullRefresh 初始为空），之后按配置间隔执行。</li>
 *   <li>单个渠道刷新失败不影响其他渠道；上一轮未完成时跳过本轮，避免任务重叠。</li>
 * </ul>
 */
@Component
public class ChannelModelRefreshTask {

    private static final Logger log = LoggerFactory.getLogger(ChannelModelRefreshTask.class);

    /** tick 间隔（毫秒）：fixedDelay，上一轮完成后隔这么久再跑 */
    private static final long TICK_INTERVAL_MS = 60_000L;

    private final ChannelService channelService;
    private final AdminConfigService adminConfigService;

    /** 上次批量刷新时间（null=尚未执行，启动后立即刷新一次） */
    private volatile Instant lastFullRefreshAt = null;

    /** 防止上一轮刷新尚未完成时重复进入 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ChannelModelRefreshTask(ChannelService channelService,
                                   AdminConfigService adminConfigService) {
        this.channelService = channelService;
        this.adminConfigService = adminConfigService;
    }

    @Scheduled(fixedDelay = TICK_INTERVAL_MS)
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            return; // 上一轮尚未完成，跳过本轮
        }
        try {
            int intervalMinutes = adminConfigService.getChannelModelRefreshIntervalMinutes();
            Instant now = Instant.now();
            if (lastFullRefreshAt != null
                    && Duration.between(lastFullRefreshAt, now).toMinutes() < intervalMinutes) {
                return; // 未到批量刷新时间
            }
            lastFullRefreshAt = now;
            refreshAll();
        } finally {
            running.set(false);
        }
    }

    /**
     * 遍历开启了自动刷新的渠道，逐个重载模型。
     */
    private void refreshAll() {
        List<Channel> channels = channelService.listAutoRefreshChannels();
        if (channels.isEmpty()) {
            return;
        }
        log.info("渠道模型自动刷新开始 - {} 个渠道", channels.size());
        int successCount = 0;
        for (Channel channel : channels) {
            try {
                channelService.reloadModels(channel.getId());
                successCount++;
            } catch (Exception e) {
                log.warn("渠道模型自动刷新失败 (渠道 {}): {}", channel.getName(), e.getMessage());
            }
        }
        log.info("渠道模型自动刷新完成 - 成功 {} / {} 个渠道", successCount, channels.size());
    }
}