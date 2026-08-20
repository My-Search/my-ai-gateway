package com.myai.gateway.service;

import org.springframework.stereotype.Component;

/**
 * API Key 清理 - 负责删除渠道相关的 API Key
 */
@Component
public class ChannelApiKeyCleanup {

    private final ChannelApiKeyService channelApiKeyService;

    public ChannelApiKeyCleanup(ChannelApiKeyService channelApiKeyService) {
        this.channelApiKeyService = channelApiKeyService;
    }

    /**
     * 删除指定渠道的所有 API Key
     */
    public int deleteAllByChannelId(Long channelId) {
        return channelApiKeyService.deleteAllByChannelId(channelId);
    }
}
