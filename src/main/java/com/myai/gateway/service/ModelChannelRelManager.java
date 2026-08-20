package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.Model;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.mapper.ModelChannelRelMapper;
import com.myai.gateway.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模型关联管理 - 负责渠道关联的增删改操作
 */
@Component
public class ModelChannelRelManager {

    private static final Logger log = LoggerFactory.getLogger(ModelChannelRelManager.class);

    private final ModelChannelRelMapper relMapper;
    private final ModelMapper modelMapper;

    public ModelChannelRelManager(ModelChannelRelMapper relMapper, ModelMapper modelMapper) {
        this.relMapper = relMapper;
        this.modelMapper = modelMapper;
    }

    public ModelChannelRel getChannelRelById(Long relId) {
        return relMapper.selectById(relId);
    }

    public ModelChannelRel getExistingRel(Long modelId, Long channelModelId) {
        return relMapper.selectOne(
                new LambdaQueryWrapper<ModelChannelRel>()
                        .eq(ModelChannelRel::getModelId, modelId)
                        .eq(ModelChannelRel::getChannelModelId, channelModelId));
    }

    public int getNextSortOrder(Long modelId) {
        ModelChannelRel lastRel = relMapper.selectOne(
                new LambdaQueryWrapper<ModelChannelRel>()
                        .eq(ModelChannelRel::getModelId, modelId)
                        .orderByDesc(ModelChannelRel::getSortOrder)
                        .last("LIMIT 1"));
        return (lastRel != null && lastRel.getSortOrder() != null)
                ? lastRel.getSortOrder() + 1 : 0;
    }

    @Transactional
    public void insertRel(ModelChannelRel rel) {
        relMapper.insert(rel);
    }

    @Transactional
    public void removeChannelRel(Long relId) {
        ModelChannelRel rel = relMapper.selectById(relId);
        if (rel == null) {
            throw new RuntimeException("关联不存在");
        }
        assertSelfAddMode(rel.getModelId());
        relMapper.deleteById(relId);
    }

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

    @Transactional
    public void updateChannelRelSortOrders(List<Long> sortedRelIds) {
        if (sortedRelIds == null || sortedRelIds.isEmpty()) return;
        for (int i = 0; i < sortedRelIds.size(); i++) {
            ModelChannelRel rel = relMapper.selectById(sortedRelIds.get(i));
            if (rel == null) {
                throw new RuntimeException("关联不存在: id=" + sortedRelIds.get(i));
            }
            if (i == 0) {
                assertSelfAddMode(rel.getModelId());
            }
            rel.setSortOrder(i);
            relMapper.updateById(rel);
        }
    }

    /**
     * 删除指向指定渠道模型的关联记录（渠道模型被删除时同步清理，避免悬空关联）
     */
    @Transactional
    public int deleteRelsByChannelModelIds(List<Long> channelModelIds) {
        if (channelModelIds == null || channelModelIds.isEmpty()) {
            return 0;
        }
        return relMapper.delete(
                new LambdaQueryWrapper<ModelChannelRel>()
                        .in(ModelChannelRel::getChannelModelId, channelModelIds));
    }

    @Transactional
    public int batchAddChannelRels(Long modelId, List<Long> channelModelIds) {
        // 检查模式
        Model model = modelMapper.selectById(modelId);
        if (model != null && Model.RelMode.INHERIT.equals(model.getRelMode())) {
            throw new RuntimeException("模型「" + model.getModelName() + "」当前为继承模式，无法修改关联");
        }

        int added = 0;
        int nextSortOrder = getNextSortOrder(modelId);

        for (Long channelModelId : channelModelIds) {
            ModelChannelRel existing = getExistingRel(modelId, channelModelId);
            if (existing != null) {
                continue;
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

    public void assertSelfAddMode(Long modelId) {
        if (modelId == null) return;
        Model m = modelMapper.selectById(modelId);
        if (m != null && Model.RelMode.INHERIT.equals(m.getRelMode())) {
            throw new RuntimeException("模型「" + m.getModelName() + "」当前为继承模式，无法修改关联");
        }
    }
}
