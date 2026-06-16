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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getApiKeyName() { return apiKeyName; }
    public void setApiKeyName(String apiKeyName) { this.apiKeyName = apiKeyName; }

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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
