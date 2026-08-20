package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import com.myai.gateway.mapper.ModelChannelRelMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 渠道模型查询 - 负责渠道模型的查询操作
 */
@Component
public class ChannelModelQuery {

    private final ChannelModelMapper channelModelMapper;
    private final ModelChannelRelMapper modelChannelRelMapper;

    public ChannelModelQuery(ChannelModelMapper channelModelMapper,
                             ModelChannelRelMapper modelChannelRelMapper) {
        this.channelModelMapper = channelModelMapper;
        this.modelChannelRelMapper = modelChannelRelMapper;
    }

    /**
     * 获取渠道下的所有启用模型
     */
    public List<ChannelModel> getChannelModels(Long channelId) {
        return channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channelId)
                        .eq(ChannelModel::getEnabled, 1));
    }

    /**
     * 获取渠道下的所有模型（包括禁用的），用于编辑回显
     */
    public List<ChannelModel> getChannelModelsAll(Long channelId) {
        return channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channelId));
    }

    /**
     * 删除单个渠道模型（同步清理其入口模型关联）
     */
    public void deleteChannelModel(Long modelId) {
        modelChannelRelMapper.delete(
                new LambdaQueryWrapper<ModelChannelRel>()
                        .eq(ModelChannelRel::getChannelModelId, modelId));
        channelModelMapper.deleteById(modelId);
    }

    /**
     * 删除渠道下的所有模型（同步清理其入口模型关联）
     */
    public int deleteAllChannelModels(Long channelId) {
        List<Long> ids = channelModelMapper.selectList(
                        new LambdaQueryWrapper<ChannelModel>()
                                .eq(ChannelModel::getChannelId, channelId))
                .stream()
                .map(ChannelModel::getId)
                .collect(Collectors.toList());
        if (!ids.isEmpty()) {
            modelChannelRelMapper.delete(
                    new LambdaQueryWrapper<ModelChannelRel>()
                            .in(ModelChannelRel::getChannelModelId, ids));
        }
        return channelModelMapper.delete(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channelId));
    }

    /**
     * 重新加载指定渠道的模型
     */
    @Transactional
    public List<ChannelModel> reloadModels(Long channelId, ChannelModelLoader modelLoader, ChannelMapper channelMapper) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null) {
            throw new RuntimeException("渠道不存在");
        }
        return modelLoader.loadModelsForChannel(channel);
    }
}
