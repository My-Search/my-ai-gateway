package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.config.LocalCacheService;
import com.myai.gateway.entity.CircuitBreakerConfig;
import com.myai.gateway.entity.Model;
import com.myai.gateway.mapper.CircuitBreakerConfigMapper;
import com.myai.gateway.mapper.ModelChannelRelMapper;
import com.myai.gateway.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型创建删除 - 负责模型的创建、更新、删除操作
 */
@Component
public class ModelCrud {

    private static final Logger log = LoggerFactory.getLogger(ModelCrud.class);

    private final ModelMapper modelMapper;
    private final CircuitBreakerConfigMapper circuitBreakerConfigMapper;
    private final ModelChannelRelMapper relMapper;
    private final LocalCacheService localCacheService;

    public ModelCrud(ModelMapper modelMapper, CircuitBreakerConfigMapper circuitBreakerConfigMapper,
                     ModelChannelRelMapper relMapper, LocalCacheService localCacheService) {
        this.modelMapper = modelMapper;
        this.circuitBreakerConfigMapper = circuitBreakerConfigMapper;
        this.relMapper = relMapper;
        this.localCacheService = localCacheService;
    }

    @Transactional
    public Model create(Model model) {
        modelMapper.insert(model);
        circuitBreakerConfigMapper.insert(CircuitBreakerConfigManager.defaultConfig(model.getId()));
        return model;
    }

    /**
     * 直查（不经过缓存），供需要新鲜实例以修改再更新的调用方使用
     */
    public Model getById(Long id) {
        return modelMapper.selectById(id);
    }

    @Transactional
    public Model update(Model model) {
        Model old = model.getId() != null ? modelMapper.selectById(model.getId()) : null;
        modelMapper.updateById(model);
        if (model.getId() != null) {
            invalidateCache(LocalCacheService.NS_MODEL_BY_ID, String.valueOf(model.getId()));
        }
        if (old != null && old.getModelName() != null) {
            invalidateCache(LocalCacheService.NS_MODEL_BY_NAME, old.getModelName());
        }
        if (model.getModelName() != null) {
            invalidateCache(LocalCacheService.NS_MODEL_BY_NAME, model.getModelName());
        }
        return model;
    }

    @Transactional
    public void delete(Long id) {
        Model self = modelMapper.selectById(id);
        if (self != null) {
            java.util.List<Model> inheritors = modelMapper.selectList(
                    new LambdaQueryWrapper<Model>()
                            .eq(Model::getRelMode, Model.RelMode.INHERIT)
                            .eq(Model::getInheritFromModelId, id));
            if (!inheritors.isEmpty()) {
                String names = inheritors.stream()
                        .map(Model::getModelName)
                        .collect(java.util.stream.Collectors.joining("、"));
                throw new RuntimeException("模型「" + self.getModelName() + "」正被以下模型继承，无法删除：" + names);
            }
        }
        // 级联删除关联和熔断配置
        relMapper.delete(new LambdaQueryWrapper<com.myai.gateway.entity.ModelChannelRel>()
                .eq(com.myai.gateway.entity.ModelChannelRel::getModelId, id));
        circuitBreakerConfigMapper.delete(
                new LambdaQueryWrapper<CircuitBreakerConfig>()
                        .eq(CircuitBreakerConfig::getModelId, id));
        modelMapper.deleteById(id);
        invalidateCache(LocalCacheService.NS_MODEL_BY_ID, String.valueOf(id));
        if (self != null && self.getModelName() != null) {
            invalidateCache(LocalCacheService.NS_MODEL_BY_NAME, self.getModelName());
        }
    }

    private void invalidateCache(String namespace, String key) {
        if (localCacheService != null) {
            localCacheService.invalidate(namespace, key);
        }
    }
}
