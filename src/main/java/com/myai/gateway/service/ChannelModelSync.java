package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import com.myai.gateway.mapper.ModelChannelRelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渠道模型同步 - 负责编辑模式下的模型增删改
 */
@Component
public class ChannelModelSync {

    private static final Logger log = LoggerFactory.getLogger(ChannelModelSync.class);

    private final ChannelMapper channelMapper;
    private final ChannelModelMapper channelModelMapper;
    private final ModelChannelRelMapper modelChannelRelMapper;
    private final ObjectMapper objectMapper;
    private final ChannelModelLoader modelLoader;

    public ChannelModelSync(ChannelMapper channelMapper, ChannelModelMapper channelModelMapper,
                            ModelChannelRelMapper modelChannelRelMapper,
                            ObjectMapper objectMapper, ChannelModelLoader modelLoader) {
        this.channelMapper = channelMapper;
        this.channelModelMapper = channelModelMapper;
        this.modelChannelRelMapper = modelChannelRelMapper;
        this.objectMapper = objectMapper;
        this.modelLoader = modelLoader;
    }

    /**
     * 编辑模式下更新渠道并同步模型
     */
    @Transactional
    public Channel updateWithModels(Channel channel, String modelsJson) {
        if (channel.getBaseUrl() != null) {
            channel.setBaseUrl(channel.getBaseUrl().trim());
        }
        channelMapper.updateById(channel);

        List<ChannelModel> existingModels = channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channel.getId()));

        if (modelsJson == null || modelsJson.isEmpty() || "[]".equals(modelsJson)) {
            List<Long> ids = existingModels.stream()
                    .map(ChannelModel::getId)
                    .collect(Collectors.toList());
            // 删除被移除模型的入口模型关联，避免悬空
            deleteRelsForModels(ids);
            for (ChannelModel cm : existingModels) {
                channelModelMapper.deleteById(cm.getId());
            }
            log.info("渠道 {} 模型已清空", channel.getName());
            return channel;
        }

        try {
            List<Map<String, Object>> submittedModels = objectMapper.readValue(modelsJson,
                    new TypeReference<List<Map<String, Object>>>() {});

            Set<String> submittedNames = new HashSet<>();
            for (Map<String, Object> m : submittedModels) {
                Boolean deleted = (Boolean) m.get("_deleted");
                if (deleted != null && deleted) {
                    continue;
                }
                String modelName = (String) m.get("modelName");
                if (modelName != null && !modelName.isEmpty()) {
                    submittedNames.add(modelName);
                }
            }

            int deletedCount = 0;
            List<Long> deletedIds = new ArrayList<>();
            for (ChannelModel cm : existingModels) {
                if (!submittedNames.contains(cm.getModelName())) {
                    deletedIds.add(cm.getId());
                }
            }
            // 删除被移除模型的入口模型关联，避免悬空
            deleteRelsForModels(deletedIds);
            for (Long id : deletedIds) {
                channelModelMapper.deleteById(id);
                deletedCount++;
            }

            Set<String> existingNames = existingModels.stream()
                    .map(ChannelModel::getModelName)
                    .collect(Collectors.toSet());
            Map<String, ChannelModel> existingByName = existingModels.stream()
                    .collect(Collectors.toMap(ChannelModel::getModelName, cm -> cm, (a, b) -> a));

            int addedCount = 0;
            int updatedCount = 0;
            for (Map<String, Object> m : submittedModels) {
                Boolean deleted = (Boolean) m.get("_deleted");
                if (deleted != null && deleted) {
                    continue;
                }

                String modelName = (String) m.get("modelName");
                String displayName = (String) m.getOrDefault("displayName", modelName);

                if (modelName == null || modelName.isEmpty()) continue;

                if (!existingNames.contains(modelName)) {
                    ChannelModel cm = new ChannelModel(channel.getId(), modelName, displayName);
                    modelLoader.applyRulesToModel(cm);
                    channelModelMapper.insert(cm);
                    addedCount++;
                    existingNames.add(modelName);
                } else {
                    ChannelModel cm = existingByName.get(modelName);
                    if (cm == null) {
                        cm = channelModelMapper.selectOne(
                                new LambdaQueryWrapper<ChannelModel>()
                                        .eq(ChannelModel::getChannelId, channel.getId())
                                        .eq(ChannelModel::getModelName, modelName));
                    }
                    if (cm != null) {
                        String oldInput = cm.getInput();
                        modelLoader.applyRulesToModel(cm);
                        if ((oldInput == null && cm.getInput() != null)
                                || (oldInput != null && !oldInput.equals(cm.getInput()))) {
                            channelModelMapper.updateById(cm);
                            updatedCount++;
                            log.debug("已有模型重新应用规则: modelId={}, modelName={}, input: {} -> {}",
                                    cm.getId(), modelName, oldInput, cm.getInput());
                        }
                    }
                }
            }

            log.info("渠道 {} 模型同步完成：新增 {} 个，更新 {} 个，删除 {} 个",
                    channel.getName(), addedCount, updatedCount, deletedCount);

        } catch (Exception e) {
            log.warn("同步模型列表失败: {}", e.getMessage());
        }

        return channel;
    }

    /**
     * 删除指向指定渠道模型的入口模型关联记录（渠道模型被移除时同步清理，避免悬空）
     */
    private void deleteRelsForModels(List<Long> channelModelIds) {
        if (channelModelIds == null || channelModelIds.isEmpty()) {
            return;
        }
        modelChannelRelMapper.delete(
                new LambdaQueryWrapper<com.myai.gateway.entity.ModelChannelRel>()
                        .in(com.myai.gateway.entity.ModelChannelRel::getChannelModelId, channelModelIds));
    }
}
