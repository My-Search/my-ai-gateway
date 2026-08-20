package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.CircuitBreakerConfig;
import com.myai.gateway.mapper.CircuitBreakerConfigMapper;
import com.myai.gateway.mapper.ModelChannelRelMapper;
import com.myai.gateway.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 熔断配置管理 - 负责模型熔断配置的查询和更新
 */
@Component
public class CircuitBreakerConfigManager {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerConfigManager.class);
    private static final int DEFAULT_CIRCUIT_BREAK_DURATION_SECONDS = 60;

    private final CircuitBreakerConfigMapper circuitBreakerConfigMapper;
    private final ModelMapper modelMapper;
    private final ModelChannelRelMapper relMapper;

    public CircuitBreakerConfigManager(CircuitBreakerConfigMapper circuitBreakerConfigMapper,
                                       ModelMapper modelMapper,
                                       ModelChannelRelMapper relMapper) {
        this.circuitBreakerConfigMapper = circuitBreakerConfigMapper;
        this.modelMapper = modelMapper;
        this.relMapper = relMapper;
    }

    public CircuitBreakerConfig getCircuitBreakerConfig(Long modelId) {
        CircuitBreakerConfig config = circuitBreakerConfigMapper.selectOne(
                new LambdaQueryWrapper<CircuitBreakerConfig>()
                        .eq(CircuitBreakerConfig::getModelId, modelId));
        if (config == null) {
            config = defaultConfig(modelId);
            circuitBreakerConfigMapper.insert(config);
        }
        // 填充模型名
        com.myai.gateway.entity.Model model = modelMapper.selectById(modelId);
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

    /**
     * 根据渠道模型 ID 反查其所属自定义模型的熔断持续时间（秒）
     */
    public int getCircuitBreakDurationByChannelModelId(Long channelModelId) {
        if (channelModelId == null) {
            return DEFAULT_CIRCUIT_BREAK_DURATION_SECONDS;
        }
        try {
            com.myai.gateway.entity.ModelChannelRel rel = relMapper.selectOne(
                    new LambdaQueryWrapper<com.myai.gateway.entity.ModelChannelRel>()
                            .eq(com.myai.gateway.entity.ModelChannelRel::getChannelModelId, channelModelId)
                            .last("LIMIT 1"));
            if (rel != null && rel.getModelId() != null) {
                CircuitBreakerConfig config = circuitBreakerConfigMapper.selectOne(
                        new LambdaQueryWrapper<CircuitBreakerConfig>()
                                .eq(CircuitBreakerConfig::getModelId, rel.getModelId()));
                if (config != null && config.getCircuitBreakDuration() != null
                        && config.getCircuitBreakDuration() > 0) {
                    return config.getCircuitBreakDuration();
                }
            }
        } catch (Exception e) {
            log.warn("反查熔断时长失败，使用默认值: channelModelId={}, err={}", channelModelId, e.getMessage());
        }
        return DEFAULT_CIRCUIT_BREAK_DURATION_SECONDS;
    }

    /**
     * 构建默认熔断配置对象（不插入库），供模型创建与配置缺省场景共用。
     */
    public static CircuitBreakerConfig defaultConfig(Long modelId) {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        config.setModelId(modelId);
        config.setRetryCount(3);
        config.setCircuitBreakDuration(60);
        config.setCircuitBreakScope("model");
        config.setEnabled(1);
        return config;
    }
}
