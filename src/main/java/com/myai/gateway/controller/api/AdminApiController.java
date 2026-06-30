package com.myai.gateway.controller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.entity.*;
import com.myai.gateway.mapper.RequestLogMapper;
import com.myai.gateway.relay.RelayService;
import com.myai.gateway.relay.LatencyTracker;
import com.myai.gateway.entity.MultiModalRule;
import com.myai.gateway.service.*;
import com.myai.gateway.config.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * 管理后台 REST API 控制器
 * 为 Vue 前端提供 JSON 数据接口
 */
@RestController
@RequestMapping("/admin/api")
public class AdminApiController {

    private static final Logger log = LoggerFactory.getLogger(AdminApiController.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * SSE 日志推送的共享线程池，避免每个连接创建一个独立线程。
     * <p>
     * 核心线程数 = CPU 数，最大 32 个，空闲 60s 回收。
     * 当超过 32 个并发连接时，后续连接会阻塞等待（SSE 连接数通常远小于此值）。
     * </p>
     */
    private static final ExecutorService ssePollExecutor = new ThreadPoolExecutor(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            32,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "sse-reader");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

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
    private final LatencyTracker latencyTracker;
    private final RequestLogMapper requestLogMapper;
    private final MultiModalRuleService multiModalRuleService;

    public AdminApiController(StatsService statsService, ChannelService channelService,
                              ChannelApiKeyService channelApiKeyService, ModelService modelService,
                              ApiKeyService apiKeyService, RequestLogService requestLogService,
                              LogSseService logSseService, AdminConfigService adminConfigService,
                              RelayService relayService, ObjectMapper objectMapper,
                              JwtTokenProvider jwtTokenProvider,
                              LatencyTracker latencyTracker,
                              RequestLogMapper requestLogMapper,
                              MultiModalRuleService multiModalRuleService) {
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
        this.latencyTracker = latencyTracker;
        this.requestLogMapper = requestLogMapper;
        this.multiModalRuleService = multiModalRuleService;
    }

    @PreDestroy
    public void shutdownSsePool() {
        ssePollExecutor.shutdown();
        try {
            if (!ssePollExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ssePollExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ssePollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
    public ResponseEntity<Map<String, Object>> dashboardStats(
            @RequestParam(defaultValue = "today") String channelRankPeriod,
            @RequestParam(defaultValue = "today") String modelRankPeriod) {
        Map<String, Object> stats = statsService.getDashboardStats(channelRankPeriod, modelRankPeriod);
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

            // 获取渠道的第一个可用 API Key（优先从 channel_api_keys 表获取）
            ChannelApiKey availableKey = channelApiKeyService.getAvailableApiKey(channel.getId());
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
            // 走网关中继测试 — 复用 RelayService 的 WebClient、header 构建和错误处理
            String response = relayService.testChannelModel(channel, testChannelModel, availableKey, message);

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

        // 按 (channelName, channelModelName) 分组计算最近 24h 内每个模型最近 30 条请求的平均响应时间
        // 加时间窗口避免全表扫描，24h 对个人网关足够覆盖低频访问场景
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        Map<String, List<RequestLog>> logsByKey = new HashMap<>();
        List<RequestLog> allLogs = requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .in(RequestLog::getPhase, "success", "fail")
                        .isNotNull(RequestLog::getChannelName)
                        .isNotNull(RequestLog::getChannelModelName)
                        .ne(RequestLog::getChannelName, "")
                        .ne(RequestLog::getChannelModelName, "")
                        .isNotNull(RequestLog::getResponseTimeMs)
                        .gt(RequestLog::getResponseTimeMs, 0)
                        .ge(RequestLog::getCreatedAt, since)
                        .orderByDesc(RequestLog::getCreatedAt));
        for (RequestLog log : allLogs) {
            String key = log.getChannelName() + "||" + log.getChannelModelName();
            logsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(log);
        }

        // 为每个关联模型计算最近 30 条的平均响应时间和样本数
        for (ModelChannelRel rel : rels) {
            String channelName = rel.getChannelName();
            String channelModelName = rel.getChannelModelName();
            if (channelName != null && channelModelName != null) {
                String key = channelName + "||" + channelModelName;
                List<RequestLog> modelLogs = logsByKey.getOrDefault(key, new ArrayList<>());
                List<RequestLog> recent30 = modelLogs.stream().limit(30).toList();
                if (!recent30.isEmpty()) {
                    double avg = recent30.stream()
                            .mapToInt(RequestLog::getResponseTimeMs)
                            .average()
                            .orElse(0.0);
                    rel.setTtftMs(Math.round(avg));
                    rel.setSampleCount(recent30.size());
                } else {
                    rel.setTtftMs(null);
                    rel.setSampleCount(null);
                }
            }
        }

        // 解析继承源模型名称（仅在 inherit 模式下有意义）
        String inheritFromModelName = null;
        if (com.myai.gateway.entity.Model.RelMode.INHERIT.equals(m.getRelMode())
                && m.getInheritFromModelId() != null) {
            com.myai.gateway.entity.Model source = modelService.getById(m.getInheritFromModelId());
            if (source != null) {
                inheritFromModelName = source.getModelName();
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", m);
        result.put("rels", rels);
        result.put("availableModels", availableModels);
        result.put("inheritFromModelName", inheritFromModelName);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取可作为继承源的入口模型列表（不含当前模型）
     * GET /admin/api/models/{id}/inheritable
     */
    @GetMapping(value = "/models/{id}/inheritable", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> listInheritableModels(@PathVariable Long id) {
        com.myai.gateway.entity.Model m = modelService.getById(id);
        if (m == null) return ResponseEntity.status(404).body(Map.of("error", "模型不存在"));
        List<com.myai.gateway.entity.Model> models = modelService.listInheritableModels(id);
        return ResponseEntity.ok(models);
    }

    /**
     * 切换模型的关联模式
     * PUT /admin/api/models/{id}/rel-mode
     * 请求体: { "mode": "self_add" | "inherit", "sourceModelId": 1 }
     *   - mode='inherit' 时 sourceModelId 必填
     *   - mode='self_add' 时 sourceModelId 忽略
     */
    @PutMapping(value = "/models/{id}/rel-mode", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> setRelMode(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String mode = (String) body.get("mode");
            if (mode == null || (!com.myai.gateway.entity.Model.RelMode.SELF_ADD.equals(mode)
                    && !com.myai.gateway.entity.Model.RelMode.INHERIT.equals(mode))) {
                result.put("success", false);
                result.put("error", "mode 必须为 self_add 或 inherit");
                return ResponseEntity.ok(result);
            }
            Long sourceModelId = null;
            Object raw = body.get("sourceModelId");
            if (raw instanceof Number) {
                sourceModelId = ((Number) raw).longValue();
            } else if (raw != null) {
                sourceModelId = Long.parseLong(raw.toString());
            }
            com.myai.gateway.entity.Model updated = modelService.setRelMode(id, mode, sourceModelId);
            result.put("success", true);
            result.put("model", updated);
        } catch (Exception e) {
            log.warn("切换模型关联模式失败: id={}, body={}", id, body, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
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

    @PutMapping(value = "/models/rels/sort", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> batchUpdateRelSort(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("sortedRelIds");
            List<Long> sortedRelIds = rawIds.stream().map(Integer::longValue).collect(Collectors.toList());
            modelService.updateChannelRelSortOrders(sortedRelIds);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 更新关联的默认思考强度（reasoning_effort）
     * PUT /admin/api/models/rels/{relId}/reasoning-effort
     * 请求体: { "reasoningEffort": "high" }（传空字符串或 null 清除默认值）
     */
    @PutMapping(value = "/models/rels/{relId}/reasoning-effort", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> updateRelReasoningEffort(@PathVariable Long relId,
                                                                         @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String reasoningEffort = body.get("reasoningEffort") != null
                    ? body.get("reasoningEffort").toString().trim()
                    : null;
            if (reasoningEffort != null && reasoningEffort.isEmpty()) {
                reasoningEffort = null;
            }
            modelService.updateChannelRelReasoningEffort(relId, reasoningEffort);
            result.put("success", true);
        } catch (Exception e) {
            log.warn("更新关联推理强度失败: relId={}, body={}", relId, body, e);
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
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) Long gatewayApiKeyId,
            @RequestParam(required = false) String apiKeyName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        // 获取总数（带过滤）
        boolean hasFilters = (modelName != null && !modelName.isEmpty())
                || gatewayApiKeyId != null
                || (apiKeyName != null && !apiKeyName.isEmpty())
                || startTime != null
                || endTime != null;
        long totalTraces;
        List<RequestLog> logs;
        if (hasFilters) {
            totalTraces = requestLogService.getFilteredTraceCount(modelName, gatewayApiKeyId, apiKeyName, startTime, endTime);
            logs = requestLogService.getFilteredLogsByPage(offset, limit, modelName, gatewayApiKeyId, apiKeyName, startTime, endTime);
        } else {
            totalTraces = requestLogService.getTraceCount();
            logs = requestLogService.getLogsByPage(offset, limit);
        }
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

            // 单次遍历替代 5 次 stream()，O(n) 代替 O(5n)
            int retryCount = 0, successCount = 0, failCount = 0;
            String traceModelName = null;
            long totalTime = 0;
            for (RequestLog l : sortedLogs) {
                String phase = l.getPhase();
                if ("retry".equals(phase)) retryCount++;
                else if ("success".equals(phase)) successCount++;
                else if ("fail".equals(phase)) failCount++;
                if (traceModelName == null && l.getModelName() != null) {
                    traceModelName = l.getModelName();
                }
                if (("success".equals(phase) || "fail".equals(phase))
                        && l.getResponseTimeMs() != null) {
                    totalTime += l.getResponseTimeMs();
                }
            }
            trace.put("retryCount", retryCount);
            trace.put("successCount", successCount);
            trace.put("failCount", failCount);
            trace.put("modelName", traceModelName != null ? traceModelName : "");
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
     * "请求日志"页面顶部"使用历史"堆叠柱状图数据。
     * <p>
     * 按 modelType 分支聚合指定月份的 token 用量，返回 days/models/values 矩阵。
     * modelType=entry 时按 model_name（入口模型）聚合；modelType=channel 时按 channel_model_name（渠道模型）聚合。
     * 仅统计成功请求的 token（与本系统其他用量统计保持一致口径）。
     * </p>
     *
     * @param year            目标年份（默认当前年）
     * @param month           目标月份 1-12（默认当前月）
     * @param modelType       模型类型：entry（入口模型，默认）或 channel（渠道模型）
     * @param modelName       可选：按入口模型名过滤
     * @param gatewayApiKeyId 可选：按网关 API Key 主键过滤（与 apiKeyName 同时存在时优先使用）
     * @param apiKeyName      可选：按 API Key 名过滤（兼容旧调用，对应渠道 API Key 名）
     */
    @GetMapping(value = "/logs/usage-chart", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> logUsageChart(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false, defaultValue = "entry") String modelType,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) Long gatewayApiKeyId,
            @RequestParam(required = false) String apiKeyName) {
        java.time.LocalDate now = java.time.LocalDate.now();
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        if (m < 1 || m > 12) {
            return ResponseEntity.ok(Map.of("error", "month 必须在 1-12 之间"));
        }
        return ResponseEntity.ok(statsService.getLogUsageChart(y, m, modelType, modelName, gatewayApiKeyId, apiKeyName));
    }

    /**
     * 日志实时推送 SSE 端点
     * GET /admin/api/logs/stream
     * <p>
     * 采用 CPA 模式：每个 SSE 连接分配一个独立队列，由专用分发线程统一写入，
     * 确保生产线程（AsyncLogWriter）零阻塞。前端通过 EventSource 连接后，
     * 服务端主动推送 event: log / data: {...RequestLog JSON...}
     * </p>
     *
     * <pre>
     * AsyncLogWriter → centralQueue.offer()       ← 一次入队，零等待
     *                        ↓
     *                  分发线程 (批量 500 条)        ← 单线程批量分发
     *                        ↓
     *                  per-subscriber 独立队列       ← 每人一个队列，互不干扰
     *                        ↓
     *                  SSE 轮询线程 → emitter.send()  ← 前端可见
     * </pre>
     */
    @GetMapping(value = "/logs/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamLogs(HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        // 不设超时，由前端断开时清理
        SseEmitter emitter = new SseEmitter(0L);

        // 创建订阅者独立队列
        LogSseService.SubscriberQueue sq = logSseService.subscribe();

        // 从共享线程池获取线程轮询订阅者队列，推送 SSE 事件
        // 使用共享线程池避免每个连接创建一个独立线程
        ssePollExecutor.execute(() -> {
            try {
                while (true) {
                    RequestLog record = sq.poll(5, TimeUnit.SECONDS);
                    if (record == null) {
                        continue;
                    }
                    String json = objectMapper.writeValueAsString(record);
                    emitter.send(SseEmitter.event()
                            .name("log")
                            .data(json, MediaType.APPLICATION_JSON));
                }
            } catch (IOException e) {
                // 连接已关闭，正常结束
            } catch (Exception e) {
                if (!"Broken pipe".equals(e.getMessage())) {
                    log.debug("SSE 推送异常（连接可能已断开）: {}", e.getMessage());
                }
            } finally {
                logSseService.unsubscribe(sq);
            }
        });

        // 连接断开/超时/异常时清理
        emitter.onCompletion(() -> logSseService.unsubscribe(sq));
        emitter.onTimeout(() -> logSseService.unsubscribe(sq));
        emitter.onError(e -> logSseService.unsubscribe(sq));

        return emitter;
    }

    /**
     * 按日志 ID 获取原始请求数据（requestHeaders / requestBody）
     * <p>
     * 列表接口 {@code GET /logs} 已排除大字段，此接口用于点击"查看原始请求"时按需加载。
     * 如果数据已被定时清理（request_body_ttl_hours），则返回 null。
     * </p>
     *
     * @param logId 日志主键
     * @return { requestHeaders, requestBody }，数据不存在或已过期时两个字段均为 null
     */
    @GetMapping(value = "/logs/{logId}/request-data", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> getRequestData(@PathVariable Long logId) {
        RequestLog logEntry = requestLogService.getRequestDataByLogId(logId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (logEntry != null) {
            result.put("requestHeaders", logEntry.getRequestHeaders());
            result.put("requestBody", logEntry.getRequestBody());
        } else {
            result.put("requestHeaders", null);
            result.put("requestBody", null);
        }
        return ResponseEntity.ok(result);
    }

    // ==================== System Config ====================

    /**
     * 获取系统配置
     * <p>
     * 返回所有系统级别配置项，包括日志管理、定时任务等。
     * </p>
     */
    @GetMapping(value = "/config/system", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> getSystemConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", adminConfigService.getSystemConfig());
        return ResponseEntity.ok(result);
    }

    /**
     * 更新系统配置
     * <p>
     * 批量更新系统配置项，仅更新传入的 key。
     * </p>
     * 请求体示例：
     * <pre>
     * {
     *   "log_retention_days": "30",
     *   "log_cleanup_enabled": "1"
     * }
     * </pre>
     */
    @PutMapping(value = "/config/system", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> updateSystemConfig(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 校验参数
            if (body.containsKey(AdminConfigService.KEY_LOG_RETENTION_DAYS)) {
                String days = body.get(AdminConfigService.KEY_LOG_RETENTION_DAYS);
                int val = Integer.parseInt(days);
                if (val < 1 || val > 365) {
                    result.put("success", false);
                    result.put("error", "日志保留天数必须在 1-365 之间");
                    return ResponseEntity.ok(result);
                }
            }
            if (body.containsKey(AdminConfigService.KEY_LOG_CLEANUP_ENABLED)) {
                String val = body.get(AdminConfigService.KEY_LOG_CLEANUP_ENABLED);
                if (!"0".equals(val) && !"1".equals(val)) {
                    result.put("success", false);
                    result.put("error", "清理开关值无效，必须为 0 或 1");
                    return ResponseEntity.ok(result);
                }
            }
            if (body.containsKey(AdminConfigService.KEY_REQUEST_BODY_TTL_HOURS)) {
                String val = body.get(AdminConfigService.KEY_REQUEST_BODY_TTL_HOURS);
                try {
                    int hours = Integer.parseInt(val);
                    if (hours < 0 || hours > 8760) {
                        result.put("success", false);
                        result.put("error", "原始请求保留时长必须在 0-8760 小时之间（0=永久保留）");
                        return ResponseEntity.ok(result);
                    }
                } catch (NumberFormatException e) {
                    result.put("success", false);
                    result.put("error", "原始请求保留时长必须为有效数字");
                    return ResponseEntity.ok(result);
                }
            }
            if (body.containsKey(AdminConfigService.KEY_RETRY_FAIL_TTL_HOURS)) {
                String val = body.get(AdminConfigService.KEY_RETRY_FAIL_TTL_HOURS);
                try {
                    int hours = Integer.parseInt(val);
                    if (hours < 0 || hours > 8760) {
                        result.put("success", false);
                        result.put("error", "重试/失败请求保留时长必须在0-8760小时之间（0=永久保留）");
                        return ResponseEntity.ok(result);
                    }
                } catch (NumberFormatException e) {
                    result.put("success", false);
                    result.put("error", "重试/失败请求保留时长必须为有效数字");
                    return ResponseEntity.ok(result);
                }
            }

            adminConfigService.updateSystemConfig(body);
            result.put("success", true);
            result.put("data", adminConfigService.getSystemConfig());
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("error", "日志保留天数必须为有效数字");
        } catch (Exception e) {
            log.warn("更新系统配置失败", e);
            result.put("success", false);
            result.put("error", "更新失败：" + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    // ==================== File Upload ====================

    /**
     * 上传文件（图片），返回可访问的 URL
     * POST /admin/api/upload
     * 请求：multipart/form-data，字段名 file
     * 返回：{ success: true, url: "/uploads/xxx.jpg", originalName: "xxx.jpg" }
     */
    /** 允许上传的图片文件扩展名 */
    private static final java.util.Set<String> ALLOWED_IMAGE_EXTENSIONS = java.util.Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg"
    );

    @PostMapping(value = "/upload", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (file.isEmpty()) {
                result.put("success", false);
                result.put("error", "文件为空");
                return ResponseEntity.ok(result);
            }
            // 校验文件类型：只允许图片
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                result.put("success", false);
                result.put("error", "只允许上传图片文件");
                return ResponseEntity.ok(result);
            }
            // 校验文件扩展名白名单
            String originalName = file.getOriginalFilename();
            if (originalName != null) {
                String ext = "";
                int dotIdx = originalName.lastIndexOf(".");
                if (dotIdx >= 0) {
                    ext = originalName.substring(dotIdx).toLowerCase();
                }
                if (!ALLOWED_IMAGE_EXTENSIONS.contains(ext)) {
                    result.put("success", false);
                    result.put("error", "不允许上传该文件类型（仅支持: jpg/png/gif/webp/bmp/svg）");
                    return ResponseEntity.ok(result);
                }
            }
            // 生成存储路径：data/uploads/yyyy/MM/dd/uuid.ext
            String ext = "";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf("."));
            }
            String datePath = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String uuid = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String relativePath = datePath + "/" + uuid + ext;
            java.io.File dest = new java.io.File("data/uploads/" + relativePath);
            dest.getParentFile().mkdirs();
            file.transferTo(dest);
            result.put("success", true);
            result.put("url", "/uploads/" + relativePath);
            result.put("originalName", originalName);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            result.put("success", false);
            result.put("error", "上传失败: " + e.getMessage());
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

    // ==================== Multi-Modal Rules ====================

    /**
     * 获取所有多模态规则列表
     * GET /admin/api/multimodal-rules
     */
    @GetMapping(value = "/multimodal-rules", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<MultiModalRule>> listMultiModalRules() {
        return ResponseEntity.ok(multiModalRuleService.listAll());
    }

    /**
     * 创建多模态规则
     * POST /admin/api/multimodal-rules
     * 请求体: { "pattern": ".*vision.*", "appendType": "image" }
     */
    @PostMapping(value = "/multimodal-rules", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> createMultiModalRule(@RequestBody MultiModalRule rule) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            MultiModalRule created = multiModalRuleService.create(rule);
            result.put("success", true);
            result.put("data", created);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 更新多模态规则
     * PUT /admin/api/multimodal-rules/{id}
     */
    @PutMapping(value = "/multimodal-rules/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> updateMultiModalRule(@PathVariable Long id,
                                                                     @RequestBody MultiModalRule rule) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            rule.setId(id);
            MultiModalRule updated = multiModalRuleService.update(rule);
            result.put("success", true);
            result.put("data", updated);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 删除多模态规则
     * DELETE /admin/api/multimodal-rules/{id}
     */
    @DeleteMapping(value = "/multimodal-rules/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> deleteMultiModalRule(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            multiModalRuleService.delete(id);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 测试正则匹配
     * POST /admin/api/multimodal-rules/test
     * 请求体: { "pattern": ".*vision.*", "testData": ["gpt-4-vision", "gpt-4"] }
     */
    @PostMapping(value = "/multimodal-rules/test", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> testMultiModalRule(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String pattern = (String) body.get("pattern");
            @SuppressWarnings("unchecked")
            List<String> testData = (List<String>) body.get("testData");
            if (pattern == null || pattern.isEmpty()) {
                result.put("success", false);
                result.put("error", "请输入正则表达式");
                return ResponseEntity.ok(result);
            }
            if (testData == null || testData.isEmpty()) {
                result.put("success", false);
                result.put("error", "请添加测试数据");
                return ResponseEntity.ok(result);
            }
            List<Map<String, Object>> matches = multiModalRuleService.testPattern(pattern, testData);
            result.put("success", true);
            result.put("data", matches);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
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
        // SSE 响应不能被任何中间层或客户端缓冲，必须即时推送
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        // 设置极小的响应缓冲区（1KB），确保每次 flush 立即将数据推送到 TCP 栈
        response.setBufferSize(1024);

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
