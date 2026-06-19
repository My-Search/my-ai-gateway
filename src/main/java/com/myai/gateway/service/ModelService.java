package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.*;
import com.myai.gateway.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 模型服务
 * 管理自定义模型的增删改查，以及关联渠道模型和熔断配置
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    private final ModelMapper modelMapper;
    private final ModelChannelRelMapper relMapper;
    private final ChannelModelMapper channelModelMapper;
    private final ChannelMapper channelMapper;
    private final CircuitBreakerConfigMapper circuitBreakerConfigMapper;
    private final ChannelService channelService;

    public ModelService(ModelMapper modelMapper, ModelChannelRelMapper relMapper,
                        ChannelModelMapper channelModelMapper, ChannelMapper channelMapper,
                        CircuitBreakerConfigMapper circuitBreakerConfigMapper,
                        ChannelService channelService) {
        this.modelMapper = modelMapper;
        this.relMapper = relMapper;
        this.channelModelMapper = channelModelMapper;
        this.channelMapper = channelMapper;
        this.circuitBreakerConfigMapper = circuitBreakerConfigMapper;
        this.channelService = channelService;
    }

    // ==================== 自定义模型 CRUD ====================

    public List<Model> listAll() {
        return modelMapper.selectList(
                new LambdaQueryWrapper<Model>().orderByAsc(Model::getCreatedAt));
    }

    public Model getById(Long id) {
        return modelMapper.selectById(id);
    }

    public Model getByModelName(String modelName) {
        return modelMapper.selectOne(
                new LambdaQueryWrapper<Model>().eq(Model::getModelName, modelName));
    }

    @Transactional
    public Model create(Model model) {
        modelMapper.insert(model);
        // 自动创建默认熔断配置
        createDefaultCircuitBreaker(model.getId());
        return model;
    }

    @Transactional
    public Model update(Model model) {
        modelMapper.updateById(model);
        return model;
    }

    @Transactional
    public void delete(Long id) {
        // 级联删除关联和熔断配置
        relMapper.delete(new LambdaQueryWrapper<ModelChannelRel>().eq(ModelChannelRel::getModelId, id));
        circuitBreakerConfigMapper.delete(
                new LambdaQueryWrapper<CircuitBreakerConfig>().eq(CircuitBreakerConfig::getModelId, id));
        modelMapper.deleteById(id);
    }

    // ==================== 关联渠道模型管理 ====================

    /**
     * 获取自定义模型关联的所有渠道模型（含渠道信息）
     */
    public List<ModelChannelRel> getChannelRels(Long modelId) {
        List<ModelChannelRel> rels = relMapper.selectList(
                new LambdaQueryWrapper<ModelChannelRel>()
                        .eq(ModelChannelRel::getModelId, modelId)
                        .orderByAsc(ModelChannelRel::getSortOrder)
                        .orderByAsc(ModelChannelRel::getCreatedAt));

        // 填充渠道模型和渠道名称
        for (ModelChannelRel rel : rels) {
            ChannelModel cm = channelModelMapper.selectById(rel.getChannelModelId());
            if (cm != null) {
                rel.setChannelModelName(cm.getModelName());
                Channel channel = channelMapper.selectById(cm.getChannelId());
                if (channel != null) {
                    rel.setChannelName(channel.getName());
                    rel.setChannelType(channel.getChannelType());
                    rel.setChannelId(channel.getId());
                }
            }
        }
        return rels;
    }

    /**
     * 获取所有可用的渠道模型（含渠道信息），用于关联选择
     */
    public List<ChannelModel> getAllAvailableChannelModels() {
        List<Channel> channels = channelService.listEnabled();
        List<ChannelModel> allModels = new java.util.ArrayList<>();
        for (Channel channel : channels) {
            List<ChannelModel> models = channelService.getChannelModels(channel.getId());
            for (ChannelModel cm : models) {
                cm.setChannelName(channel.getName());
                cm.setChannelType(channel.getChannelType());
                allModels.add(cm);
            }
        }
        // 按模型名排序（相同前缀天然排在一起）
        allModels.sort(java.util.Comparator.comparing(ChannelModel::getModelName));
        return allModels;
    }

    /**
     * 添加关联
     */
    @Transactional
    public ModelChannelRel addChannelRel(ModelChannelRel rel) {
        // 检查是否已存在关联
        ModelChannelRel existing = relMapper.selectOne(
                new LambdaQueryWrapper<ModelChannelRel>()
                        .eq(ModelChannelRel::getModelId, rel.getModelId())
                        .eq(ModelChannelRel::getChannelModelId, rel.getChannelModelId()));
        if (existing != null) {
            throw new RuntimeException("该渠道模型已关联到此自定义模型");
        }
        // 自动设置 sortOrder 为当前最大值+1（如果没有则从0开始）
        ModelChannelRel lastRel = relMapper.selectOne(
                new LambdaQueryWrapper<ModelChannelRel>()
                        .eq(ModelChannelRel::getModelId, rel.getModelId())
                        .orderByDesc(ModelChannelRel::getSortOrder)
                        .last("LIMIT 1"));
        rel.setSortOrder((lastRel != null && lastRel.getSortOrder() != null) 
                ? lastRel.getSortOrder() + 1 : 0);
        relMapper.insert(rel);
        return rel;
    }

    /**
     * 批量添加关联（跳过已存在的）
     */
    @Transactional
    public int batchAddChannelRels(Long modelId, List<Long> channelModelIds) {
        int added = 0;
        // 获取当前最大的 sortOrder
        ModelChannelRel lastRel = relMapper.selectOne(
                new LambdaQueryWrapper<ModelChannelRel>()
                        .eq(ModelChannelRel::getModelId, modelId)
                        .orderByDesc(ModelChannelRel::getSortOrder)
                        .last("LIMIT 1"));
        int nextSortOrder = (lastRel != null && lastRel.getSortOrder() != null) 
                ? lastRel.getSortOrder() + 1 : 0;
        
        for (Long channelModelId : channelModelIds) {
            ModelChannelRel existing = relMapper.selectOne(
                    new LambdaQueryWrapper<ModelChannelRel>()
                            .eq(ModelChannelRel::getModelId, modelId)
                            .eq(ModelChannelRel::getChannelModelId, channelModelId));
            if (existing != null) {
                continue; // 跳过已存在的关联
            }
            ModelChannelRel newRel = new ModelChannelRel(modelId, channelModelId);
            newRel.setSortOrder(nextSortOrder++);
            relMapper.insert(newRel);
            added++;
        }
        if (added > 0) {
            log.info("批量关联模型 {}: 新增 {} 个关联", modelId, added);
        }
        return added;
    }

    /**
     * 删除关联
     */
    @Transactional
    public void removeChannelRel(Long relId) {
        relMapper.deleteById(relId);
    }

    /**
     * 更新关联的排序顺序
     */
    @Transactional
    public void updateChannelRelSortOrder(Long relId, Integer newSortOrder) {
        ModelChannelRel rel = relMapper.selectById(relId);
        if (rel == null) {
            throw new RuntimeException("关联不存在");
        }
        rel.setSortOrder(newSortOrder);
        relMapper.updateById(rel);
    }

    /**
     * 批量更新多个关联的排序顺序
     * @param sortedRelIds 按期望顺序排列的关联 ID 列表
     */
    @Transactional
    public void updateChannelRelSortOrders(List<Long> sortedRelIds) {
        for (int i = 0; i < sortedRelIds.size(); i++) {
            ModelChannelRel rel = relMapper.selectById(sortedRelIds.get(i));
            if (rel != null) {
                rel.setSortOrder(i);
                relMapper.updateById(rel);
            }
        }
    }

    // ==================== 熔断配置 ====================

    public CircuitBreakerConfig getCircuitBreakerConfig(Long modelId) {
        CircuitBreakerConfig config = circuitBreakerConfigMapper.selectOne(
                new LambdaQueryWrapper<CircuitBreakerConfig>()
                        .eq(CircuitBreakerConfig::getModelId, modelId));
        if (config == null) {
            config = createDefaultCircuitBreaker(modelId);
        }
        // 填充模型名
        Model model = modelMapper.selectById(modelId);
        if (model != null) {
            config.setModelName(model.getModelName());
        }
        return config;
    }

    @Transactional
    public CircuitBreakerConfig updateCircuitBreakerConfig(CircuitBreakerConfig config) {
        CircuitBreakerConfig existing = circuitBreakerConfigMapper.selectOne(
                new LambdaQueryWrapper<CircuitBreakerConfig>()
                        .eq(CircuitBreakerConfig::getModelId, config.getModelId()));
        if (existing != null) {
            config.setId(existing.getId());
            circuitBreakerConfigMapper.updateById(config);
        } else {
            circuitBreakerConfigMapper.insert(config);
        }
        return config;
    }

    private CircuitBreakerConfig createDefaultCircuitBreaker(Long modelId) {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setModelId(modelId);
        config.setRetryCount(3);
        config.setCircuitBreakDuration(60);
        config.setCircuitBreakScope("model");
        config.setEnabled(1);
        circuitBreakerConfigMapper.insert(config);
        return config;
    }

    // ==================== 查询方法 ====================

    /**
     * 获取自定义模型关联的可用渠道模型列表（已过滤熔断状态等）
     */
    public List<ModelChannelRel> getAvailableRels(Long modelId) {
        return getChannelRels(modelId).stream()
                .filter(r -> r.getEnabled() == 1)
                .collect(Collectors.toList());
    }

    // ==================== 跨服务查询 ====================

    /**
     * 根据 ID 查询渠道模型
     */
    public ChannelModel getChannelModelById(Long channelModelId) {
        return channelModelMapper.selectById(channelModelId);
    }

    /**
     * 根据 ID 查询渠道
     */
    public Channel getChannelById(Long channelId) {
        return channelMapper.selectById(channelId);
    }

    // ==================== 轮询 LRU 支持 ====================

    /**
     * 更新渠道模型的最后使用时间（用于轮询 LRU 排序）
     *
     * @param channelModelId 渠道模型 ID
     */
    @Transactional
    public void updateChannelModelLastUsed(Long channelModelId) {
        if (channelModelId == null) {
            return;
        }
        ChannelModel cm = channelModelMapper.selectById(channelModelId);
        if (cm != null) {
            cm.setLastUsedAt(LocalDateTime.now());
            channelModelMapper.updateById(cm);
            log.debug("渠道模型 {} 最后使用时间已更新", channelModelId);
        }
    }
}
