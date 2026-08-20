package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 渠道基本操作 - 负责渠道的增删改查
 */
@Component
public class ChannelCrud {

    private static final Logger log = LoggerFactory.getLogger(ChannelCrud.class);

    private final ChannelMapper channelMapper;
    private final ChannelModelMapper channelModelMapper;

    public ChannelCrud(ChannelMapper channelMapper, ChannelModelMapper channelModelMapper) {
        this.channelMapper = channelMapper;
        this.channelModelMapper = channelModelMapper;
    }

    public ChannelMapper getChannelMapper() {
        return channelMapper;
    }

    /**
     * 列出所有渠道
     */
    public List<Channel> listAll() {
        return channelMapper.selectList(
                new LambdaQueryWrapper<Channel>()
                        .orderByAsc(Channel::getCreatedAt));
    }

    /**
     * 列出所有启用的渠道
     */
    public List<Channel> listEnabled() {
        return channelMapper.selectList(
                new LambdaQueryWrapper<Channel>()
                        .eq(Channel::getEnabled, 1)
                        .orderByAsc(Channel::getSortOrder));
    }

    /**
     * 列出启用、开启模型自动刷新、且至少有一个可用（启用）API Key 的渠道
     * （供定时刷新任务使用；无可用 Key 的渠道刷新无意义，直接跳过）
     */
    public List<Channel> listAutoRefreshChannels() {
        return channelMapper.selectList(
                new LambdaQueryWrapper<Channel>()
                        .eq(Channel::getEnabled, 1)
                        .eq(Channel::getModelRefreshEnabled, 1)
                        .inSql(Channel::getId,
                                "SELECT channel_id FROM channel_api_keys WHERE enabled = 1")
                        .orderByAsc(Channel::getSortOrder));
    }

    /**
     * 根据ID获取渠道
     */
    public Channel getById(Long id) {
        return channelMapper.selectById(id);
    }

    /**
     * 创建渠道并自动加载模型
     * <p>model_refresh_enabled=0（不刷新）时跳过创建后的自动拉取，仅手动「获取模型」可用。</p>
     */
    @Transactional
    public Channel create(Channel channel, ChannelModelLoader modelLoader) {
        if (channel.getBaseUrl() != null) {
            channel.setBaseUrl(channel.getBaseUrl().trim());
        }
        if (channel.getModelRefreshEnabled() == null) {
            channel.setModelRefreshEnabled(1);
        }
        channelMapper.insert(channel);
        // 修复 SQLite 下 MyBatis Plus 无法正确获取自增 ID 的问题
        if (channel.getId() == null) {
            Long generatedId = channelMapper.getLastInsertId();
            channel.setId(generatedId);
        }
        // 创建渠道后自动加载模型（不刷新模式跳过）
        if (channel.getModelRefreshEnabled() == 1) {
            modelLoader.loadModelsForChannel(channel);
        }
        return channel;
    }

    /**
     * 更新渠道基本信息
     */
    @Transactional
    public Channel update(Channel channel) {
        if (channel.getBaseUrl() != null) {
            channel.setBaseUrl(channel.getBaseUrl().trim());
        }
        channelMapper.updateById(channel);
        return channel;
    }

    /**
     * 删除渠道（级联删除模型、API Key、熔断状态）
     */
    @Transactional
    public void delete(Long channelId, ChannelModelLoader modelLoader,
                       ChannelApiKeyCleanup apiKeyCleanup, CircuitBreakerCleanup circuitBreakerCleanup) {
        // 1. 清理入口模型关联
        List<ChannelModel> channelModels = channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channelId));
        List<Long> channelModelIds = channelModels.stream()
                .map(ChannelModel::getId)
                .collect(Collectors.toList());

        if (!channelModelIds.isEmpty()) {
            try {
                int deletedRels = modelLoader.getModelChannelRelMapper().delete(
                        new LambdaQueryWrapper<com.myai.gateway.entity.ModelChannelRel>()
                                .in(com.myai.gateway.entity.ModelChannelRel::getChannelModelId, channelModelIds));
                if (deletedRels > 0) {
                    log.info("清理了 {} 条入口模型关联记录 (渠道模型 IDs: {})", deletedRels, channelModelIds);
                }
            } catch (Exception e) {
                log.warn("清理入口模型关联记录失败 (渠道 {}): {}", channelId, e.getMessage());
            }
        }

        // 2. 删除渠道模型
        try {
            channelModelMapper.delete(
                    new LambdaQueryWrapper<ChannelModel>()
                            .eq(ChannelModel::getChannelId, channelId));
        } catch (Exception e) {
            log.warn("删除渠道模型失败 (渠道 {}): {}", channelId, e.getMessage());
        }

        // 3. 删除 API Key
        try {
            int deletedKeys = apiKeyCleanup.deleteAllByChannelId(channelId);
            if (deletedKeys > 0) {
                log.info("清理了 {} 条渠道 API Key 记录 (渠道 {})", deletedKeys, channelId);
            }
        } catch (Exception e) {
            log.warn("删除渠道 API Key 失败 (渠道 {}): {}", channelId, e.getMessage());
        }

        // 4. 删除熔断状态
        try {
            int deletedStates = circuitBreakerCleanup.deleteByChannelId(channelId);
            if (deletedStates > 0) {
                log.info("清理了 {} 条熔断状态记录 (渠道 {})", deletedStates, channelId);
            }
        } catch (Exception e) {
            log.warn("删除熔断状态失败 (渠道 {}): {}", channelId, e.getMessage());
        }

        // 5. 删除渠道本身
        channelMapper.deleteById(channelId);
        log.info("渠道 {} 已删除，相关关联数据已清理", channelId);
    }
}
