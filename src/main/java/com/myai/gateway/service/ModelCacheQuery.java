package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.config.LocalCacheService;
import com.myai.gateway.entity.Model;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ModelMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型缓存查询 - 负责跨服务查询，带可选缓存
 */
@Component
public class ModelCacheQuery {

    private final ModelMapper modelMapper;
    private final ChannelMapper channelMapper;
    private final LocalCacheService localCacheService;

    public ModelCacheQuery(ModelMapper modelMapper, ChannelMapper channelMapper,
                           LocalCacheService localCacheService) {
        this.modelMapper = modelMapper;
        this.channelMapper = channelMapper;
        this.localCacheService = localCacheService;
    }

    public Model getModelById(Long id) {
        if (id == null) return null;
        if (localCacheService != null) {
            return localCacheService.get(LocalCacheService.NS_MODEL_BY_ID, String.valueOf(id),
                    () -> modelMapper.selectById(id));
        }
        return modelMapper.selectById(id);
    }

    public Model getByModelName(String modelName) {
        if (modelName == null) return null;
        if (localCacheService != null) {
            return localCacheService.get(LocalCacheService.NS_MODEL_BY_NAME, modelName,
                    () -> modelMapper.selectOne(
                            new LambdaQueryWrapper<Model>().eq(Model::getModelName, modelName)));
        }
        return modelMapper.selectOne(
                new LambdaQueryWrapper<Model>().eq(Model::getModelName, modelName));
    }

    public Channel getChannelById(Long channelId) {
        if (channelId == null) return null;
        if (localCacheService != null) {
            return localCacheService.get(LocalCacheService.NS_CHANNEL_BY_ID, String.valueOf(channelId),
                    () -> channelMapper.selectById(channelId));
        }
        return channelMapper.selectById(channelId);
    }
}
