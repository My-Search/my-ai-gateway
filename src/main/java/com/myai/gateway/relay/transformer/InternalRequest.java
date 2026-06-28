package com.myai.gateway.relay.transformer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内部统一请求模型
 * 屏蔽 OpenAI 和 Anthropic 的请求体差异，提供统一的中间表示
 */
public class InternalRequest {

    /** 模型名 */
    private String model;

    /** 消息列表 */
    private List<InternalMessage> messages;

    /** System prompt（Anthropic 顶层字段，OpenAI 也在 messages 中） */
    private String systemPrompt;

    /** 最大输出 token 数 */
    private Integer maxTokens;

    /** 温度 */
    private Double temperature;

    /** Top P */
    private Double topP;

    /** 是否流式 */
    private boolean stream;

    /** 停止序列 */
    private List<String> stop;

    /**  Tools 定义 */
    private List<Map<String, Object>> tools;

    /** Tool choice */
    private Object toolChoice;

    /** 原始请求 JSON（用于透传场景） */
    private JsonNode originalRequestJson;

    /** 客户端 API 格式: "openai" 或 "anthropic" */
    private String clientApiFormat;

    /** 额外参数（转换时无法映射到标准字段的参数） */
    private Map<String, Object> extraParams;

    /** 是否由中途失败的流式内容拼接而成的上下文重试请求（用于日志标记） */
    private boolean contextRetry;

    /** 思考强度（reasoning_effort），如 low/medium/high，null 表示不设置 */
    private String reasoningEffort;

    /**
     * 已检测到的请求中媒体类型缓存
     * 由 RelayService.detectRequestMediaTypes() 填充，避免重复遍历 messages
     */
    private Set<String> detectedMediaTypes;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<InternalMessage> getMessages() { return messages; }
    public void setMessages(List<InternalMessage> messages) { this.messages = messages; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }

    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }

    public List<String> getStop() { return stop; }
    public void setStop(List<String> stop) { this.stop = stop; }

    public List<Map<String, Object>> getTools() { return tools; }
    public void setTools(List<Map<String, Object>> tools) { this.tools = tools; }

    public Object getToolChoice() { return toolChoice; }
    public void setToolChoice(Object toolChoice) { this.toolChoice = toolChoice; }

    public JsonNode getOriginalRequestJson() { return originalRequestJson; }
    public void setOriginalRequestJson(JsonNode originalRequestJson) { this.originalRequestJson = originalRequestJson; }

    public String getClientApiFormat() { return clientApiFormat; }
    public void setClientApiFormat(String clientApiFormat) { this.clientApiFormat = clientApiFormat; }

    public Map<String, Object> getExtraParams() { return extraParams; }
    public void setExtraParams(Map<String, Object> extraParams) { this.extraParams = extraParams; }

    public boolean isContextRetry() { return contextRetry; }
    public void setContextRetry(boolean contextRetry) { this.contextRetry = contextRetry; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

    public Set<String> getDetectedMediaTypes() { return detectedMediaTypes; }
    public void setDetectedMediaTypes(Set<String> detectedMediaTypes) { this.detectedMediaTypes = detectedMediaTypes; }
}
