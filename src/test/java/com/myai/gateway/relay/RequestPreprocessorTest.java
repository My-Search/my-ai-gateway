package com.myai.gateway.relay;

import com.myai.gateway.entity.Model;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.MessageTransformer;
import com.myai.gateway.service.ModelService;
import com.myai.gateway.service.PromptInjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RequestPreprocessor 单元测试
 * 验证强制覆盖思考强度（applyReasoningEffortOverride）逻辑
 */
class RequestPreprocessorTest {

    private MessageTransformer messageTransformer;
    private RouteResolver routeResolver;
    private PromptInjectionService promptInjectionService;
    private ModelService modelService;

    private RequestPreprocessor requestPreprocessor;

    @BeforeEach
    void setUp() {
        messageTransformer = mock(MessageTransformer.class);
        routeResolver = mock(RouteResolver.class);
        promptInjectionService = mock(PromptInjectionService.class);
        modelService = mock(ModelService.class);

        requestPreprocessor = new RequestPreprocessor(
                messageTransformer, routeResolver, promptInjectionService, modelService);
    }

    /**
     * 构建一个带模型名和 reasoningEffort 的 InternalRequest
     */
    private InternalRequest buildRequest(String modelName, String reasoningEffort) {
        InternalRequest req = new InternalRequest();
        req.setModel(modelName);
        req.setReasoningEffort(reasoningEffort);
        return req;
    }

    @Test
    void applyReasoningEffortOverride_flagOn_clearsClientReasoningEffort() {
        // 模型启用了强制覆盖
        Model model = new Model();
        model.setId(1L);
        model.setForceOverrideReasoningEffort(1);

        when(routeResolver.resolveModelId("my-model")).thenReturn(1L);
        when(modelService.getById(1L)).thenReturn(model);

        InternalRequest req = buildRequest("my-model", "high");

        requestPreprocessor.applyReasoningEffortOverride(req);

        assertThat(req.getReasoningEffort()).isNull();
    }

    @Test
    void applyReasoningEffortOverride_flagOff_preservesClientReasoningEffort() {
        // 模型未启用强制覆盖
        Model model = new Model();
        model.setId(1L);
        model.setForceOverrideReasoningEffort(0);

        when(routeResolver.resolveModelId("my-model")).thenReturn(1L);
        when(modelService.getById(1L)).thenReturn(model);

        InternalRequest req = buildRequest("my-model", "high");

        requestPreprocessor.applyReasoningEffortOverride(req);

        assertThat(req.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void applyReasoningEffortOverride_flagOnButNoClientEffort_noChange() {
        // 模型启用了强制覆盖，但客户端未发送 reasoning_effort
        Model model = new Model();
        model.setId(1L);
        model.setForceOverrideReasoningEffort(1);

        when(routeResolver.resolveModelId("my-model")).thenReturn(1L);
        when(modelService.getById(1L)).thenReturn(model);

        InternalRequest req = buildRequest("my-model", null);

        requestPreprocessor.applyReasoningEffortOverride(req);

        // 客户端未发送 reasoning_effort，无需覆盖
        assertThat(req.getReasoningEffort()).isNull();
        // 不应查询模型（短路返回）
        verify(modelService, never()).getById(any());
    }

    @Test
    void applyReasoningEffortOverride_modelNotFound_noChange() {
        // 模型不存在
        when(routeResolver.resolveModelId("unknown")).thenReturn(99L);
        when(modelService.getById(99L)).thenReturn(null);

        InternalRequest req = buildRequest("unknown", "high");

        requestPreprocessor.applyReasoningEffortOverride(req);

        assertThat(req.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void applyReasoningEffortOverride_modelIdNotResolved_noChange() {
        // 模型名无法解析为模型 ID
        when(routeResolver.resolveModelId("unknown")).thenReturn(null);

        InternalRequest req = buildRequest("unknown", "high");

        requestPreprocessor.applyReasoningEffortOverride(req);

        assertThat(req.getReasoningEffort()).isEqualTo("high");
        // 不应查询模型
        verify(modelService, never()).getById(any());
    }

    @Test
    void applyReasoningEffortOverride_flagNull_preservesClientReasoningEffort() {
        // forceOverrideReasoningEffort 为 null（兼容旧数据）
        Model model = new Model();
        model.setId(1L);
        model.setForceOverrideReasoningEffort(null);

        when(routeResolver.resolveModelId("my-model")).thenReturn(1L);
        when(modelService.getById(1L)).thenReturn(model);

        InternalRequest req = buildRequest("my-model", "medium");

        requestPreprocessor.applyReasoningEffortOverride(req);

        assertThat(req.getReasoningEffort()).isEqualTo("medium");
    }
}
