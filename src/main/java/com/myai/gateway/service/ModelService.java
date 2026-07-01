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

    /**
     * 列出对外可见的模型列表（hidden=0 且 enabled=1）。
     * 用于对外 API（/v1/models）和分享接口，隐藏模型仅在管理后台可见。
     */
    public List<Model> listVisible() {
        return modelMapper.selectList(
                new LambdaQueryWrapper<Model>()
                        .eq(Model::getHidden, 0)
                        .eq(Model::getEnabled, 1)
                        .orderByAsc(Model::getCreatedAt));
    }

    public Model getById(Long id) {
        return modelMapper.selectById(id);
    }

    public Model getByModelName(String modelName) {
        return modelMapper.selectOne(
                new LambdaQueryWrapper<Model>().eq(Model::getModelName, modelName));
    }

    /**
     * 列出可作为继承源的入口模型（不含自身）。
     * 规则：仅启用 (enabled=1) 的模型；过滤后会由调用方负责环检测。
     */
    public List<Model> listInheritableModels(Long excludeModelId) {
        return modelMapper.selectList(
                new LambdaQueryWrapper<Model>()
                        .eq(Model::getEnabled, 1)
                        .ne(excludeModelId != null, Model::getId, excludeModelId)
                        .orderByAsc(Model::getModelName));
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
        // 阻止删除：若有其他模型正在继承本模型
        Model self = modelMapper.selectById(id);
        if (self != null) {
            List<Model> inheritors = modelMapper.selectList(
                    new LambdaQueryWrapper<Model>()
                            .eq(Model::getRelMode, Model.RelMode.INHERIT)
                            .eq(Model::getInheritFromModelId, id));
            if (!inheritors.isEmpty()) {
                String names = inheritors.stream()
                        .map(Model::getModelName)
                        .collect(Collectors.joining("、"));
                throw new RuntimeException("模型「" + self.getModelName() + "」正被以下模型继承，无法删除：" + names);
            }
        }
        // 级联删除关联和熔断配置
        relMapper.delete(new LambdaQueryWrapper<ModelChannelRel>().eq(ModelChannelRel::getModelId, id));
        circuitBreakerConfigMapper.delete(
                new LambdaQueryWrapper<CircuitBreakerConfig>().eq(CircuitBreakerConfig::getModelId, id));
        modelMapper.deleteById(id);
    }

    // ==================== 关联渠道模型管理 ====================

    /**
     * 获取自定义模型关联的所有渠道模型（含渠道信息）。
     * <p>
     * 主干流程：根据模型的 relMode 决定返回自有 rels 还是解析自源模型（递归解析 + 环检测）。
     * 继承模式下，关联列表是源模型的实时映射，rels 内的 id 是源模型 rel 的 id（用于排障识别）。
     * </p>
     */
    public List<ModelChannelRel> getChannelRels(Long modelId) {
        return resolveRels(modelId, new java.util.HashSet<>());
    }

    /**
     * 递归解析模型的关联列表。
     * - self_add：直接查本模型自有的 rels 并填充渠道信息
     * - inherit：递归到源模型解析（带环检测）
     */
    private List<ModelChannelRel> resolveRels(Long modelId, java.util.Set<Long> visited) {
        if (modelId == null) return java.util.Collections.emptyList();
        if (!visited.add(modelId)) {
            log.warn("检测到模型关联的循环继承，modelId={}，已访问链路={}", modelId, visited);
            return java.util.Collections.emptyList();
        }

        Model model = modelMapper.selectById(modelId);
        if (model == null) return java.util.Collections.emptyList();

        if (Model.RelMode.INHERIT.equals(model.getRelMode()) && model.getInheritFromModelId() != null) {
            return resolveRels(model.getInheritFromModelId(), visited);
        }

        // 自添加模式：直接查本模型自有的 rels
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
                rel.setInput(cm.getInput());
                Channel channel = channelMapper.selectById(cm.getChannelId());
                if (channel != null) {
                    rel.setChannelName(channel.getName());
                    rel.setChannelType(channel.getChannelType());
                    rel.setChannelId(channel.getId());
                    rel.setChannelEnabled(channel.getEnabled());
                }
            }
        }
        return rels;
    }

    /**
     * 切换模型的关联模式。
     * <p>
     * 处理流程：
     * - self_add → inherit：保留本模型自有的 rels（仅切换模式，切回时恢复），设置 inheritFromModelId
     * - inherit → self_add：恢复为之前保留的自有 rels（之前自添加的 rels 切走时未被删除）
     * - 同模式切换：仅当两端都是 inherit 且源不同时才允许切换源；同源则视为无操作
     * </p>
     *
     * @param modelId      目标模型 ID
     * @param newMode      新模式（self_add / inherit）
     * @param sourceModelId  继承源模型 ID（仅 newMode='inherit' 时必填，且不能等于 modelId）
     * @return 更新后的 Model
     */
    @Transactional
    public Model setRelMode(Long modelId, String newMode, Long sourceModelId) {
        Model model = modelMapper.selectById(modelId);
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
            Model source = modelMapper.selectById(sourceModelId);
            if (source == null) {
                throw new RuntimeException("源模型不存在");
            }
            // 检测切换到 inherit 后是否会产生环
            java.util.Set<Long> visited = new java.util.HashSet<>();
            visited.add(modelId);
            if (wouldCreateCycle(sourceModelId, visited)) {
                throw new RuntimeException("指定的源模型会形成循环继承");
            }

            // self_add → inherit：保留自有 rels（仅切换模式，切回时恢复）
            // inherit → inherit：仅更新源
            model.setRelMode(Model.RelMode.INHERIT);
            model.setInheritFromModelId(sourceModelId);
        } else if (Model.RelMode.SELF_ADD.equals(newMode)) {
            // inherit → self_add：恢复为之前保留的自有 rels（之前自添加的 rels 未被删除）
            model.setRelMode(Model.RelMode.SELF_ADD);
            model.setInheritFromModelId(null);
        } else {
            throw new RuntimeException("未知的关联模式: " + newMode);
        }
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(model);
        return model;
    }

    /**
     * 检测从 startModelId 出发解析继承时是否会形成环。
     * visited 集合中应已包含发起继承的 modelId。
     */
    private boolean wouldCreateCycle(Long startModelId, java.util.Set<Long> visited) {
        Model m = modelMapper.selectById(startModelId);
        if (m == null) return false;
        if (!Model.RelMode.INHERIT.equals(m.getRelMode()) || m.getInheritFromModelId() == null) {
            return false;
        }
        Long next = m.getInheritFromModelId();
        if (visited.contains(next)) return true;
        visited.add(next);
        return wouldCreateCycle(next, visited);
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
        assertSelfAddMode(rel.getModelId());
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
        assertSelfAddMode(modelId);
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
        ModelChannelRel rel = relMapper.selectById(relId);
        if (rel == null) {
            throw new RuntimeException("关联不存在");
        }
        assertSelfAddMode(rel.getModelId());
        relMapper.deleteById(relId);
    }

    /**
     * 更新关联的默认思考强度（reasoning_effort）
     *
     * @param relId 关联 ID
     * @param reasoningEffort 思考强度值（null 表示清除默认值）
     */
    @Transactional
    public void updateChannelRelReasoningEffort(Long relId, String reasoningEffort) {
        ModelChannelRel rel = relMapper.selectById(relId);
        if (rel == null) {
            throw new RuntimeException("关联不存在");
        }
        assertSelfAddMode(rel.getModelId());
        rel.setReasoningEffort(reasoningEffort);
        relMapper.updateById(rel);
        log.info("更新关联推理强度: relId={}, reasoningEffort={}", relId, reasoningEffort);
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
        assertSelfAddMode(rel.getModelId());
        rel.setSortOrder(newSortOrder);
        relMapper.updateById(rel);
    }

    /**
     * 批量更新多个关联的排序顺序
     * @param sortedRelIds 按期望顺序排列的关联 ID 列表
     */
    @Transactional
    public void updateChannelRelSortOrders(List<Long> sortedRelIds) {
        if (sortedRelIds == null || sortedRelIds.isEmpty()) return;
        // 先校验所有 rels 对应的模型都不是 inherit 模式
        for (int i = 0; i < sortedRelIds.size(); i++) {
            ModelChannelRel rel = relMapper.selectById(sortedRelIds.get(i));
            if (rel == null) {
                throw new RuntimeException("关联不存在: id=" + sortedRelIds.get(i));
            }
            if (i == 0) {
                // 仅校验第一个的 modelId（同一批次属于同一模型）
                assertSelfAddMode(rel.getModelId());
            }
            rel.setSortOrder(i);
            relMapper.updateById(rel);
        }
    }

    /**
     * 校验模型处于 self_add 模式，否则抛出明确错误。
     * 继承模式下不允许手动修改关联。
     */
    private void assertSelfAddMode(Long modelId) {
        if (modelId == null) return;
        Model m = modelMapper.selectById(modelId);
        if (m != null && Model.RelMode.INHERIT.equals(m.getRelMode())) {
            throw new RuntimeException("模型「" + m.getModelName() + "」当前为继承模式，无法修改关联");
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
