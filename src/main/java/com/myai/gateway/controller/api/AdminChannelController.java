package com.myai.gateway.controller.api;

import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.relay.RelayService;
import com.myai.gateway.service.ChannelApiKeyService;
import com.myai.gateway.service.ChannelService;
import com.myai.gateway.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台「渠道」REST API 控制器
 * <p>从原 {@link AdminApiController} 拆分而来（P2 架构：巨型类拆分），
 * 承载渠道的增删改查、模型加载/管理、用量统计与快速连通测试接口。路径前缀与行为与原实现完全一致。</p>
 */
@RestController
@RequestMapping("/admin/api")
public class AdminChannelController {

    private static final Logger log = LoggerFactory.getLogger(AdminChannelController.class);

    private final ChannelService channelService;
    private final ChannelApiKeyService channelApiKeyService;
    private final StatsService statsService;
    private final RelayService relayService;

    public AdminChannelController(ChannelService channelService,
                                  ChannelApiKeyService channelApiKeyService,
                                  StatsService statsService,
                                  RelayService relayService) {
        this.channelService = channelService;
        this.channelApiKeyService = channelApiKeyService;
        this.statsService = statsService;
        this.relayService = relayService;
    }

    @GetMapping(value = "/channels", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<Map<String, Object>>> listChannels() {
        List<Channel> channels = channelService.listAll();
        Map<String, Map<String, Object>> usageStats = statsService.getChannelSummaryStats();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Channel ch : channels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ch.getId());
            item.put("name", ch.getName());
            item.put("channelType", ch.getChannelType());
            item.put("baseUrl", ch.getBaseUrl());
            item.put("enabled", ch.getEnabled());
            item.put("sortOrder", ch.getSortOrder());
            item.put("createdAt", ch.getCreatedAt());
            item.put("updatedAt", ch.getUpdatedAt());
            // 附加用量统计
            Map<String, Object> usage = usageStats.get(ch.getName());
            if (usage != null) {
                item.put("requestCount", usage.get("requestCount"));
                item.put("promptTokens", usage.get("promptTokens"));
                item.put("completionTokens", usage.get("completionTokens"));
                item.put("totalTokens", usage.get("totalTokens"));
            } else {
                item.put("requestCount", 0L);
                item.put("promptTokens", 0L);
                item.put("completionTokens", 0L);
                item.put("totalTokens", 0L);
            }
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/channels/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getChannel(@PathVariable Long id) {
        Channel channel = channelService.getById(id);
        if (channel == null) {
            return ResponseEntity.status(404).body(Map.of("error", "渠道不存在"));
        }
        List<ChannelModel> channelModels = channelService.getChannelModelsAll(id);
        List<ChannelApiKey> apiKeys = channelApiKeyService.listByChannelId(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", channel);
        result.put("channelModels", channelModels);
        result.put("apiKeys", apiKeys);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/channels", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> createChannel(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Channel channel = new Channel();
            channel.setName((String) body.get("name"));
            channel.setChannelType((String) body.get("channelType"));
            channel.setBaseUrl((String) body.get("baseUrl"));
            channel.setEnabled(body.get("enabled") != null ? Integer.parseInt(body.get("enabled").toString()) : 1);
            channel.setModelRefreshEnabled(body.get("model_refresh_enabled") != null
                    ? Integer.parseInt(body.get("model_refresh_enabled").toString()) : 1);

            String manualModels = body.get("manualModels") != null ? body.get("manualModels").toString() : "[]";
            String apiKeysJson = body.get("apiKeysJson") != null ? body.get("apiKeysJson").toString() : "[]";

            if (!"[]".equals(manualModels)) {
                channelService.createWithManualModels(channel, manualModels);
            } else {
                channelService.create(channel);
            }

            if (channel.getId() != null && !"[]".equals(apiKeysJson)) {
                channelApiKeyService.syncApiKeys(channel.getId(), parseApiKeysJson(apiKeysJson));
            }

            result.put("success", true);
            result.put("id", channel.getId());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping(value = "/channels/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> updateChannel(@PathVariable Long id,
                                                              @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Channel channel = channelService.getById(id);
            if (channel == null) {
                result.put("success", false);
                result.put("error", "渠道不存在");
                return ResponseEntity.ok(result);
            }
            if (body.containsKey("name")) channel.setName((String) body.get("name"));
            if (body.containsKey("channelType")) channel.setChannelType((String) body.get("channelType"));
            if (body.containsKey("baseUrl")) channel.setBaseUrl((String) body.get("baseUrl"));
            if (body.containsKey("enabled")) channel.setEnabled(Integer.parseInt(body.get("enabled").toString()));
            if (body.containsKey("model_refresh_enabled")) {
                channel.setModelRefreshEnabled(Integer.parseInt(body.get("model_refresh_enabled").toString()));
            }

            String manualModels = body.get("manualModels") != null ? body.get("manualModels").toString() : null;
            String apiKeysJson = body.get("apiKeysJson") != null ? body.get("apiKeysJson").toString() : null;

            if (manualModels != null) {
                channelService.updateWithModels(channel, manualModels);
            } else {
                channelService.update(channel);
            }

            if (apiKeysJson != null) {
                channelApiKeyService.syncApiKeys(id, parseApiKeysJson(apiKeysJson));
            }

            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping(value = "/channels/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> deleteChannel(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            channelService.delete(id);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/channels/{id}/models", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getChannelModels(@PathVariable Long id) {
        try {
            Channel channel = channelService.getById(id);
            if (channel == null) {
                return ResponseEntity.status(404).body(Map.of("error", "渠道不存在"));
            }
            List<ChannelModel> channelModels = channelService.getChannelModels(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("channel", channel);
            result.put("models", channelModels);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取指定渠道的模型用量统计
     * 返回该渠道下每个模型的 token 用量、请求次数、最近30次平均响应时间
     * 以及渠道整体最近30次平均响应时间
     */
    @GetMapping(value = "/channels/{id}/usage-stats", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getChannelUsageStats(@PathVariable Long id) {
        try {
            Channel channel = channelService.getById(id);
            if (channel == null) {
                return ResponseEntity.status(404).body(Map.of("error", "渠道不存在"));
            }
            Map<String, Object> usageData = statsService.getChannelModelUsageStats(channel.getName());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("channel", channel);
            result.put("modelStats", usageData.get("modelStats"));
            result.put("channelAvgResponseTimeRecent30", usageData.get("channelAvgResponseTimeRecent30"));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/channels/{id}/reload-models", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> reloadModels(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ChannelModel> models = channelService.reloadModels(id);
            result.put("success", true);
            result.put("data", models);
            result.put("count", models.size());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/channels/fetch-models", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> fetchModels(@RequestParam String baseUrl,
                                                            @RequestParam String apiKey,
                                                            @RequestParam String channelType) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ChannelModel> models = channelService.previewFetchModels(baseUrl, apiKey, channelType);
            result.put("success", true);
            result.put("data", models);
            result.put("count", models.size());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/channels/{channelId}/models", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> addManualModel(@PathVariable Long channelId,
                                                               @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String modelName = (String) body.get("modelName");
            String displayName = (String) body.get("displayName");
            if (displayName == null || displayName.isEmpty()) displayName = modelName;
            ChannelModel cm = channelService.addManualModel(channelId, modelName, displayName);
            result.put("success", true);
            result.put("data", cm);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/channels/{id}/quick-test", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> quickTest(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String message = (String) body.getOrDefault("message", "Hello");
            Channel channel = channelService.getById(id);
            if (channel == null) {
                result.put("success", false);
                result.put("error", "渠道不存在");
                return ResponseEntity.ok(result);
            }

            // 获取渠道的可用 API Key（优先从 channel_api_keys 表获取）
            // 支持前端传入 apiKeyId 来指定测试使用的 API Key
            ChannelApiKey availableKey = null;
            Object apiKeyIdObj = body.get("apiKeyId");
            if (apiKeyIdObj != null) {
                Long apiKeyId;
                try {
                    apiKeyId = Long.parseLong(apiKeyIdObj.toString());
                } catch (NumberFormatException e) {
                    result.put("success", false);
                    result.put("error", "apiKeyId 必须为数字");
                    return ResponseEntity.ok(result);
                }
                availableKey = channelApiKeyService.getById(apiKeyId);
                if (availableKey == null || !availableKey.getChannelId().equals(channel.getId())) {
                    result.put("success", false);
                    result.put("error", "指定的 API Key 不存在或不属于该渠道");
                    return ResponseEntity.ok(result);
                }
                if (availableKey.getEnabled() == null || availableKey.getEnabled() != 1) {
                    result.put("success", false);
                    result.put("error", "指定的 API Key 已被禁用");
                    return ResponseEntity.ok(result);
                }
            }
            if (availableKey == null) {
                availableKey = channelApiKeyService.getAvailableApiKey(channel.getId());
            }
            if (availableKey == null) {
                result.put("success", false);
                result.put("error", "渠道没有可用的 API Key，请先添加 API Key");
                return ResponseEntity.ok(result);
            }

            List<ChannelModel> models = channelService.getChannelModels(id);
            if (models.isEmpty()) {
                result.put("success", false);
                result.put("error", "渠道没有可用模型，请先加载模型");
                return ResponseEntity.ok(result);
            }

            // 支持前端传入 modelName 来选择测试模型，默认使用第一个模型
            String requestedModel = (String) body.get("modelName");
            ChannelModel testChannelModel = null;
            if (requestedModel != null && !requestedModel.isEmpty()) {
                testChannelModel = models.stream()
                        .filter(m -> requestedModel.equals(m.getModelName()))
                        .findFirst().orElse(null);
            }
            if (testChannelModel == null) {
                testChannelModel = models.get(0);
            }
            String testModel = testChannelModel.getModelName();

            long startTime = System.currentTimeMillis();
            // 走网关中继测试 — 复用 RelayService 的 WebClient、header 构建和错误处理（流式，测量首字节响应时间）
            RelayService.ChannelTestResult testResult = relayService.testChannelModel(channel, testChannelModel, availableKey, message);

            long totalTime = System.currentTimeMillis() - startTime;
            // 输出速度统计：优先取上游返回的 usage token 数，缺失时按输出文本长度估算
            long outputTokens = testResult.usageOutputTokens() != null
                    ? testResult.usageOutputTokens()
                    : estimateTokens(testResult.content());
            double outputSpeed = totalTime > 0 ? outputTokens * 1000.0 / totalTime : 0;

            result.put("success", true);
            result.put("response", testResult.content());
            result.put("ttfb", testResult.firstByteTimeMs()); // 首字节响应时间（ms）
            result.put("model", testModel);
            result.put("outputSpeed", outputSpeed);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 估算文本对应的 token 数：中文（CJK）字符按 1 token 计，其余字符按 4 字符 ≈ 1 token 估算
     */
    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + other / 4;
    }

    @DeleteMapping(value = "/channels/{channelId}/models/{modelId}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> deleteChannelModel(@PathVariable Long channelId,
                                                                   @PathVariable Long modelId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            channelService.deleteChannelModel(modelId);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping(value = "/channels/{channelId}/models", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> deleteAllChannelModels(@PathVariable Long channelId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int count = channelService.deleteAllChannelModels(channelId);
            result.put("success", true);
            result.put("count", count);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    // ==================== Helpers ====================

    private List<ChannelApiKey> parseApiKeysJson(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> list = mapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            return list.stream().map(m -> {
                ChannelApiKey key = new ChannelApiKey();
                if (m.get("id") != null) key.setId(Long.parseLong(m.get("id").toString()));
                key.setKeyName(m.getOrDefault("keyName", "").toString());
                key.setApiKey(m.getOrDefault("apiKey", "").toString());
                key.setEnabled(Integer.parseInt(m.getOrDefault("enabled", "1").toString()));
                key.setSortOrder(Integer.parseInt(m.getOrDefault("sortOrder", "0").toString()));
                return key;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("解析 API Keys JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }
}
