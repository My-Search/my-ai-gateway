package com.myai.gateway.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.ApiKey;
import com.myai.gateway.entity.Model;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.relay.RelayService;
import com.myai.gateway.service.ApiKeyService;
import com.myai.gateway.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * ShareApiController 单元测试
 * <p>
 * 验证密钥分享 API 无需登录即可公开访问，且返回数据格式正确。
 * 测试范围：getShareInfo（通过分享码）、getShareInfoByKey（通过密钥值）。
 */
class ShareApiControllerTest {

    private ShareApiController controller;
    private ApiKeyService apiKeyService;
    private ModelService modelService;
    private RelayService relayService;
    private ObjectMapper objectMapper;

    private ApiKey validKey;
    private ApiKey disabledKey;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        modelService = mock(ModelService.class);
        relayService = mock(RelayService.class);
        objectMapper = new ObjectMapper();

        controller = new ShareApiController(apiKeyService, modelService, relayService, objectMapper);

        // 准备测试数据
        validKey = new ApiKey("测试密钥", "sk-myai-test-key-value-12345678");
        validKey.setId(1L);
        validKey.setShareCode("valid-share-code-123");
        validKey.setEnabled(1);
        validKey.setShared(1);

        disabledKey = new ApiKey("已禁用密钥", "sk-myai-disabled-key");
        disabledKey.setId(2L);
        disabledKey.setShareCode("disabled-share-code");
        disabledKey.setEnabled(0);
        disabledKey.setShared(1);
    }

    // ==================== getShareInfo ====================

    @Test
    void getShareInfo_withValidCode_returns200AndShareData() {
        when(apiKeyService.findByShareCode("valid-share-code-123")).thenReturn(validKey);
        when(modelService.listAll()).thenReturn(List.of());
        when(modelService.getChannelRels(anyLong())).thenReturn(List.of());

        ResponseEntity<?> response = controller.getShareInfo("valid-share-code-123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("keyName")).isEqualTo("测试密钥");
        assertThat(body.get("keyValue")).isEqualTo("sk-myai-test-key-value-12345678");
        assertThat(body.get("keyValueMasked")).isNotNull();
        assertThat(body.get("shareCode")).isEqualTo("valid-share-code-123");
        assertThat(body.get("models")).isInstanceOf(List.class);
    }

    @Test
    void getShareInfo_withInvalidCode_returns404() {
        when(apiKeyService.findByShareCode("invalid-code")).thenReturn(null);

        ResponseEntity<?> response = controller.getShareInfo("invalid-code");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isNotNull();
    }

    @Test
    void getShareInfo_withDisabledKey_returns403() {
        when(apiKeyService.findByShareCode("disabled-share-code")).thenReturn(disabledKey);

        ResponseEntity<?> response = controller.getShareInfo("disabled-share-code");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isNotNull();
    }

    @Test
    void getShareInfo_masksKeyValue() {
        when(apiKeyService.findByShareCode("valid-share-code-123")).thenReturn(validKey);
        when(modelService.listAll()).thenReturn(List.of());
        when(modelService.getChannelRels(anyLong())).thenReturn(List.of());

        ResponseEntity<?> response = controller.getShareInfo("valid-share-code-123");

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();

        // 原始密钥值应返回，同时返回遮罩值
        String masked = (String) body.get("keyValueMasked");
        assertThat(masked).isNotNull();
        // 遮罩值应包含 **** 且长度与原始值不同
        assertThat(masked).contains("****");
        // 应展示前8位和后4位
        assertThat(masked).startsWith("sk-myai-");
        assertThat(masked).endsWith("5678");
    }

    @Test
    void getShareInfo_returnsModelList() {
        Model model1 = new Model("gpt-4", "GPT-4 model", "failover");
        model1.setId(10L);
        model1.setEnabled(1);
        Model model2 = new Model("claude-3", "Claude 3 model", "failover");
        model2.setId(20L);
        model2.setEnabled(1);

        when(apiKeyService.findByShareCode("valid-share-code-123")).thenReturn(validKey);
        when(modelService.listAll()).thenReturn(List.of(model1, model2));
        when(modelService.getChannelRels(10L)).thenReturn(List.of(createRel("openai")));
        when(modelService.getChannelRels(20L)).thenReturn(List.of(createRel("anthropic")));

        ResponseEntity<?> response = controller.getShareInfo("valid-share-code-123");

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();

        List<Map<String, Object>> models = (List<Map<String, Object>>) body.get("models");
        assertThat(models).hasSize(2);
        assertThat(models.get(0).get("modelName")).isEqualTo("gpt-4");
        assertThat(models.get(1).get("modelName")).isEqualTo("claude-3");
    }

    @Test
    void getShareInfo_skipsDisabledModels() {
        Model enabledModel = new Model("gpt-4", "Enabled", "failover");
        enabledModel.setId(10L);
        enabledModel.setEnabled(1);
        Model disabledModel = new Model("disabled-model", "Disabled", "failover");
        disabledModel.setId(20L);
        disabledModel.setEnabled(0);

        when(apiKeyService.findByShareCode("valid-share-code-123")).thenReturn(validKey);
        when(modelService.listAll()).thenReturn(List.of(enabledModel, disabledModel));
        when(modelService.getChannelRels(10L)).thenReturn(List.of(createRel("openai")));

        ResponseEntity<?> response = controller.getShareInfo("valid-share-code-123");

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        List<Map<String, Object>> models = (List<Map<String, Object>>) body.get("models");
        assertThat(models).hasSize(1);
        assertThat(models.get(0).get("modelName")).isEqualTo("gpt-4");
    }

    // ==================== getShareInfoByKey ====================

    @Test
    void getShareInfoByKey_withValidKey_returns200() {
        when(apiKeyService.findByKeyValueForShare("sk-myai-test-key-value-12345678")).thenReturn(validKey);
        when(modelService.listAll()).thenReturn(List.of());

        ResponseEntity<?> response = controller.getShareInfoByKey("sk-myai-test-key-value-12345678");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(true);
    }

    @Test
    void getShareInfoByKey_withInvalidKey_returns404() {
        when(apiKeyService.findByKeyValueForShare("invalid-key")).thenReturn(null);

        ResponseEntity<?> response = controller.getShareInfoByKey("invalid-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isNotNull();
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个 ModelChannelRel 对象
     */
    private ModelChannelRel createRel(String channelType) {
        ModelChannelRel rel = new ModelChannelRel();
        rel.setId(1L);
        rel.setChannelType(channelType);
        rel.setEnabled(1);
        return rel;
    }
}
