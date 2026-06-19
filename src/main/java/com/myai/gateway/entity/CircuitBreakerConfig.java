package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 熔断配置实体
 * 对应 circuit_breaker_configs 表
 *
 * <p>熔断影响范围（scope）只有两个取值：</p>
 * <ul>
 *   <li>{@code channel} — 渠道级（合并原 apikey 级），根据 {@link CircuitBreakerState}
 *       的 {@code (channelId, channelApiKeyId)} 二元组识别</li>
 *   <li>{@code model} — 模型级（单模型熔断）</li>
 * </ul>
 * <p>历史兼容：DB 中残存的 {@code apikey} 值在读取/写入时自动归一化为 {@code channel}。</p>
 */
@TableName("circuit_breaker_configs")
public class CircuitBreakerConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的自定义模型 ID */
    private Long modelId;

    /** 重试次数 */
    private Integer retryCount;

    /** 熔断持续时间（秒） */
    private Integer circuitBreakDuration;

    /**
     * 熔断影响范围：channel（渠道级，合并原 apikey 级）/ model（模型级）
     * DB 残存值 "apikey" 在 getter/setter 中自动归一化为 "channel"
     */
    private String circuitBreakScope;

    /** 是否启用 */
    private Integer enabled;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    // 非数据库字段
    @TableField(exist = false)
    private String modelName;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public Integer getCircuitBreakDuration() { return circuitBreakDuration; }
    public void setCircuitBreakDuration(Integer circuitBreakDuration) { this.circuitBreakDuration = circuitBreakDuration; }

    /**
     * 获取熔断影响范围；DB 中残存的 "apikey" 返回时归一化为 "channel"
     */
    public String getCircuitBreakScope() {
        return "apikey".equals(circuitBreakScope) ? "channel" : circuitBreakScope;
    }

    /**
     * 设置熔断影响范围；若传入 "apikey" 自动归一化为 "channel"
     */
    public void setCircuitBreakScope(String circuitBreakScope) {
        this.circuitBreakScope = "apikey".equals(circuitBreakScope) ? "channel" : circuitBreakScope;
    }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
}
