package com.myai.gateway.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.entity.*;
import com.myai.gateway.relay.RelayService;
import com.myai.gateway.service.*;
import com.myai.gateway.config.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理后台 REST API 控制器
 * 为 Vue 前端提供 JSON 数据接口
 */
@RestController
@RequestMapping("/admin/api")
public class AdminApiController {

    private static final Logger log = LoggerFactory.getLogger(AdminApiController.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StatsService statsService;
    private final ChannelService channelService;
    private final ChannelApiKeyService channelApiKeyService;
    private final ModelService modelService;
    private final ApiKeyService apiKeyService;
    private final RequestLogService requestLogService;
    private final LogSseService logSseService;
    private final AdminConfigService adminConfigService;
    private final RelayService relayService;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider jwtTokenProvider;

    public AdminApiController(StatsService statsService, ChannelService channelService,
                              ChannelApiKeyService channelApiKeyService, ModelService modelService,
                              ApiKeyService apiKeyService, RequestLogService requestLogService,
                              LogSseService logSseService, AdminConfigService adminConfigService,
                              RelayService relayService, ObjectMapper objectMapper,
                              JwtTokenProvider jwtTokenProvider) {
        this.statsService = statsService;
        this.channelService = channelService;
        this.channelApiKeyService = channelApiKeyService;
        this.modelService = modelService;
        this.apiKeyService = apiKeyService;
        this.requestLogService = requestLogService;
        this.logSseService = logSseService;
        this.adminConfigService = adminConfigService;
        this.relayService = relayService;
        this.objectMapper = objectMapper;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    // ==================== Auth ====================

    @GetMapping(value = "/auth/check", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> checkAuth(HttpServletRequest request) {
        boolean authenticated = false;

        // 方式1：JWT Token 认证
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            authenticated = jwtTokenProvider.validateToken(token);
        }

        // 方式2：Session 认证（向后兼容）
        if (!authenticated) {
            HttpSession session = request.getSession(false);
            authenticated = session != null && session.getAttribute("adminUser") != null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", authenticated);
        result.put("hasAdminAccount", adminConfigService.hasAdminAccount());
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/auth/login", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        Map<String, Object> result = new LinkedHashMap<>();

        if (!adminConfigService.hasAdminAccount()) {
            result.put("success", false);
            result.put("error", "请先设置管理员账号");
            return ResponseEntity.ok(result);
        }

        if (adminConfigService.verify(username, password)) {
            // 生成 JWT Token
            String token = jwtTokenProvider.generateToken(username);
            HttpSession session = request.getSession(true);
            session.setAttribute("adminUser", username);
            session.setMaxInactiveInterval(8 * 60 * 60);
            result.put("success", true);
            result.put("username", username);
            result.put("token", token);
        } else {
            result.put("success", false);
            result.put("error", "用户名或密码错误");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/auth/setup", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> setup(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        String confirmPassword = body.get("confirmPassword");
        Map<String, Object> result = new LinkedHashMap<>();

        if (!password.equals(confirmPassword)) {
            result.put("success", false);
            result.put("error", "两次输入的密码不一致");
            return ResponseEntity.ok(result);
        }
        if (password.length() < 6) {
            result.put("success", false);
            result.put("error", "密码长度至少6位");
            return ResponseEntity.ok(result);
        }

        boolean success = adminConfigService.setAdminAccount(username, password);
        if (!success) {
            result.put("success", false);
            result.put("error", "管理员账号已存在");
            return ResponseEntity.ok(result);
        }

        // 生成 JWT Token
        String token = jwtTokenProvider.generateToken(username);
        HttpSession session = request.getSession(true);
        session.setAttribute("adminUser", username);
        session.setMaxInactiveInterval(8 * 60 * 60);
        result.put("success", true);
        result.put("token", token);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/auth/logout", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    // ==================== Dashboard ====================

    @GetMapping(value = "/dashboard/stats", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> dashboardStats() {
        Map<String, Object> stats = statsService.getDashboardStats();
        stats.put("channelCount", channelService.listAll().size());
        stats.put("customModelCount", modelService.listAll().size());
        stats.put("apiKeyCount", apiKeyService.listAll().size());
        return ResponseEntity.ok(stats);
    }

    // ==================== Channels ====================

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
            item.put("apiKeys", ch.getApiKeys());
            item.put("models", ch.getModels());
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
            List<ChannelModel> models = channelService.getChannelModels(id);
            if (models.isEmpty()) {
                result.put("success", false);
                result.put("error", "渠道没有可用模型，请先加载模型");
                return ResponseEntity.ok(result);
            }

            var webClient = org.springframework.web.reactive.function.client.WebClient.builder()
                    .codecs(config -> config.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                    .build();

            String testModel = models.get(0).getModelName();
            String baseUrl = channel.getBaseUrl();
            if (baseUrl != null && baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

            long startTime = System.currentTimeMillis();
            String endpoint;
            String requestBody;
            Map<String, String> headers = new HashMap<>();

            if ("anthropic".equals(channel.getChannelType())) {
                endpoint = baseUrl + "/messages";
                requestBody = "{\"model\":\"" + testModel + "\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"" + message + "\"}]}";
                headers.put("x-api-key", channel.getApiKey());
                headers.put("anthropic-version", "2023-06-01");
            } else {
                endpoint = baseUrl + "/chat/completions";
                requestBody = "{\"model\":\"" + testModel + "\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"" + message + "\"}]}";
                headers.put("Authorization", "Bearer " + channel.getApiKey());
            }
            headers.put("Content-Type", "application/json");

            String response = webClient.post()
                    .uri(endpoint)
                    .headers(h -> headers.forEach(h::set))
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(30));

            long responseTime = System.currentTimeMillis() - startTime;
            String content = extractContent(response, channel.getChannelType());

            result.put("success", true);
            result.put("response", content);
            result.put("responseTime", responseTime);
            result.put("model", testModel);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    private String extractContent(String response, String channelType) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var json = mapper.readTree(response);
            if ("anthropic".equals(channelType)) {
                if (json.has("content") && json.get("content").isArray() && json.get("content").size() > 0) {
                    return json.get("content").get(0).get("text").asText();
                }
            } else {
                if (json.has("choices") && json.get("choices").isArray() && json.get("choices").size() > 0) {
                    return json.get("choices").get(0).get("message").get("content").asText();
                }
            }
            if (json.has("error")) return "API Error: " + json.get("error").toString();
            return response.length() > 500 ? response.substring(0, 500) + "..." : response;
        } catch (Exception e) {
            return response != null ? (response.length() > 500 ? response.substring(0, 500) + "..." : response) : "Empty response";
        }
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

    // ==================== Custom Models ====================

    @GetMapping(value = "/models", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<com.myai.gateway.entity.Model>> listModels() {
        return ResponseEntity.ok(modelService.listAll());
    }

    @GetMapping(value = "/models/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getModel(@PathVariable Long id) {
        com.myai.gateway.entity.Model m = modelService.getById(id);
        if (m == null) return ResponseEntity.status(404).body(Map.of("error", "模型不存在"));
        return ResponseEntity.ok(m);
    }

    @PostMapping(value = "/models", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> createModel(@RequestBody com.myai.gateway.entity.Model entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            modelService.create(entity);
            result.put("success", true);
            result.put("id", entity.getId());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping(value = "/models/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> updateModel(@PathVariable Long id,
                                                            @RequestBody com.myai.gateway.entity.Model entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            entity.setId(id);
            modelService.update(entity);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping(value = "/models/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> deleteModel(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            modelService.delete(id);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/models/{id}/rels", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getModelRels(@PathVariable Long id) {
        com.myai.gateway.entity.Model m = modelService.getById(id);
        if (m == null) return ResponseEntity.status(404).body(Map.of("error", "模型不存在"));
        List<ModelChannelRel> rels = modelService.getChannelRels(id);
        List<ChannelModel> availableModels = modelService.getAllAvailableChannelModels();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", m);
        result.put("rels", rels);
        result.put("availableModels", availableModels);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/models/{modelId}/rels", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> batchAddRels(@PathVariable Long modelId,
                                                             @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("channelModelIds");
            List<Long> channelModelIds = rawIds.stream().map(Integer::longValue).collect(Collectors.toList());
            String sortedRelIds = (String) body.get("sortedRelIds");
            int count = modelService.batchAddChannelRels(modelId, channelModelIds);
            if (sortedRelIds != null && !sortedRelIds.isEmpty()) {
                List<Long> sortedIds = Arrays.stream(sortedRelIds.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).map(Long::parseLong)
                        .collect(Collectors.toList());
                if (!sortedIds.isEmpty()) modelService.updateChannelRelSortOrders(sortedIds);
            }
            result.put("success", true);
            result.put("count", count);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping(value = "/models/rels/{relId}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> removeRel(@PathVariable Long relId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            modelService.removeChannelRel(relId);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping(value = "/models/rels/{relId}/sort", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> updateRelSort(@PathVariable Long relId,
                                                              @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Integer sortOrder = Integer.valueOf(body.get("sortOrder").toString());
            modelService.updateChannelRelSortOrder(relId, sortOrder);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/models/{id}/circuit-breaker", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getCircuitBreaker(@PathVariable Long id) {
        com.myai.gateway.entity.Model m = modelService.getById(id);
        if (m == null) return ResponseEntity.status(404).body(Map.of("error", "模型不存在"));
        CircuitBreakerConfig config = modelService.getCircuitBreakerConfig(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", m);
        result.put("config", config);
        return ResponseEntity.ok(result);
    }

    @PutMapping(value = "/models/{id}/circuit-breaker", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> saveCircuitBreaker(@PathVariable Long id,
                                                                   @RequestBody CircuitBreakerConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            config.setModelId(id);
            modelService.updateCircuitBreakerConfig(config);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    // ==================== API Keys ====================

    @GetMapping(value = "/api-keys", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<ApiKey>> listApiKeys() {
        return ResponseEntity.ok(apiKeyService.listAll());
    }

    @GetMapping(value = "/api-keys/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getApiKey(@PathVariable Long id) {
        ApiKey key = apiKeyService.getById(id);
        if (key == null) return ResponseEntity.status(404).body(Map.of("error", "密钥不存在"));
        return ResponseEntity.ok(key);
    }

    @PostMapping(value = "/api-keys", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> createApiKey(@RequestBody ApiKey apiKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            apiKeyService.create(apiKey);
            result.put("success", true);
            result.put("id", apiKey.getId());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping(value = "/api-keys/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> updateApiKey(@PathVariable Long id,
                                                             @RequestBody ApiKey apiKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            apiKey.setId(id);
            apiKeyService.update(apiKey);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping(value = "/api-keys/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> deleteApiKey(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            apiKeyService.delete(id);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/api-keys/{id}/toggle-share", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> toggleShare(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            boolean shared = body.containsKey("shared") && Boolean.TRUE.equals(body.get("shared"));
            ApiKey updated = apiKeyService.toggleShare(id, shared);
            result.put("success", true);
            result.put("shared", shared);
            if (shared && updated != null) {
                result.put("shareCode", updated.getShareCode());
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    // ==================== Logs ====================

    @GetMapping(value = "/logs", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> listLogs(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        // 获取总数
        long totalTraces = requestLogService.getTraceCount();
        
        // 分页查询日志
        List<RequestLog> logs = requestLogService.getLogsByPage(offset, limit);
        Map<String, List<RequestLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(RequestLog::getTraceId));
        List<Map<String, Object>> treeData = new ArrayList<>();

        for (Map.Entry<String, List<RequestLog>> entry : grouped.entrySet()) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("traceId", entry.getKey());
            List<RequestLog> sortedLogs = entry.getValue().stream()
                    .sorted(Comparator.comparing(RequestLog::getCreatedAt))
                    .collect(Collectors.toList());
            trace.put("logs", sortedLogs);

            int retryCount = (int) sortedLogs.stream().filter(l -> "retry".equals(l.getPhase())).count();
            int successCount = (int) sortedLogs.stream().filter(l -> "success".equals(l.getPhase())).count();
            int failCount = (int) sortedLogs.stream().filter(l -> "fail".equals(l.getPhase())).count();
            trace.put("retryCount", retryCount);
            trace.put("successCount", successCount);
            trace.put("failCount", failCount);

            String modelName = sortedLogs.stream().filter(l -> l.getModelName() != null)
                    .map(RequestLog::getModelName).findFirst().orElse("");
            trace.put("modelName", modelName);

            long totalTime = sortedLogs.stream()
                    .filter(l -> ("success".equals(l.getPhase()) || "fail".equals(l.getPhase()))
                            && l.getResponseTimeMs() != null)
                    .mapToLong(RequestLog::getResponseTimeMs).sum();
            trace.put("totalTimeMs", totalTime);

            if (!sortedLogs.isEmpty()) {
                trace.put("startTime", sortedLogs.get(0).getCreatedAt().format(DT_FMT));
                trace.put("endTime", sortedLogs.get(sortedLogs.size() - 1).getCreatedAt().format(DT_FMT));
            }
            treeData.add(trace);
        }

        treeData.sort((a, b) -> {
            var timeA = (String) a.get("endTime");
            var timeB = (String) b.get("endTime");
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            return timeB.compareTo(timeA);
        });

        // 返回分页数据
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", treeData);
        result.put("total", totalTraces);
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("hasMore", offset + treeData.size() < totalTraces);

        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/logs/clean", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> cleanLogs() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            requestLogService.cleanOldLogs(30);
            result.put("success", true);
            result.put("message", "已清理 30 天前的日志");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 日志实时推送 SSE 端点
     * GET /admin/api/logs/stream
     * <p>
     * 前端通过 EventSource 连接后，每次有新日志写入时服务端主动推送
     * event: log / data: {...RequestLog JSON...}
     * </p>
     */
    @GetMapping(value = "/logs/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamLogs(HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        // 不设超时，由前端断开时清理
        SseEmitter emitter = new SseEmitter(0L);

        // 订阅日志流
        var subscription = logSseService.subscribe().subscribe(
                log -> {
                    try {
                        String json = objectMapper.writeValueAsString(log);
                        emitter.send(SseEmitter.event()
                                .name("log")
                                .data(json, MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );

        // 连接断开/超时/异常时取消订阅
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(e -> subscription.dispose());

        return emitter;
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

    // ==================== Playground Chat ====================

    /**
     * 流式聊天测试接口 (SSE)
     * POST /admin/api/chat/stream
     *
     * 请求体:
     * {
     *   "model": "my-gpt4",
     *   "messages": [{"role": "user", "content": "hello"}],
     *   "api_key_id": 1  // 可选，不传则使用第一个可用的 API Key
     * }
     */
    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody String requestBody, HttpServletResponse response) {
        // 显式设置响应头，确保浏览器使用 UTF-8 解码 SSE 流
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时

        try {
            JsonNode json = objectMapper.readTree(requestBody);
            String modelName = json.has("model") ? json.get("model").asText() : "";
            long apiKeyId = json.has("api_key_id") ? json.get("api_key_id").asLong() : 0;

            if (modelName.isEmpty()) {
                sendErrorAndComplete(emitter, "请选择要测试的模型");
                return emitter;
            }

            // 获取 API Key
            ApiKey apiKey = null;
            if (apiKeyId > 0) {
                apiKey = apiKeyService.getById(apiKeyId);
            }
            if (apiKey == null) {
                // 使用第一个可用的 API Key
                List<ApiKey> keys = apiKeyService.listAll();
                for (ApiKey k : keys) {
                    if (k.getEnabled() == 1) {
                        apiKey = k;
                        break;
                    }
                }
            }
            if (apiKey == null) {
                sendErrorAndComplete(emitter, "没有可用的 API Key，请先创建一个");
                return emitter;
            }

            // 构建 OpenAI 格式请求
            ObjectNode openAiRequest = objectMapper.createObjectNode();
            openAiRequest.put("model", modelName);
            openAiRequest.put("stream", true);

            if (json.has("messages")) {
                openAiRequest.set("messages", json.get("messages"));
            } else {
                ArrayNode msgs = openAiRequest.putArray("messages");
                ObjectNode msg = msgs.addObject();
                msg.put("role", "user");
                msg.put("content", json.has("content") ? json.get("content").asText() : "");
            }

            // 可选参数
            if (json.has("temperature")) openAiRequest.put("temperature", json.get("temperature").asDouble());
            if (json.has("max_tokens")) openAiRequest.put("max_tokens", json.get("max_tokens").asInt());

            String requestJson = objectMapper.writeValueAsString(openAiRequest);
            String authHeader = "Bearer " + apiKey.getKeyValue();

            log.info("Playground 测试: 模型={}, API Key={}", modelName, apiKey.getKeyName());

            // 调用流式中继（内部客户端，发送路由进度等自定义事件）
            return relayService.chatCompletionsStream(authHeader, requestJson, true);

        } catch (Exception e) {
            log.error("Playground 聊天请求失败", e);
            sendErrorAndComplete(emitter, "请求失败: " + e.getMessage());
            return emitter;
        }
    }

    /**
     * 非流式聊天测试接口
     * POST /admin/api/chat
     */
    @PostMapping(value = "/chat", produces = "application/json;charset=UTF-8")
    public Object chat(@RequestBody String requestBody) {
        try {
            JsonNode json = objectMapper.readTree(requestBody);
            String modelName = json.has("model") ? json.get("model").asText() : "";
            long apiKeyId = json.has("api_key_id") ? json.get("api_key_id").asLong() : 0;

            if (modelName.isEmpty()) {
                return "{\"error\":{\"message\":\"请选择要测试的模型\",\"type\":\"invalid_request_error\",\"code\":400}}";
            }

            ApiKey apiKey = null;
            if (apiKeyId > 0) {
                apiKey = apiKeyService.getById(apiKeyId);
            }
            if (apiKey == null) {
                List<ApiKey> keys = apiKeyService.listAll();
                for (ApiKey k : keys) {
                    if (k.getEnabled() == 1) {
                        apiKey = k;
                        break;
                    }
                }
            }
            if (apiKey == null) {
                return "{\"error\":{\"message\":\"没有可用的 API Key\",\"type\":\"api_error\",\"code\":401}}";
            }

            ObjectNode openAiRequest = objectMapper.createObjectNode();
            openAiRequest.put("model", modelName);
            openAiRequest.put("stream", false);

            if (json.has("messages")) {
                openAiRequest.set("messages", json.get("messages"));
            } else {
                ArrayNode msgs = openAiRequest.putArray("messages");
                ObjectNode msg = msgs.addObject();
                msg.put("role", "user");
                msg.put("content", json.has("content") ? json.get("content").asText() : "");
            }

            if (json.has("temperature")) openAiRequest.put("temperature", json.get("temperature").asDouble());
            if (json.has("max_tokens")) openAiRequest.put("max_tokens", json.get("max_tokens").asInt());

            String requestJson = objectMapper.writeValueAsString(openAiRequest);
            String authHeader = "Bearer " + apiKey.getKeyValue();

            return relayService.chatCompletions(authHeader, requestJson)
                    .map(response -> response);

        } catch (Exception e) {
            log.error("Playground 非流式聊天请求失败", e);
            return "{\"error\":{\"message\":\"" + e.getMessage() + "\",\"type\":\"api_error\",\"code\":500}}";
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("error", message);
            emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(error), MediaType.parseMediaType("application/json;charset=UTF-8")));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
