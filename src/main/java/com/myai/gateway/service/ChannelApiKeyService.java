package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.mapper.ChannelApiKeyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 渠道 API Key 服务
 * 管理渠道下的多个 API Key
 */
@Service
public class ChannelApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ChannelApiKeyService.class);

    private final ChannelApiKeyMapper channelApiKeyMapper;

    public ChannelApiKeyService(ChannelApiKeyMapper channelApiKeyMapper) {
        this.channelApiKeyMapper = channelApiKeyMapper;
    }

    /**
     * 获取渠道下所有启用的 API Keys
     */
    public List<ChannelApiKey> listEnabledByChannelId(Long channelId) {
        return channelApiKeyMapper.selectEnabledByChannelId(channelId);
    }

    /**
     * 获取渠道下所有 API Keys（用于编辑）
     */
    public List<ChannelApiKey> listByChannelId(Long channelId) {
        return channelApiKeyMapper.selectAllByChannelId(channelId);
    }

    /**
     * 根据 ID 获取
     */
    public ChannelApiKey getById(Long id) {
        return channelApiKeyMapper.selectById(id);
    }

    /**
     * 创建 API Key
     */
    @Transactional
    public ChannelApiKey create(ChannelApiKey apiKey) {
        channelApiKeyMapper.insert(apiKey);
        if (apiKey.getId() == null) {
            // SQLite 自增 ID 兼容处理
            Long generatedId = channelApiKeyMapper.getLastInsertId();
            apiKey.setId(generatedId);
        }
        return apiKey;
    }

    /**
     * 批量创建 API Keys
     */
    @Transactional
    public void batchCreate(Long channelId, List<ChannelApiKey> apiKeys) {
        for (ChannelApiKey apiKey : apiKeys) {
            apiKey.setChannelId(channelId);
            create(apiKey);
        }
        log.info("渠道 {} 批量创建了 {} 个 API Keys", channelId, apiKeys.size());
    }

    /**
     * 更新 API Key
     */
    @Transactional
    public ChannelApiKey update(ChannelApiKey apiKey) {
        channelApiKeyMapper.updateById(apiKey);
        return apiKey;
    }

    /**
     * 删除 API Key
     */
    @Transactional
    public void delete(Long id) {
        channelApiKeyMapper.deleteById(id);
    }

    /**
     * 删除渠道下所有 API Keys
     */
    @Transactional
    public int deleteAllByChannelId(Long channelId) {
        return channelApiKeyMapper.delete(
                new LambdaQueryWrapper<ChannelApiKey>()
                        .eq(ChannelApiKey::getChannelId, channelId));
    }

    /**
     * 获取渠道路由时可用的 API Key
     * 返回第一个启用的 API Key，或者根据策略选择
     */
    public ChannelApiKey getAvailableApiKey(Long channelId) {
        List<ChannelApiKey> enabledKeys = listEnabledByChannelId(channelId);
        if (enabledKeys != null && !enabledKeys.isEmpty()) {
            // 默认返回第一个，也可以实现轮询策略
            return enabledKeys.get(0);
        }
        return null;
    }

    /**
     * 获取渠道路由时可用的所有 API Keys
     */
    public List<ChannelApiKey> getAvailableApiKeys(Long channelId) {
        return listEnabledByChannelId(channelId);
    }

    /**
     * 同步 API Keys（编辑渠道时使用）
     * 对比页面提交的 API Key 列表和数据库中的：
     * - 删除已在页面移除的
     * - 新增页面新添加的
     * - 更新已存在的
     */
    @Transactional
    public void syncApiKeys(Long channelId, List<ChannelApiKey> submittedKeys) {
        // 1. 获取数据库中当前渠道的所有 API Keys
        List<ChannelApiKey> existingKeys = listByChannelId(channelId);

        if (submittedKeys == null || submittedKeys.isEmpty()) {
            // 没有提交数据，删除所有现有
            for (ChannelApiKey key : existingKeys) {
                channelApiKeyMapper.deleteById(key.getId());
            }
            log.info("渠道 {} API Keys 已清空", channelId);
            return;
        }

        // 2. 构建页面提交的 keyName 集合
        java.util.Set<String> submittedNames = new java.util.HashSet<>();
        java.util.Set<Long> submittedIds = new java.util.HashSet<>();
        for (ChannelApiKey key : submittedKeys) {
            if (key.getKeyName() != null && !key.getKeyName().isEmpty()) {
                submittedNames.add(key.getKeyName());
                if (key.getId() != null) {
                    submittedIds.add(key.getId());
                }
            }
        }

        // 3. 删除：在数据库中存在但页面没有提交的
        for (ChannelApiKey existing : existingKeys) {
            // 如果数据库中的不在页面提交的列表中，则删除
            if (!submittedNames.contains(existing.getKeyName())) {
                channelApiKeyMapper.deleteById(existing.getId());
            }
        }

        // 4. 新增或更新：页面有但数据库没有的
        java.util.Set<String> existingNames = new java.util.HashSet<>();
        for (ChannelApiKey existing : existingKeys) {
            existingNames.add(existing.getKeyName());
        }

        for (ChannelApiKey submitted : submittedKeys) {
            if (submitted.getKeyName() != null && !submitted.getKeyName().isEmpty()) {
                if (existingNames.contains(submitted.getKeyName())) {
                    // 已存在：更新
                    for (ChannelApiKey existing : existingKeys) {
                        if (existing.getKeyName().equals(submitted.getKeyName())) {
                            existing.setApiKey(submitted.getApiKey());
                            existing.setEnabled(submitted.getEnabled() != null ? submitted.getEnabled() : 1);
                            existing.setSortOrder(submitted.getSortOrder() != null ? submitted.getSortOrder() : 0);
                            channelApiKeyMapper.updateById(existing);
                            break;
                        }
                    }
                } else {
                    // 不存在：新增
                    submitted.setChannelId(channelId);
                    create(submitted);
                    existingNames.add(submitted.getKeyName());
                }
            }
        }

        log.info("渠道 {} API Keys 同步完成", channelId);
    }
}