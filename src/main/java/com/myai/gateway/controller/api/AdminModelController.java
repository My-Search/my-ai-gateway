package com.myai.gateway.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.CircuitBreakerConfig;
import com.myai.gateway.entity.Model;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import com.myai.gateway.service.ChannelApiKeyService;
import com.myai.gateway.service.CircuitBreakerService;
import com.myai.gateway.service.ModelService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理后台「模型」REST API 控制器
 * <p>从原 {@link AdminApiController} 拆分而来（P2 架构：巨型类拆分），
 * 承载入口模型的增删改查、与渠道模型的关联管理、熔断配置与关联模式切换接口。路径前缀与行为与原实现完全一致。</p>
 */
@RestController
@RequestMapping("/admin/api")
public class AdminModelController {

    private static final Logger log = LoggerFactory.getLogger(AdminModelController.class);

    private final ModelService modelService;
    private final StatsService statsService;
    private final RequestLogMapper requestLogMapper;
    private final ChannelApiKeyService channelApiKeyService;
    private final CircuitBreakerService circuitBreakerService;

    public AdminModelController(ModelService modelService,
                                StatsService statsService,
                                RequestLogMapper requestLogMapper,
                                ChannelApiKeyService channelApiKeyService,
                                CircuitBreakerService circuitBreakerService) {
        this.modelService = modelService;
        this.statsService = statsService;
        this.requestLogMapper = requestLogMapper;
        this.channelApiKeyService = channelApiKeyService;
        this.circuitBreakerService = circuitBreakerService;
    }

    @GetMapping(value = "/models", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<Model>> listModels() {
        return ResponseEntity.ok(modelService.listAll());
    }

    /**
     * GET /admin/api/models/stats
     * 获取模型管理页所需的各模型统计与今日趋势数据
     */
    @GetMapping(value = "/models/stats", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> listModelStats(@RequestParam(required = false) String date) {
        List<Model> models = modelService.listAll();
        List<String> modelNames = models.stream()
                .map(Model::getModelName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toList());
        Map<String, Object> stats = statsService.getModelListStats(modelNames, date);
        return ResponseEntity.ok(stats);
    }

    @GetMapping(value = "/models/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getModel(@PathVariable Long id) {
        Model m = modelService.getById(id);
        if (m == null) return ResponseEntity.status(404).body(Map.of("error", "模型不存在"));
        return ResponseEntity.ok(m);
    }

    @PostMapping(value = "/models", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> createModel(@RequestBody Model entity) {
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
                                                            @RequestBody Model entity) {
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
        Model m = modelService.getById(id);
        if (m == null) return ResponseEntity.status(404).body(Map.of("error", "模型不存在"));
        List<ModelChannelRel> rels = modelService.getChannelRels(id);
        List<ChannelModel> availableModels = modelService.getAllAvailableChannelModels();

        // 按 (channelName, channelModelName) 分组计算最近 24h 内每个模型最近 30 条请求的首字节平均响应时间
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
                        .isNotNull(RequestLog::getFirstByteMs)
                        .gt(RequestLog::getFirstByteMs, 0)
                        .ge(RequestLog::getCreatedAt, since)
                        .orderByDesc(RequestLog::getCreatedAt));
        for (RequestLog l : allLogs) {
            String key = l.getChannelName() + "||" + l.getChannelModelName();
            logsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
        }

        // 批量计算全部关联的熔断标记（一次加载渠道模型/熔断记录/API Key，避免逐关联 N+1 查询）
        Map<Long, CircuitBreakerService.RelBrokenMark> brokenMarks = circuitBreakerService.computeRelBrokenMarks(rels);

        // 批量加载渠道模型与启用中的 API Key，用于计算每个关联的"可用密钥"标记
        // （与路由候选构建一致：渠道模型指定了 Key 则仅该 Key 可用，否则渠道下任意启用 Key 即可）
        List<Long> relChannelModelIds = rels.stream()
                .map(ModelChannelRel::getChannelModelId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ChannelModel> channelModelsById = relChannelModelIds.isEmpty()
                ? Map.of()
                : modelService.getChannelModelsByIds(relChannelModelIds).stream()
                        .collect(Collectors.toMap(ChannelModel::getId, cm -> cm, (a, b) -> a));
        List<Long> relChannelIds = rels.stream()
                .map(ModelChannelRel::getChannelId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, List<ChannelApiKey>> enabledKeysByChannel = channelApiKeyService.listEnabledByChannelIds(relChannelIds);

        // 为每个关联模型计算最近 30 条的首字节平均响应时间和样本数，并填充熔断状态标记
        for (ModelChannelRel rel : rels) {
            String channelName = rel.getChannelName();
            String channelModelName = rel.getChannelModelName();
            if (channelName != null && channelModelName != null) {
                String key = channelName + "||" + channelModelName;
                List<RequestLog> modelLogs = logsByKey.getOrDefault(key, new ArrayList<>());
                List<RequestLog> recent30 = modelLogs.stream().limit(30).toList();
                if (!recent30.isEmpty()) {
                    double avg = recent30.stream()
                            .mapToInt(RequestLog::getFirstByteMs)
                            .average()
                            .orElse(0.0);
                    rel.setTtftMs(Math.round(avg));
                    rel.setSampleCount(recent30.size());
                } else {
                    rel.setTtftMs(null);
                    rel.setSampleCount(null);
                }

                // 计算平均生成速度 (tokens/s)：completion_tokens * 1000 / 总响应耗时。
                // 注意分母使用总耗时而非(总耗时-首字节耗时)：非流式请求首字节与末字节同时到达，
                // (response_time_ms - first_byte_ms) 趋近 0 会得到异常大的速度值。
                double speedSum = 0;
                int speedCount = 0;
                for (RequestLog rl : recent30) {
                    Integer rt = rl.getResponseTimeMs();
                    Integer ct = rl.getCompletionTokens();
                    if (rt != null && ct != null && rt > 0 && ct > 0) {
                        double speed = ct * 1000.0 / rt;
                        speedSum += speed;
                        speedCount++;
                    }
                }
                if (speedCount > 0) {
                    rel.setOutputSpeed(Math.round(speedSum / speedCount * 10.0) / 10.0);
                } else {
                    rel.setOutputSpeed(null);
                }
            }
            CircuitBreakerService.RelBrokenMark mark = brokenMarks.get(rel.getId());
            if (mark == null) {
                rel.setCircuitBroken(0);
                rel.setCircuitBrokenScope(null);
                rel.setCircuitBrokenExpireAt(null);
            } else {
                rel.setCircuitBroken(1);
                rel.setCircuitBrokenScope(mark.scope);
                rel.setCircuitBrokenExpireAt(mark.expireAt);
            }
            // 可用密钥标记：渠道下没有启用 Key、或指定 Key 被禁用/不存在时置 0，前端显示"无可用密钥"
            if (rel.getChannelId() != null) {
                ChannelModel relCm = rel.getChannelModelId() != null
                        ? channelModelsById.get(rel.getChannelModelId()) : null;
                Long pinnedKeyId = relCm != null ? relCm.getChannelApiKeyId() : null;
                List<ChannelApiKey> enabledKeys = enabledKeysByChannel.getOrDefault(rel.getChannelId(), List.of());
                boolean usable = pinnedKeyId != null
                        ? enabledKeys.stream().anyMatch(k -> pinnedKeyId.equals(k.getId()))
                        : !enabledKeys.isEmpty();
                rel.setApiKeyAvailable(usable ? 1 : 0);
            }
        }

        // 解析继承源模型名称（仅在 inherit 模式下有意义）
        String inheritFromModelName = null;
        if (Model.RelMode.INHERIT.equals(m.getRelMode())
                && m.getInheritFromModelId() != null) {
            Model source = modelService.getById(m.getInheritFromModelId());
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
     * 手动解除关联的熔断状态（若渠道级熔断存在则一并解除）
     * DELETE /admin/api/models/rels/{relId}/circuit-breaker
     */
    @DeleteMapping(value = "/models/rels/{relId}/circuit-breaker", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> clearRelCircuitBreaker(@PathVariable Long relId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ModelChannelRel rel = modelService.getChannelRelById(relId);
            if (rel == null) {
                return ResponseEntity.status(404).body(Map.of("error", "关联不存在"));
            }
            ChannelModel cm = modelService.getChannelModelById(rel.getChannelModelId());
            Long keyId = cm != null ? cm.getChannelApiKeyId() : null;
            Long channelId = cm != null ? cm.getChannelId() : null;
            int count = circuitBreakerService.manualRecover(rel.getChannelModelId(), channelId, keyId);
            result.put("success", true);
            result.put("recovered", count);
        } catch (Exception e) {
            log.warn("手动解除熔断失败: relId={}", relId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取可作为继承源的入口模型列表（不含当前模型）
     * GET /admin/api/models/{id}/inheritable
     */
    @GetMapping(value = "/models/{id}/inheritable", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> listInheritableModels(@PathVariable Long id) {
        Model m = modelService.getById(id);
        if (m == null) return ResponseEntity.status(404).body(Map.of("error", "模型不存在"));
        List<Model> models = modelService.listInheritableModels(id);
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
            if (mode == null || (!Model.RelMode.SELF_ADD.equals(mode)
                    && !Model.RelMode.INHERIT.equals(mode))) {
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
            Model updated = modelService.setRelMode(id, mode, sourceModelId);
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

    @PostMapping(value = "/models/rels/batch-delete", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> batchRemoveRels(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("relIds");
            List<Long> relIds = rawIds.stream().map(Integer::longValue).collect(Collectors.toList());
            int count = modelService.removeChannelRels(relIds);
            result.put("success", true);
            result.put("count", count);
        } catch (Exception e) {
            log.warn("批量删除模型关联失败: body={}", body, e);
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
        Model m = modelService.getById(id);
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
}
