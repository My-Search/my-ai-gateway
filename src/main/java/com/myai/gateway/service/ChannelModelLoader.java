package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelHeaders;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelApiKeyMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import com.myai.gateway.mapper.ModelChannelRelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
    private final TransactionTemplate transactionTemplate;

    public ChannelModelLoader(ChannelMapper channelMapper, ChannelModelMapper channelModelMapper,
                               ModelChannelRelMapper modelChannelRelMapper,
                               ChannelApiKeyMapper channelApiKeyMapper,
                               ObjectMapper objectMapper,
                               MultiModalRuleService multiModalRuleService,
                               PlatformTransactionManager transactionManager) {
        this.channelMapper = channelMapper;
        this.channelModelMapper = channelModelMapper;
        this.modelChannelRelMapper = modelChannelRelMapper;
        this.channelApiKeyMapper = channelApiKeyMapper;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.multiModalRuleService = multiModalRuleService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ModelChannelRelMapper getModelChannelRelMapper() {
        return modelChannelRelMapper;
    }

    public ChannelMapper getChannelMapper() {
        return channelMapper;
    }

    /**
     * 加载模型列表（使用渠道的第一个启用 API Key）
     * <p>不加事务：内部会同步调用服务商 HTTP 拉取。IMMEDIATE 模式下事务从 BEGIN 起就持有
     * SQLite 写锁，HTTP 放在事务内会让写锁阻塞其他写事务数十秒直至 busy_timeout 超时，
     * 事务边界由 {@link #loadModels} 自管。</p>
     */
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
     * <p>从服务商重新拉取模型并替换该渠道下所有 source='api' 的模型。
     * 被删除模型上已关联到入口模型（model_channel_rels）的，按模型名迁移到刷新后的
     * 同名单模型，保证自动/手动刷新不丢失入口模型关联；服务商不再返回的模型则移除对应关联。
     * 若本次拉取失败且渠道已有现役模型，则保留现有模型与关联不变，避免回退预设模型丢弃自定义模型。</p>
     * <p>事务边界：服务商拉取（HTTP，最长可达 60s）与预查在事务外执行；仅「删除旧模型 +
     * 写入新模型 + 恢复入口模型关联」在一个 IMMEDIATE 写事务内完成。IMMEDIATE 模式下
     * 事务从 BEGIN 起就持有 SQLite 写锁，HTTP 若在事务内会让写锁阻塞其他写事务直至
     * busy_timeout 超时（进而污染池连接，见 SelfHealingHikariDataSource），故必须拆开。</p>
     */
    public List<ChannelModel> loadModels(Channel channel, String apiKey) {
        Long channelId = channel.getId();

        // 预查将被替换的 api 模型（供刷新后按模型名恢复关联）
        List<ChannelModel> apiModelsToDelete = channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>()
                        .eq(ChannelModel::getChannelId, channelId)
                        .eq(ChannelModel::getSource, "api"));

        // 先拉取，成功后再删除替换，避免拉取失败时旧模型被清空
        List<ChannelModel> newModels = fetchNewModels(channel, apiKey);
        if (newModels.isEmpty()) {
            long existingCount = channelModelMapper.selectCount(
                    new LambdaQueryWrapper<ChannelModel>()
                            .eq(ChannelModel::getChannelId, channelId));
            if (existingCount == 0) {
                // 新建渠道：拉取失败或未配置 Key 时回退预设模型
                newModels = getDefaultModels(channel);
            } else {
                // 已有模型的刷新：保留现状，不做任何替换
                log.warn("渠道 {} 刷新模型拉取失败或服务商未返回模型，保留现有模型", channel.getName());
                return channelModelMapper.selectList(
                        new LambdaQueryWrapper<ChannelModel>().eq(ChannelModel::getChannelId, channelId));
            }
        }

        // 收集将被删除的 api 模型及其入口模型关联，刷新后按模型名恢复
        Map<Long, String> modelNameByDeletedId = apiModelsToDelete.stream()
                .collect(Collectors.toMap(ChannelModel::getId, ChannelModel::getModelName));
        final List<ChannelModel> modelsToApply = newModels;

        transactionTemplate.executeWithoutResult(status -> {
            List<ModelChannelRel> affectedRels;
            if (modelNameByDeletedId.isEmpty()) {
                affectedRels = Collections.emptyList();
            } else {
                affectedRels = modelChannelRelMapper.selectList(
                        new LambdaQueryWrapper<ModelChannelRel>()
                                .in(ModelChannelRel::getChannelModelId, modelNameByDeletedId.keySet()));
                // 先删除旧关联，避免悬空
                modelChannelRelMapper.delete(
                        new LambdaQueryWrapper<ModelChannelRel>()
                                .in(ModelChannelRel::getChannelModelId, modelNameByDeletedId.keySet()));
            }
            channelModelMapper.delete(
                    new LambdaQueryWrapper<ChannelModel>()
                            .eq(ChannelModel::getChannelId, channelId)
                            .eq(ChannelModel::getSource, "api"));

            // 获取当前手动添加的模型名称
            List<ChannelModel> existingManualModels = channelModelMapper.selectList(
                    new LambdaQueryWrapper<ChannelModel>()
                            .eq(ChannelModel::getChannelId, channelId));
            Set<String> existingManualModelNameSet = existingManualModels.stream()
                    .map(ChannelModel::getModelName)
                    .collect(Collectors.toSet());

            // 插入新获取的模型，跳过与手动添加模型重名的
            int addedCount = 0;
            for (ChannelModel model : modelsToApply) {
                if (!existingManualModelNameSet.contains(model.getModelName())) {
                    model.setChannelId(channelId);
                    if (model.getSource() == null) {
                        model.setSource("api");
                    }
                    applyRulesToModel(model);
                    channelModelMapper.insert(model);
                    addedCount++;
                }
            }

            // 按模型名恢复被刷新前删除的入口模型关联
            if (!affectedRels.isEmpty()) {
                restoreRelsAfterRefresh(channelId, affectedRels, modelNameByDeletedId);
            }

            log.info("渠道 {} 重新加载了 {} 个模型（保留 {} 个手动模型）",
                    channel.getName(), addedCount, existingManualModels.size());
        });

        return channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>().eq(ChannelModel::getChannelId, channelId));
    }

    /**
     * 刷新模型后按模型名恢复入口模型关联：
     * 刷新前被删除模型上的关联迁移到刷新后同名的渠道模型；模型不再存在时丢弃该关联并记录日志。
     */
    private void restoreRelsAfterRefresh(Long channelId, List<ModelChannelRel> affectedRels,
                                         Map<Long, String> modelNameByDeletedId) {
        List<ChannelModel> currentModels = channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>().eq(ChannelModel::getChannelId, channelId));
        Map<String, ChannelModel> currentByName = currentModels.stream()
                .collect(Collectors.toMap(ChannelModel::getModelName, cm -> cm, (a, b) -> a));

        int restored = 0;
        int dropped = 0;
        for (ModelChannelRel rel : affectedRels) {
            String modelName = modelNameByDeletedId.get(rel.getChannelModelId());
            ChannelModel target = currentByName.get(modelName);
            if (target == null) {
                dropped++;
                continue;
            }
            rel.setId(null); // 重新插入，保留 weight/reasoningEffort/sortOrder/enabled
            rel.setChannelModelId(target.getId());
            modelChannelRelMapper.insert(rel);
            restored++;
        }
        if (restored > 0) {
            log.info("渠道 {} 刷新后按模型名恢复 {} 条入口模型关联", channelId, restored);
        }
        if (dropped > 0) {
            log.warn("渠道 {} 刷新后有 {} 条关联的模型不再存在，已移除", channelId, dropped);
        }
    }

    /**
     * 从服务商获取模型列表；请求失败或未配置 API Key 时返回空列表（由调用方决定回退策略）。
     * 包级可见，便于测试覆写以隔离真实 HTTP 调用。
     */
    List<ChannelModel> fetchNewModels(Channel channel, String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            Channel tempChannel = new Channel();
            tempChannel.setName(channel.getName());
            tempChannel.setChannelType(channel.getChannelType());
            tempChannel.setBaseUrl(channel.getBaseUrl());
            tempChannel.setApiKey(apiKey);
            return fetchModelsFromProvider(tempChannel);
        }
        return fetchModelsFromProvider(channel);
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

            // 追加自定义请求头
            Map<String, String> extraHeaders = ChannelHeaders.parse(channel.getCustomHeaders());
            if (extraHeaders != null) {
                extraHeaders.forEach(requestBuilder::header);
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
