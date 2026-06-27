package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渠道服务
 * 管理渠道的增删改查，以及加载渠道下的模型列表
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final ChannelMapper channelMapper;
    private final ChannelModelMapper channelModelMapper;
    private final ChannelApiKeyService channelApiKeyService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final MultiModalRuleService multiModalRuleService;

    public ChannelService(ChannelMapper channelMapper, ChannelModelMapper channelModelMapper, 
                          ChannelApiKeyService channelApiKeyService, ObjectMapper objectMapper,
                          MultiModalRuleService multiModalRuleService) {
        this.channelMapper = channelMapper;
        this.channelModelMapper = channelModelMapper;
        this.channelApiKeyService = channelApiKeyService;
        this.objectMapper = objectMapper;
        this.multiModalRuleService = multiModalRuleService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ==================== 渠道 CRUD ====================

    public List<Channel> listAll() {
        return channelMapper.selectList(
                new LambdaQueryWrapper<Channel>().orderByAsc(Channel::getCreatedAt));
    }

    public Channel getById(Long id) {
        return channelMapper.selectById(id);
    }

    @Transactional
    public Channel create(Channel channel) {
        // 保存前 trim baseUrl，防止前后空白字符导致 URL 解析失败
        if (channel.getBaseUrl() != null) {
            channel.setBaseUrl(channel.getBaseUrl().trim());
        }
        channelMapper.insert(channel);
        // 修复 SQLite 下 MyBatis Plus 无法正确获取自增 ID 的问题
        if (channel.getId() == null) {
            Long generatedId = channelMapper.getLastInsertId();
            channel.setId(generatedId);
        }
        // 创建渠道后自动加载模型
        loadModels(channel);
        return channel;
    }

    @Transactional
    public Channel update(Channel channel) {
        // 保存前 trim baseUrl，防止前后空白字符导致 URL 解析失败
        if (channel.getBaseUrl() != null) {
            channel.setBaseUrl(channel.getBaseUrl().trim());
        }
        channelMapper.updateById(channel);
        return channel;
    }

    /**
     * 编辑模式下更新渠道并同步模型
     * 对比页面提交的模型列表和数据库中的模型：
     * - 删除已在页面移除的模型（数据库有但页面没有，或标记为_deleted）
     * - 新增页面新添加的模型（页面有但数据库没有的）
     */
    @Transactional
    public Channel updateWithModels(Channel channel, String modelsJson) {
        // 保存前 trim baseUrl，防止前后空白字符导致 URL 解析失败
        if (channel.getBaseUrl() != null) {
            channel.setBaseUrl(channel.getBaseUrl().trim());
        }
        // 1. 更新渠道基本信息
        channelMapper.updateById(channel);

        // 2. 获取数据库中当前渠道的所有模型
        List<ChannelModel> existingModels = channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channel.getId()));

        if (modelsJson == null || modelsJson.isEmpty() || "[]".equals(modelsJson)) {
            // 没有模型数据，删除所有现有模型
            for (ChannelModel cm : existingModels) {
                channelModelMapper.deleteById(cm.getId());
            }
            log.info("渠道 {} 模型已清空", channel.getName());
            return channel;
        }

        try {
            // 3. 解析页面提交的模型列表
            List<Map<String, Object>> submittedModels = objectMapper.readValue(modelsJson,
                    new TypeReference<List<Map<String, Object>>>() {});

            // 4. 构建页面提交的模型名称集合
            Set<String> submittedNames = new HashSet<>();
            Set<Long> submittedIds = new HashSet<>();
            for (Map<String, Object> m : submittedModels) {
                Boolean deleted = (Boolean) m.get("_deleted");
                if (deleted != null && deleted) {
                    continue; // 跳过标记删除的
                }
                String modelName = (String) m.get("modelName");
                if (modelName != null && !modelName.isEmpty()) {
                    submittedNames.add(modelName);
                    Object idObj = m.get("id");
                    if (idObj != null) {
                        try {
                            submittedIds.add(Long.parseLong(idObj.toString()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            // 5. 删除：在数据库中存在但页面没有提交的模型
            int deletedCount = 0;
            for (ChannelModel cm : existingModels) {
                // 如果数据库中的模型不在页面提交的列表中，则删除
                // （即页面没有保留该模型，或者标记了删除）
                if (!submittedNames.contains(cm.getModelName())) {
                    channelModelMapper.deleteById(cm.getId());
                    deletedCount++;
                }
            }

            // 6. 新增：页面有但数据库没有的模型
            Set<String> existingNames = existingModels.stream()
                    .map(ChannelModel::getModelName)
                    .collect(Collectors.toSet());
            // 建立已有模型名的 → 实体映射，便于后续更新已存在的模型
            java.util.Map<String, ChannelModel> existingByName = existingModels.stream()
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
                    // 新模型：插入并设置 input
                    ChannelModel cm = new ChannelModel(channel.getId(), modelName, displayName);
                    applyRulesToModel(cm);
                    channelModelMapper.insert(cm);
                    addedCount++;
                    existingNames.add(modelName);
                } else {
                    // 已有模型：重新应用规则更新 input 字段
                    ChannelModel cm = existingByName.get(modelName);
                    // 若 existingByName 中找不到（如同名的模型刚被插入），回退查 DB
                    if (cm == null) {
                        cm = channelModelMapper.selectOne(
                                new LambdaQueryWrapper<ChannelModel>()
                                        .eq(ChannelModel::getChannelId, channel.getId())
                                        .eq(ChannelModel::getModelName, modelName));
                    }
                    if (cm != null) {
                        String oldInput = cm.getInput();
                        applyRulesToModel(cm);
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

            log.info("渠道 {} 模型同步完成：新增 {} 个，更新 {} 个，删除 {} 个", channel.getName(), addedCount, updatedCount, deletedCount);

        } catch (Exception e) {
            log.warn("同步模型列表失败: {}", e.getMessage());
        }

        return channel;
    }

    @Transactional
    public void delete(Long id) {
        // Explicitly delete associated ChannelModel entries before deleting the channel
        // This ensures the model association list is cleared when a channel is removed
        try {
            channelModelMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.myai.gateway.entity.ChannelModel>()
                .eq(com.myai.gateway.entity.ChannelModel::getChannelId, id));
        } catch (Exception e) {
            log.warn("Failed to delete associated ChannelModel entries for channel {}: {}", id, e.getMessage());
        }
        // Delete the channel itself
        channelMapper.deleteById(id);
    }

    /**
     * 根据渠道类型加载模型列表（保存到数据库）
     * 先删除所有 source='api' 的模型，再插入从 API 获取的新模型，
     * 保留手动添加的模型不动
     */
    @Transactional
    public List<ChannelModel> loadModels(Channel channel, String apiKey) {
        // 先删除该渠道下所有 source='api' 的模型（接口获取的）
        channelModelMapper.delete(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channel.getId())
                        .eq(ChannelModel::getSource, "api"));

        List<ChannelModel> newModels;
        if (apiKey != null && !apiKey.isEmpty()) {
            // 创建临时 channel 对象，设置指定的 apiKey 用于获取模型列表
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
        
        // 获取当前手动添加的模型名称（数据库中还存在的，但不包括刚删除的 API 模型）
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
        
        // 返回更新后的所有模型
        return channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>().eq(ChannelModel::getChannelId, channel.getId()));
    }

    /**
     * 加载模型列表（使用渠道的第一个 API Key）
     */
    @Transactional
    public List<ChannelModel> loadModels(Channel channel) {
        // 获取第一个可用的 API Key
        String apiKey = null;
        try {
            var apiKeys = channelApiKeyService.getAvailableApiKeys(channel.getId());
            if (apiKeys != null && !apiKeys.isEmpty()) {
                apiKey = apiKeys.get(0).getApiKey();
            }
        } catch (Exception e) {
            log.warn("获取渠道 API Key 失败: {}", e.getMessage());
        }
        return loadModels(channel, apiKey);
    }

    /**
     * 构建模型的 API URL（直接在 baseUrl 后追加 /models）
     */
    String buildModelsUrl(Channel channel) {
        String baseUrl = channel.getBaseUrl();
        // 防御性处理：trim 掉可能存在的前后空白字符（数据库中可能存了带空格的 URL）
        if (baseUrl != null) {
            baseUrl = baseUrl.trim();
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = switch (channel.getChannelType()) {
                case "anthropic" -> "https://api.anthropic.com/v1";
                default -> "https://api.openai.com/v1";
            };
        }
        // 确保末尾没有多余的 /
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/models";
    }

    /**
     * 从 AI 提供商获取模型列表
     * 支持 OpenAI 兼容和 Anthropic
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
     * 预览获取模型列表（不保存到数据库），用于表单页 AJAX
     */
    public List<ChannelModel> previewFetchModels(String baseUrl, String apiKey,
                                                  String channelType) {
        Channel channel = new Channel();
        channel.setName("preview");
        channel.setChannelType(channelType);
        channel.setApiKey(apiKey);
        channel.setBaseUrl(baseUrl);
        return fetchModelsFromProvider(channel);
    }

    /**
     * 使用手动输入的模型列表创建渠道（适用于不提供模型列表 API 的渠道）
     */
    @Transactional
    public Channel createWithManualModels(Channel channel, String manualModelsJson) {
        channelMapper.insert(channel);
        // 修复 SQLite 下 MyBatis Plus 无法正确获取自增 ID 的问题
        // SQLite 使用 last_insert_rowid() 而不是 MySQL 的方式
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
     * 根据多模态规则自动匹配并设置模型的 input 字段
     */
    private void applyRulesToModel(ChannelModel model) {
        if (model == null || model.getModelName() == null) {
            return;
        }
        String input = multiModalRuleService.computeInput(model.getModelName());
        model.setInput(input);
    }

    /**
     * 手动添加模型（编辑模式 AJAX）
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
     * 获取预设的默认模型列表
     */
    private List<ChannelModel> getDefaultModels(Channel channel) {
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
     * 删除单个渠道模型
     */
    public void deleteChannelModel(Long modelId) {
        channelModelMapper.deleteById(modelId);
    }

    /**
     * 删除渠道下的所有模型
     */
    @Transactional
    public int deleteAllChannelModels(Long channelId) {
        return channelModelMapper.delete(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channelId));
    }

    /**
     * 重新加载指定渠道的模型
     */
    @Transactional
    public List<ChannelModel> reloadModels(Long channelId) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null) {
            throw new RuntimeException("渠道不存在");
        }
        return loadModels(channel);
    }

    /**
     * 获取所有启用的渠道
     */
    public List<Channel> listEnabled() {
        return channelMapper.selectList(
                new LambdaQueryWrapper<Channel>()
                        .eq(Channel::getEnabled, 1)
                        .orderByAsc(Channel::getSortOrder));
    }
}
