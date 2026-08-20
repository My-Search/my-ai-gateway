package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelApiKeyMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import com.myai.gateway.mapper.ModelChannelRelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渠道模型加载 - 负责从AI提供商获取和加载模型列表
 */
@Component
public class ChannelModelLoader {

    private static final Logger log = LoggerFactory.getLogger(ChannelModelLoader.class);

    private final ChannelMapper channelMapper;
    private final ChannelModelMapper channelModelMapper;
    private final ModelChannelRelMapper modelChannelRelMapper;
    private final ChannelApiKeyMapper channelApiKeyMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final MultiModalRuleService multiModalRuleService;

    public ChannelModelLoader(ChannelMapper channelMapper, ChannelModelMapper channelModelMapper,
                               ModelChannelRelMapper modelChannelRelMapper,
                               ChannelApiKeyMapper channelApiKeyMapper,
                               ObjectMapper objectMapper,
                               MultiModalRuleService multiModalRuleService) {
        this.channelMapper = channelMapper;
        this.channelModelMapper = channelModelMapper;
        this.modelChannelRelMapper = modelChannelRelMapper;
        this.channelApiKeyMapper = channelApiKeyMapper;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.multiModalRuleService = multiModalRuleService;
    }

    public ModelChannelRelMapper getModelChannelRelMapper() {
        return modelChannelRelMapper;
    }

    public ChannelMapper getChannelMapper() {
        return channelMapper;
    }

    /**
     * 加载模型列表（使用渠道的第一个启用 API Key）
     */
    @Transactional
    public List<ChannelModel> loadModelsForChannel(Channel channel) {
        String apiKey = null;
        try {
            var apiKeys = channelApiKeyMapper.selectEnabledByChannelId(channel.getId());
            if (apiKeys != null && !apiKeys.isEmpty()) {
                apiKey = apiKeys.get(0).getApiKey();
            }
        } catch (Exception e) {
            log.warn("获取渠道 API Key 失败: {}", e.getMessage());
        }
        return loadModels(channel, apiKey);
    }

    /**
     * 加载模型列表
     */
    @Transactional
    public List<ChannelModel> loadModels(Channel channel, String apiKey) {
        // 先删除该渠道下所有 source='api' 的模型
        channelModelMapper.delete(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channel.getId())
                        .eq(ChannelModel::getSource, "api"));

        List<ChannelModel> newModels;
        if (apiKey != null && !apiKey.isEmpty()) {
            Channel tempChannel = new Channel();
            tempChannel.setName(channel.getName());
            tempChannel.setChannelType(channel.getChannelType());
            tempChannel.setBaseUrl(channel.getBaseUrl());
            tempChannel.setApiKey(apiKey);
            newModels = fetchModelsFromProvider(tempChannel);
        } else {
            newModels = fetchModelsFromProvider(channel);
        }

        // 如果 API 调用失败，使用预设模型
        if (newModels.isEmpty()) {
            newModels = getDefaultModels(channel);
        }

        // 获取当前手动添加的模型名称
        List<ChannelModel> existingManualModels = channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channel.getId()));
        Set<String> existingManualModelNameSet = existingManualModels.stream()
                .map(ChannelModel::getModelName)
                .collect(Collectors.toSet());

        // 插入新获取的模型，跳过与手动添加模型重名的
        int addedCount = 0;
        for (ChannelModel model : newModels) {
            if (!existingManualModelNameSet.contains(model.getModelName())) {
                model.setChannelId(channel.getId());
                if (model.getSource() == null) {
                    model.setSource("api");
                }
                applyRulesToModel(model);
                channelModelMapper.insert(model);
                addedCount++;
            }
        }

        log.info("渠道 {} 重新加载了 {} 个模型（保留 {} 个手动模型）",
                channel.getName(), addedCount, existingManualModels.size());

        return channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>().eq(ChannelModel::getChannelId, channel.getId()));
    }

    /**
     * 预览获取模型列表（不保存到数据库）
     */
    public List<ChannelModel> previewFetchModels(String baseUrl, String apiKey, String channelType) {
        Channel channel = new Channel();
        channel.setName("preview");
        channel.setChannelType(channelType);
        channel.setApiKey(apiKey);
        channel.setBaseUrl(baseUrl);
        return fetchModelsFromProvider(channel);
    }

    /**
     * 构建模型的 API URL
     */
    public String buildModelsUrl(Channel channel) {
        String baseUrl = channel.getBaseUrl();
        if (baseUrl != null) {
            baseUrl = baseUrl.trim();
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = switch (channel.getChannelType()) {
                case "anthropic" -> "https://api.anthropic.com/v1";
                default -> "https://api.openai.com/v1";
            };
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/models";
    }

    /**
     * 从 AI 提供商获取模型列表
     */
    private List<ChannelModel> fetchModelsFromProvider(Channel channel) {
        List<ChannelModel> models = new ArrayList<>();
        try {
            String modelsUrl = buildModelsUrl(channel);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .timeout(Duration.ofSeconds(30));

            if ("anthropic".equals(channel.getChannelType())) {
                requestBuilder.header("x-api-key", channel.getApiKey());
                requestBuilder.header("anthropic-version", "2023-06-01");
            } else {
                requestBuilder.header("Authorization", "Bearer " + channel.getApiKey());
            }

            HttpRequest request = requestBuilder.GET().build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode dataArray = root.get("data");
                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode node : dataArray) {
                        String modelId = node.get("id").asText();
                        ChannelModel cm = new ChannelModel(null, modelId, modelId);
                        cm.setSource("api");
                        models.add(cm);
                    }
                }
            } else {
                log.warn("获取模型列表失败: {} {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("获取渠道 {} 模型列表失败: {}", channel.getName(), e.getMessage());
        }
        return models;
    }

    /**
     * 使用手动输入的模型列表创建渠道
     */
    @Transactional
    public Channel createWithManualModels(Channel channel, String manualModelsJson) {
        channelMapper.insert(channel);
        // 修复 SQLite 下 MyBatis Plus 无法正确获取自增 ID 的问题
        if (channel.getId() == null) {
            Long generatedId = channelMapper.getLastInsertId();
            channel.setId(generatedId);
        }
        try {
            List<Map<String, String>> models = objectMapper.readValue(manualModelsJson,
                    new TypeReference<List<Map<String, String>>>() {});
            for (Map<String, String> m : models) {
                String modelName = m.get("modelName");
                String displayName = m.getOrDefault("displayName", modelName);
                if (modelName != null && !modelName.isEmpty()) {
                    ChannelModel cm = new ChannelModel(channel.getId(), modelName, displayName);
                    cm.setSource("manual");
                    applyRulesToModel(cm);
                    channelModelMapper.insert(cm);
                }
            }
            log.info("渠道 {} 手动添加了 {} 个模型", channel.getName(), models.size());
        } catch (Exception e) {
            log.warn("解析手动输入的模型列表失败: {}", e.getMessage());
        }
        return channel;
    }

    /**
     * 获取预设的默认模型列表
     */
    public List<ChannelModel> getDefaultModels(Channel channel) {
        List<ChannelModel> models = new ArrayList<>();
        switch (channel.getChannelType()) {
            case "openai" -> {
                models.add(new ChannelModel(channel.getId(), "gpt-4o", "GPT-4o"));
                models.add(new ChannelModel(channel.getId(), "gpt-4o-mini", "GPT-4o Mini"));
                models.add(new ChannelModel(channel.getId(), "gpt-4-turbo", "GPT-4 Turbo"));
                models.add(new ChannelModel(channel.getId(), "gpt-4", "GPT-4"));
                models.add(new ChannelModel(channel.getId(), "gpt-3.5-turbo", "GPT-3.5 Turbo"));
                models.add(new ChannelModel(channel.getId(), "o1-preview", "O1 Preview"));
                models.add(new ChannelModel(channel.getId(), "o1-mini", "O1 Mini"));
                models.add(new ChannelModel(channel.getId(), "dall-e-3", "DALL-E 3"));
                models.add(new ChannelModel(channel.getId(), "text-embedding-3-small", "Embedding 3 Small"));
                models.add(new ChannelModel(channel.getId(), "text-embedding-3-large", "Embedding 3 Large"));
            }
            case "anthropic" -> {
                models.add(new ChannelModel(channel.getId(), "claude-3-opus-20240229", "Claude 3 Opus"));
                models.add(new ChannelModel(channel.getId(), "claude-3-sonnet-20240229", "Claude 3 Sonnet"));
                models.add(new ChannelModel(channel.getId(), "claude-3-haiku-20240307", "Claude 3 Haiku"));
                models.add(new ChannelModel(channel.getId(), "claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet"));
                models.add(new ChannelModel(channel.getId(), "claude-3-5-haiku-20241022", "Claude 3.5 Haiku"));
            }
            default -> {
                models.add(new ChannelModel(channel.getId(), "default-model", "Default Model"));
            }
        }
        return models;
    }

    /**
     * 手动添加模型
     */
    @Transactional
    public ChannelModel addManualModel(Long channelId, String modelName, String displayName) {
        ChannelModel cm = new ChannelModel(channelId, modelName, displayName);
        cm.setSource("manual");
        applyRulesToModel(cm);
        channelModelMapper.insert(cm);
        return cm;
    }

    /**
     * 根据多模态规则自动匹配并设置模型的 input 字段
     */
    public void applyRulesToModel(ChannelModel model) {
        if (model == null || model.getModelName() == null) {
            return;
        }
        String input = multiModalRuleService.computeInput(model.getModelName());
        model.setInput(input);
    }
}
