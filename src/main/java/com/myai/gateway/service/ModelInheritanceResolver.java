package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.Model;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import com.myai.gateway.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型继承链解析 - 负责递归解析模型的关联列表，检测循环继承
 */
@Component
public class ModelInheritanceResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelInheritanceResolver.class);

    private final ModelMapper modelMapper;
    private final com.myai.gateway.mapper.ModelChannelRelMapper relMapper;
    private final ChannelModelMapper channelModelMapper;
    private final ChannelMapper channelMapper;

    public ModelInheritanceResolver(ModelMapper modelMapper,
                                    com.myai.gateway.mapper.ModelChannelRelMapper relMapper,
                                    ChannelModelMapper channelModelMapper,
                                    ChannelMapper channelMapper) {
        this.modelMapper = modelMapper;
        this.relMapper = relMapper;
        this.channelModelMapper = channelModelMapper;
        this.channelMapper = channelMapper;
    }

    /**
     * 获取自定义模型关联的所有渠道模型（含渠道信息）
     */
    public List<ModelChannelRel> getChannelRels(Long modelId) {
        return resolveRels(modelId, new HashSet<>());
    }

    /**
     * 递归解析模型的关联列表
     * - self_add：直接查本模型自有的 rels 并填充渠道信息
     * - inherit：递归到源模型解析（带环检测）
     */
    private List<ModelChannelRel> resolveRels(Long modelId, Set<Long> visited) {
        if (modelId == null) return List.of();
        if (!visited.add(modelId)) {
            log.warn("检测到模型关联的循环继承，modelId={}，已访问链路={}", modelId, visited);
            return List.of();
        }

        Model model = modelMapper.selectById(modelId);
        if (model == null) return List.of();

        if (Model.RelMode.INHERIT.equals(model.getRelMode()) && model.getInheritFromModelId() != null) {
            return resolveRels(model.getInheritFromModelId(), visited);
        }

        // 自添加模式：直接查本模型自有的 rels
        List<ModelChannelRel> rels = relMapper.selectList(
                new LambdaQueryWrapper<com.myai.gateway.entity.ModelChannelRel>()
                        .eq(com.myai.gateway.entity.ModelChannelRel::getModelId, modelId)
                        .orderByAsc(com.myai.gateway.entity.ModelChannelRel::getSortOrder)
                        .orderByAsc(com.myai.gateway.entity.ModelChannelRel::getCreatedAt));

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
     * 检测从 startModelId 出发解析继承时是否会形成环
     */
    public boolean wouldCreateCycle(Long startModelId, Set<Long> visited) {
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
}
