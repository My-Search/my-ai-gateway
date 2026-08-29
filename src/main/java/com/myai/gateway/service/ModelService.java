package com.myai.gateway.service;

import com.myai.gateway.config.LocalCacheService;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.CircuitBreakerConfig;
import com.myai.gateway.entity.Model;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.mapper.ChannelModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 模型服务门面
 * 管理自定义模型的增删改查，以及关联渠道模型和熔断配置
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    private final ModelQuery modelQuery;
    private final ModelCrud modelCrud;
    private final ModelCacheQuery cacheQuery;
    private final ModelInheritanceResolver inheritanceResolver;
    private final ModelChannelRelManager relManager;
    private final CircuitBreakerConfigManager circuitBreakerConfigManager;
    private final ChannelService channelService;
    private final ChannelModelMapper channelModelMapper;

    /**
     * 热点查询缓存（可选能力）
     */
    private LocalCacheService localCacheService;

    public ModelService(ModelQuery modelQuery,
                        ModelCrud modelCrud,
                        ModelCacheQuery cacheQuery,
                        ModelInheritanceResolver inheritanceResolver,
                        ModelChannelRelManager relManager,
                        CircuitBreakerConfigManager circuitBreakerConfigManager,
                        ChannelService channelService,
                        ChannelModelMapper channelModelMapper) {
        this.modelQuery = modelQuery;
        this.modelCrud = modelCrud;
        this.cacheQuery = cacheQuery;
        this.inheritanceResolver = inheritanceResolver;
        this.relManager = relManager;
        this.circuitBreakerConfigManager = circuitBreakerConfigManager;
        this.channelService = channelService;
        this.channelModelMapper = channelModelMapper;
    }

    /**
     * 注入本地缓存服务（可选能力）
     */
    @Autowired(required = false)
    public void setLocalCacheService(LocalCacheService localCacheService) {
        this.localCacheService = localCacheService;
    }

    // ==================== 自定义模型 CRUD ====================

    public List<Model> listAll() {
        return modelQuery.listAll();
    }

    public List<Model> listVisible() {
        return modelQuery.listVisible();
    }

    public Model getById(Long id) {
        return cacheQuery.getModelById(id);
    }

    public Model getByModelName(String modelName) {
        return cacheQuery.getByModelName(modelName);
    }

    public List<Model> listInheritableModels(Long excludeModelId) {
        return modelQuery.listInheritableModels(excludeModelId);
    }

    public Model create(Model model) {
        return modelCrud.create(model);
    }

    public Model update(Model model) {
        return modelCrud.update(model);
    }

    public void delete(Long id) {
        modelCrud.delete(id);
    }

    // ==================== 关联渠道模型管理 ====================

    public List<ModelChannelRel> getChannelRels(Long modelId) {
        return inheritanceResolver.getChannelRels(modelId);
    }

    @Transactional
    public Model setRelMode(Long modelId, String newMode, Long sourceModelId) {
        // 获取当前模式（直查新实例，避免修改共享缓存引用）
        Model model = modelCrud.getById(modelId);
        if (model == null) {
            throw new RuntimeException("模型不存在");
        }
        String currentMode = model.getRelMode() == null ? Model.RelMode.SELF_ADD : model.getRelMode();

        if (Model.RelMode.INHERIT.equals(newMode)) {
            if (sourceModelId == null) {
                throw new RuntimeException("切换到继承模式时必须指定源模型");
            }
            if (sourceModelId.equals(modelId)) {
                throw new RuntimeException("不能将模型继承自自身");
            }
            Model source = cacheQuery.getModelById(sourceModelId);
            if (source == null) {
                throw new RuntimeException("源模型不存在");
            }
            // 检测切换到 inherit 后是否会产生环
            java.util.Set<Long> visited = new java.util.HashSet<>();
            visited.add(modelId);
            if (inheritanceResolver.wouldCreateCycle(sourceModelId, visited)) {
                throw new RuntimeException("指定的源模型会形成循环继承");
            }
            // self_add → inherit：保留自有 rels
            model.setRelMode(Model.RelMode.INHERIT);
            model.setInheritFromModelId(sourceModelId);
        } else if (Model.RelMode.SELF_ADD.equals(newMode)) {
            // inherit → self_add：恢复为之前保留的自有 rels
            model.setRelMode(Model.RelMode.SELF_ADD);
            model.setInheritFromModelId(null);
        } else {
            throw new RuntimeException("未知的关联模式: " + newMode);
        }
        model.setUpdatedAt(LocalDateTime.now());
        modelCrud.update(model);
        return model;
    }

    public List<ChannelModel> getAllAvailableChannelModels() {
        List<com.myai.gateway.entity.Channel> channels = channelService.listEnabled();
        List<ChannelModel> allModels = new java.util.ArrayList<>();
        for (com.myai.gateway.entity.Channel channel : channels) {
            List<ChannelModel> models = channelService.getChannelModels(channel.getId());
            for (ChannelModel cm : models) {
                cm.setChannelName(channel.getName());
                cm.setChannelType(channel.getChannelType());
                allModels.add(cm);
            }
        }
        allModels.sort(java.util.Comparator.comparing(ChannelModel::getModelName));
        return allModels;
    }

    public ModelChannelRel addChannelRel(ModelChannelRel rel) {
        relManager.assertSelfAddMode(rel.getModelId());
        // 检查是否已存在关联
        com.myai.gateway.entity.ModelChannelRel existing = relManager.getExistingRel(
                rel.getModelId(), rel.getChannelModelId());
        if (existing != null) {
            throw new RuntimeException("该渠道模型已关联到此自定义模型");
        }
        // 自动设置 sortOrder
        int nextSortOrder = relManager.getNextSortOrder(rel.getModelId());
        rel.setSortOrder(nextSortOrder);
        relManager.insertRel(rel);
        return rel;
    }

    public int batchAddChannelRels(Long modelId, List<Long> channelModelIds) {
        return relManager.batchAddChannelRels(modelId, channelModelIds);
    }

    public ModelChannelRel getChannelRelById(Long relId) {
        return relManager.getChannelRelById(relId);
    }

    public void removeChannelRel(Long relId) {
        relManager.removeChannelRel(relId);
    }

    public int removeChannelRels(List<Long> relIds) {
        return relManager.removeChannelRels(relIds);
    }

    public void updateChannelRelReasoningEffort(Long relId, String reasoningEffort) {
        relManager.updateChannelRelReasoningEffort(relId, reasoningEffort);
    }

    public void updateChannelRelSortOrder(Long relId, Integer newSortOrder) {
        relManager.updateChannelRelSortOrder(relId, newSortOrder);
    }

    public void updateChannelRelSortOrders(List<Long> sortedRelIds) {
        relManager.updateChannelRelSortOrders(sortedRelIds);
    }

    // ==================== 熔断配置 ====================

    public CircuitBreakerConfig getCircuitBreakerConfig(Long modelId) {
        return circuitBreakerConfigManager.getCircuitBreakerConfig(modelId);
    }

    public CircuitBreakerConfig updateCircuitBreakerConfig(CircuitBreakerConfig config) {
        return circuitBreakerConfigManager.updateCircuitBreakerConfig(config);
    }

    public int getCircuitBreakDurationByChannelModelId(Long channelModelId) {
        return circuitBreakerConfigManager.getCircuitBreakDurationByChannelModelId(channelModelId);
    }

    // ==================== 查询方法 ====================

    public List<ModelChannelRel> getAvailableRels(Long modelId) {
        return getChannelRels(modelId).stream()
                .filter(r -> r.getEnabled() == 1)
                .collect(java.util.stream.Collectors.toList());
    }

    public ChannelModel getChannelModelById(Long channelModelId) {
        if (channelModelId == null) {
            return null;
        }
        if (localCacheService != null) {
            return localCacheService.get(LocalCacheService.NS_CHANNEL_MODEL_BY_ID, String.valueOf(channelModelId),
                    () -> channelModelMapper.selectById(channelModelId));
        }
        return channelModelMapper.selectById(channelModelId);
    }

    public List<ChannelModel> getChannelModelsByIds(Collection<Long> channelModelIds) {
        if (channelModelIds == null || channelModelIds.isEmpty()) {
            return List.of();
        }
        return channelModelMapper.selectBatchIds(channelModelIds);
    }

    public ChannelModel getFirstEnabledChannelModelByChannelId(Long channelId) {
        if (channelId == null) {
            return null;
        }
        return channelModelMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channelId)
                        .eq(ChannelModel::getEnabled, 1)
                        .orderByAsc(ChannelModel::getId)
                        .last("LIMIT 1"));
    }

    public com.myai.gateway.entity.Channel getChannelById(Long channelId) {
        return cacheQuery.getChannelById(channelId);
    }

    // ==================== LRU 支持 ====================

    public void updateChannelModelLastUsed(Long channelModelId) {
        if (channelModelId == null) {
            return;
        }
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChannelModel> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChannelModel>()
                            .eq(ChannelModel::getId, channelModelId)
                            .set(ChannelModel::getLastUsedAt, LocalDateTime.now());
            channelModelMapper.update(null, wrapper);
            log.debug("渠道模型 {} 最后使用时间已更新", channelModelId);
        } catch (Exception e) {
            log.warn("更新渠道模型 {} 最后使用时间失败: {}", channelModelId, e.getMessage());
        }
    }
}
