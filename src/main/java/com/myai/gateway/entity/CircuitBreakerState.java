package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 熔断状态实体
 * 记录熔断器当前的开闭状态
 *
 * <p>采用两级模型而非三级：
 * <ul>
 *   <li><b>渠道级（合并）</b> — 由 {@code (channelId, channelApiKeyId)} 二元组标识，
 *       包括全渠道熔断（channelApiKeyId IS NULL）和按 API Key 熔断（channelApiKeyId 非空），
 *       两者均以 {@code channelModelId IS NULL} 区分于模型级。</li>
 *   <li><b>模型级</b> — 由 {@code (channelId, channelApiKeyId, channelModelId)} 三个字段联合标识，
 *       新记录三个字段均非空；旧记录可能仅有 channelModelId。</li>
 * </ul>
 *
 * <p>历史兼容：旧渠道级记录 {@code (channelId=X, channelApiKeyId=NULL, channelModelId=NULL)}、
 * 旧 API Key 级记录 {@code (channelId=NULL, channelApiKeyId=Y, channelModelId=NULL)} 仍然留存，
 * 可通过过时方法查询，新方法不覆盖这些记录。</p>
 */
@TableName("circuit_breaker_states")
public class CircuitBreakerState {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 渠道 ID（渠道级熔断时使用） */
    private Long channelId;

    /** 渠道 API Key ID（API Key 级熔断时使用） */
    private Long channelApiKeyId;

    /** 渠道模型 ID（模型级熔断时使用） */
    private Long channelModelId;

    /** 是否开启熔断：0-关闭，1-开启 */
    private Integer isOpen;

    /** 失败次数 */
    private Integer failCount;

    /** 熔断开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime openedAt;

    /** 熔断到期时间 */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime expireAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }

    public Long getChannelApiKeyId() { return channelApiKeyId; }
    public void setChannelApiKeyId(Long channelApiKeyId) { this.channelApiKeyId = channelApiKeyId; }

    public Long getChannelModelId() { return channelModelId; }
    public void setChannelModelId(Long channelModelId) { this.channelModelId = channelModelId; }

    public Integer getIsOpen() { return isOpen; }
    public void setIsOpen(Integer isOpen) { this.isOpen = isOpen; }

    public Integer getFailCount() { return failCount; }
    public void setFailCount(Integer failCount) { this.failCount = failCount; }

    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }

    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
