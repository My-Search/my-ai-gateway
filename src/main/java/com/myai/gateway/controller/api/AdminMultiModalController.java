package com.myai.gateway.controller.api;

import com.myai.gateway.entity.MultiModalRule;
import com.myai.gateway.entity.PromptInjection;
import com.myai.gateway.service.MultiModalRuleService;
import com.myai.gateway.service.PromptInjectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 - 多模态规则与 Prompt 注入规则控制器
 * 从 AdminApiController 拆出（P2 架构：巨型类拆分）
 * 保持 /admin/api 前缀与原有 handler 逐字不变
 */
@RestController
@RequestMapping("/admin/api")
public class AdminMultiModalController {

    private final MultiModalRuleService multiModalRuleService;
    private final PromptInjectionService promptInjectionService;

    public AdminMultiModalController(MultiModalRuleService multiModalRuleService,
                                     PromptInjectionService promptInjectionService) {
        this.multiModalRuleService = multiModalRuleService;
        this.promptInjectionService = promptInjectionService;
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

    // ==================== Prompt Injections ====================

    /**
     * 获取指定模型的所有 Prompt 注入规则
     * GET /admin/api/models/{modelId}/prompt-injections
     */
    @GetMapping(value = "/models/{modelId}/prompt-injections", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<PromptInjection>> listPromptInjections(@PathVariable Long modelId) {
        return ResponseEntity.ok(promptInjectionService.listByModelId(modelId));
    }

    /**
     * 获取单条 Prompt 注入规则
     * GET /admin/api/prompt-injections/{id}
     */
    @GetMapping(value = "/prompt-injections/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getPromptInjection(@PathVariable Long id) {
        PromptInjection rule = promptInjectionService.getById(id);
        if (rule == null) return ResponseEntity.status(404).body(Map.of("error", "规则不存在"));
        return ResponseEntity.ok(rule);
    }

    /**
     * 创建 Prompt 注入规则
     * POST /admin/api/models/{modelId}/prompt-injections
     */
    @PostMapping(value = "/models/{modelId}/prompt-injections", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> createPromptInjection(@PathVariable Long modelId,
                                                                      @RequestBody PromptInjection rule) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            rule.setModelId(modelId);
            PromptInjection created = promptInjectionService.create(rule);
            result.put("success", true);
            result.put("data", created);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 更新 Prompt 注入规则
     * PUT /admin/api/prompt-injections/{id}
     */
    @PutMapping(value = "/prompt-injections/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> updatePromptInjection(@PathVariable Long id,
                                                                      @RequestBody PromptInjection rule) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            rule.setId(id);
            PromptInjection updated = promptInjectionService.update(rule);
            result.put("success", true);
            result.put("data", updated);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 删除 Prompt 注入规则
     * DELETE /admin/api/prompt-injections/{id}
     */
    @DeleteMapping(value = "/prompt-injections/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> deletePromptInjection(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            promptInjectionService.delete(id);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }
}
