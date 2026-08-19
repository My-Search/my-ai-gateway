package com.myai.gateway.controller.api;

import com.myai.gateway.entity.ApiKey;
import com.myai.gateway.service.ApiKeyService;
import com.myai.gateway.service.StatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台「API Key」REST API 控制器
 * <p>从原 {@link AdminApiController} 拆分而来（P2 架构：巨型类拆分），
 * 承载网关访问 Key 的增删改查、分享开关与用量统计接口。路径前缀与行为与原实现完全一致。</p>
 */
@RestController
@RequestMapping("/admin/api")
public class AdminApiKeyController {

    private final ApiKeyService apiKeyService;
    private final StatsService statsService;

    public AdminApiKeyController(ApiKeyService apiKeyService,
                                 StatsService statsService) {
        this.apiKeyService = apiKeyService;
        this.statsService = statsService;
    }

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

    /**
     * 获取所有 API Key 的日/周/月 Token 用量与请求次数统计
     */
    @GetMapping(value = "/api-keys/usage-stats", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<Long, Map<String, Map<String, Object>>>> getApiKeyUsageStats() {
        return ResponseEntity.ok(statsService.getApiKeyUsageStats());
    }
}
