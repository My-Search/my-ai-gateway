package com.myai.gateway.service;

import com.myai.gateway.config.LocalCacheService;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 渠道服务门面
 * 管理渠道的增删改查，以及加载渠道下的模型列表
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final ChannelCrud channelCrud;
    private final ChannelModelLoader modelLoader;
    private final ChannelModelSync modelSync;
    private final ChannelModelQuery modelQuery;
    private final ChannelApiKeyCleanup apiKeyCleanup;
    private final CircuitBreakerCleanup circuitBreakerCleanup;

    /**
     * 热点查询缓存（可选能力，由 Spring 注入；测试直接 new 时为 null 走直查）
     */
    private LocalCacheService localCacheService;

    public ChannelService(ChannelCrud channelCrud,
                          ChannelModelLoader modelLoader,
                          ChannelModelSync modelSync,
                          ChannelModelQuery modelQuery,
                          ChannelApiKeyCleanup apiKeyCleanup,
                          CircuitBreakerCleanup circuitBreakerCleanup) {
        this.channelCrud = channelCrud;
        this.modelLoader = modelLoader;
        this.modelSync = modelSync;
        this.modelQuery = modelQuery;
        this.apiKeyCleanup = apiKeyCleanup;
        this.circuitBreakerCleanup = circuitBreakerCleanup;
    }

    /**
     * 注入本地缓存服务（可选能力，由 Spring 调用）
     */
    @Autowired(required = false)
    public void setLocalCacheService(LocalCacheService localCacheService) {
        this.localCacheService = localCacheService;
    }

    private boolean hasCache() {
        return localCacheService != null;
    }

    private void invalidate(String namespace, String key) {
        if (hasCache()) {
            localCacheService.invalidate(namespace, key);
        }
    }

    private void invalidateAll(String namespace) {
        if (hasCache()) {
            localCacheService.invalidateAll(namespace);
        }
    }

    // ==================== 渠道 CRUD ====================

    public List<Channel> listAll() {
        return channelCrud.listAll();
    }

    public Channel getById(Long id) {
        return channelCrud.getById(id);
    }

    public Channel create(Channel channel) {
        Channel created = channelCrud.create(channel, modelLoader);
        invalidateAll(LocalCacheService.NS_CHANNEL_MODEL_BY_ID);
        return created;
    }

    public Channel update(Channel channel) {
        Channel updated = channelCrud.update(channel);
        invalidate(LocalCacheService.NS_CHANNEL_BY_ID, String.valueOf(channel.getId()));
        return updated;
    }

    /**
     * 编辑模式下更新渠道并同步模型
     */
    public Channel updateWithModels(Channel channel, String modelsJson) {
        Channel updated = modelSync.updateWithModels(channel, modelsJson);
        invalidate(LocalCacheService.NS_CHANNEL_BY_ID, String.valueOf(channel.getId()));
        invalidateAll(LocalCacheService.NS_CHANNEL_MODEL_BY_ID);
        return updated;
    }

    public void delete(Long channelId) {
        channelCrud.delete(channelId, modelLoader, apiKeyCleanup, circuitBreakerCleanup);
        invalidate(LocalCacheService.NS_CHANNEL_BY_ID, String.valueOf(channelId));
        invalidateAll(LocalCacheService.NS_CHANNEL_MODEL_BY_ID);
    }

    // ==================== 模型加载 ====================

    /**
     * 加载模型列表（使用渠道的第一个启用 API Key）
     */
    public List<ChannelModel> loadModels(Channel channel) {
        List<ChannelModel> models = modelLoader.loadModelsForChannel(channel);
        invalidateAll(LocalCacheService.NS_CHANNEL_MODEL_BY_ID);
        return models;
    }

    /**
     * 预览获取模型列表（不保存到数据库），用于表单页 AJAX
     */
    public List<ChannelModel> previewFetchModels(String baseUrl, String apiKey, String channelType) {
        return modelLoader.previewFetchModels(baseUrl, apiKey, channelType);
    }

    /**
     * 使用手动输入的模型列表创建渠道（适用于不提供模型列表 API 的渠道）
     */
    public Channel createWithManualModels(Channel channel, String manualModelsJson) {
        Channel created = modelLoader.createWithManualModels(channel, manualModelsJson);
        invalidateAll(LocalCacheService.NS_CHANNEL_MODEL_BY_ID);
        return created;
    }

    // ==================== 模型查询 ====================

    /**
     * 获取渠道下的所有启用模型
     */
    public List<ChannelModel> getChannelModels(Long channelId) {
        return modelQuery.getChannelModels(channelId);
    }

    /**
     * 获取渠道下的所有模型（包括禁用的），用于编辑回显
     */
    public List<ChannelModel> getChannelModelsAll(Long channelId) {
        return modelQuery.getChannelModelsAll(channelId);
    }

    /**
     * 删除单个渠道模型
     */
    public void deleteChannelModel(Long modelId) {
        modelQuery.deleteChannelModel(modelId);
        invalidate(LocalCacheService.NS_CHANNEL_MODEL_BY_ID, String.valueOf(modelId));
    }

    /**
     * 删除渠道下的所有模型
     */
    public int deleteAllChannelModels(Long channelId) {
        int deleted = modelQuery.deleteAllChannelModels(channelId);
        invalidateAll(LocalCacheService.NS_CHANNEL_MODEL_BY_ID);
        return deleted;
    }

    /**
     * 重新加载指定渠道的模型
     */
    public List<ChannelModel> reloadModels(Long channelId) {
        List<ChannelModel> models = modelQuery.reloadModels(channelId, modelLoader, channelCrud.getChannelMapper());
        invalidateAll(LocalCacheService.NS_CHANNEL_MODEL_BY_ID);
        return models;
    }

    /**
     * 手动添加模型（编辑模式 AJAX）
     */
    public ChannelModel addManualModel(Long channelId, String modelName, String displayName) {
        ChannelModel cm = modelLoader.addManualModel(channelId, modelName, displayName);
        invalidate(LocalCacheService.NS_CHANNEL_MODEL_BY_ID, String.valueOf(cm.getId()));
        return cm;
    }

    // ==================== 其他 ====================

    /**
     * 获取所有启用的渠道
     */
    public List<Channel> listEnabled() {
        return channelCrud.listEnabled();
    }

    /**
     * 获取启用且开启模型自动刷新的渠道（供定时刷新任务使用）
     */
    public List<Channel> listAutoRefreshChannels() {
        return channelCrud.listAutoRefreshChannels();
    }
}
