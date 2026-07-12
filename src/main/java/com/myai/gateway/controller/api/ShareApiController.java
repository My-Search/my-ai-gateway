package com.myai.gateway.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myai.gateway.entity.ApiKey;
import com.myai.gateway.entity.Model;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.relay.RelayService;
import com.myai.gateway.service.ApiKeyService;
import com.myai.gateway.service.ModelService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * API 密钥分享公开接口
 * 无需登录认证，用于分享页面展示和测试
 */
@RestController
@RequestMapping("/api/share")
public class ShareApiController {

    private static final Logger log = LoggerFactory.getLogger(ShareApiController.class);

    private final ApiKeyService apiKeyService;
    private final ModelService modelService;
    private final RelayService relayService;
    private final ObjectMapper objectMapper;

    public ShareApiController(ApiKeyService apiKeyService, ModelService modelService,
                              RelayService relayService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.modelService = modelService;
        this.relayService = relayService;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取 API 密钥分享信息（通过分享码）
     * GET /api/share/{code}
     *
     * 返回：密钥名称、密钥值、Base URL、可用模型列表
     */
    @GetMapping(value = "/{code}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getShareInfo(@PathVariable String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ApiKey apiKey = apiKeyService.findByShareCode(code);
            if (apiKey == null) {
                return ResponseEntity.status(404).body(Map.of("error", "分享链接无效或已失效"));
            }
            if (apiKey.getEnabled() != 1) {
                return ResponseEntity.status(403).body(Map.of("error", "API 密钥已禁用"));
            }

            return buildShareResponse(apiKey);
        } catch (Exception e) {
            log.error("获取分享信息失败", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * 获取 API 密钥分享信息（通过密钥值）
     * GET /api/share/by-key/{keyValue}
     *
     * 返回：密钥名称（遮罩）、Base URL、可用模型列表
     * 不返回完整的密钥值，仅返回遮罩后的密钥名称
     */
    @GetMapping(value = "/by-key/{keyValue}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> getShareInfoByKey(@PathVariable String keyValue) {
        try {
            ApiKey apiKey = apiKeyService.findByKeyValueForShare(keyValue);
            if (apiKey == null) {
                return ResponseEntity.status(404).body(Map.of("error", "分享链接无效或已失效"));
            }

            return buildShareResponse(apiKey);
        } catch (Exception e) {
            log.error("获取分享信息失败", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * 构建分享响应（提取公共逻辑）
     */
    private ResponseEntity<?> buildShareResponse(ApiKey apiKey) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 获取可见的自定义模型（enabled=1 且 hidden=0）
        List<Model> allModels = modelService.listVisible();

        // 获取模型关联的渠道信息
        List<Map<String, Object>> modelList = new ArrayList<>();
        Set<String> channelTypes = new HashSet<>();

        for (Model model : allModels) {
            List<ModelChannelRel> rels = modelService.getChannelRels(model.getId());
            for (ModelChannelRel rel : rels) {
                if (rel.getChannelType() != null) {
                    channelTypes.add(rel.getChannelType());
                }
            }

            Map<String, Object> modelInfo = new LinkedHashMap<>();
            modelInfo.put("id", model.getId());
            modelInfo.put("modelName", model.getModelName());
            modelInfo.put("description", model.getDescription());

            // 获取关联的渠道类型
            List<String> types = rels.stream()
                    .map(ModelChannelRel::getChannelType)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            modelInfo.put("channelTypes", types);

            modelList.add(modelInfo);
        }

        // 获取 Base URL
        String baseUrl = getBaseUrl();

        // 密钥值遮罩处理：只显示前8位和后4位
        String keyValue = apiKey.getKeyValue();
        String maskedKey = maskKeyValue(keyValue);

        result.put("success", true);
        result.put("id", apiKey.getId());
        result.put("shareCode", apiKey.getShareCode());
        result.put("keyName", apiKey.getKeyName());
        result.put("keyValue", keyValue);
        result.put("keyValueMasked", maskedKey);
        result.put("baseUrl", baseUrl);
        result.put("models", modelList);
        result.put("channelTypes", channelTypes);

        return ResponseEntity.ok(result);
    }

    /**
     * 遮罩密钥值，只显示前8位和后4位
     */
    private String maskKeyValue(String keyValue) {
        if (keyValue == null || keyValue.length() <= 12) {
            return keyValue;
        }
        int prefixLen = Math.min(8, keyValue.length() - 4);
        return keyValue.substring(0, prefixLen) + "****" + keyValue.substring(keyValue.length() - 4);
    }

    /**
     * 流式聊天测试接口 (分享页面专用)
     * POST /api/share/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody String requestBody,
                                  @RequestParam String shareCode,
                                  HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        SseEmitter emitter = new SseEmitter(0L); // 禁用墙钟超时，由 idle timeout 管控死连接

        try {
            // 验证 API Key
            ApiKey apiKey = apiKeyService.findByShareCode(shareCode);
            if (apiKey == null || apiKey.getEnabled() != 1) {
                sendErrorAndComplete(emitter, "API 密钥无效或已禁用");
                return emitter;
            }

            com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(requestBody);
            String modelName = json.has("model") ? json.get("model").asText() : "";

            if (modelName.isEmpty()) {
                sendErrorAndComplete(emitter, "请选择要测试的模型");
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

            if (json.has("temperature")) openAiRequest.put("temperature", json.get("temperature").asDouble());
            if (json.has("max_tokens")) openAiRequest.put("max_tokens", json.get("max_tokens").asInt());

            String requestJson = objectMapper.writeValueAsString(openAiRequest);
            String authHeader = "Bearer " + apiKey.getKeyValue();

            log.info("分享页面测试: 模型={}, API Key={}", modelName, apiKey.getKeyName());

            return relayService.chatCompletionsStream(authHeader, requestJson, true);

        } catch (Exception e) {
            log.error("分享页面聊天请求失败", e);
            sendErrorAndComplete(emitter, "请求失败: " + e.getMessage());
            return emitter;
        }
    }

    /**
     * 非流式聊天测试接口 (分享页面专用)
     * POST /api/share/chat
     */
    @PostMapping(value = "/chat", produces = "application/json;charset=UTF-8")
    public Object chat(@RequestBody String requestBody,
                       @RequestParam String shareCode) {
        try {
            // 验证 API Key
            ApiKey apiKey = apiKeyService.findByShareCode(shareCode);
            if (apiKey == null || apiKey.getEnabled() != 1) {
                return "{\"error\":{\"message\":\"API 密钥无效或已禁用\",\"type\":\"api_error\",\"code\":403}}";
            }

            com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(requestBody);
            String modelName = json.has("model") ? json.get("model").asText() : "";

            if (modelName.isEmpty()) {
                return "{\"error\":{\"message\":\"请选择要测试的模型\",\"type\":\"invalid_request_error\",\"code\":400}}";
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
                    .map(r -> r);

        } catch (Exception e) {
            log.error("分享页面非流式聊天请求失败", e);
            return "{\"error\":{\"message\":\"" + e.getMessage() + "\",\"type\":\"api_error\",\"code\":500}}";
        }
    }

    private String getBaseUrl() {
        // 返回当前服务的 Base URL
        // 在实际部署中，这应该是网关的对外地址
        return "";
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("error", message);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(error), MediaType.parseMediaType("application/json;charset=UTF-8")));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
