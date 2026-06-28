package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 请求日志实体
 */
@TableName("request_logs")
public class RequestLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 追踪 ID */
    private String traceId;

    /** API 密钥名称 */
    private String apiKeyName;

    /**
     * 网关 API Key 主键（{@code api_keys.id}）
     * <p>
     * 用于"使用历史"图表和"请求日志"按网关 API Key 过滤：按 id 精确匹配，
     * 避免网关 Key 名称与渠道 Key 名称同名时筛选错乱。
     * 历史数据该字段为 NULL。
     * </p>
     */
    private Long gatewayApiKeyId;

    /** 请求的模型名（自定义模型名） */
    private String modelName;

    /** 实际调用的渠道模型名 */
    private String channelModelName;

    /** 使用的渠道名 */
    private String channelName;

    /** 阶段：start / retry / reroute / success / fail */
    private String phase;

    /** 状态：pending / success / error */
    private String status;

    /** 日志消息 */
    private String message;

    /** 重试索引（0=首次请求，>0=第N次重试，用于日志缩进显示） */
    private Integer retryIndex;

    /** 响应时间（毫秒） */
    private Integer responseTimeMs;

    /** 输入 token 数 */
    private Integer promptTokens;

    /** 输出 token 数 */
    private Integer completionTokens;

    /** 总 token 数 */
    private Integer totalTokens;

    /** 原始请求头（JSON 格式） */
    private String requestHeaders;

    /** 原始请求体 */
    private String requestBody;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getApiKeyName() { return apiKeyName; }
    public void setApiKeyName(String apiKeyName) { this.apiKeyName = apiKeyName; }

    public Long getGatewayApiKeyId() { return gatewayApiKeyId; }
    public void setGatewayApiKeyId(Long gatewayApiKeyId) { this.gatewayApiKeyId = gatewayApiKeyId; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getChannelModelName() { return channelModelName; }
    public void setChannelModelName(String channelModelName) { this.channelModelName = channelModelName; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getRetryIndex() { return retryIndex; }
    public void setRetryIndex(Integer retryIndex) { this.retryIndex = retryIndex; }

    public Integer getResponseTimeMs() { return responseTimeMs; }
    public void setResponseTimeMs(Integer responseTimeMs) { this.responseTimeMs = responseTimeMs; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public String getRequestHeaders() { return requestHeaders; }
    public void setRequestHeaders(String requestHeaders) { this.requestHeaders = requestHeaders; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
