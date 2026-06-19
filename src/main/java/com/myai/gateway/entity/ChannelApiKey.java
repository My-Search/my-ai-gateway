package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 渠道 API Key 实体
 * 对应 channel_api_keys 表，一个渠道可以有多个 API Key
 */
@TableName("channel_api_keys")
public class ChannelApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的渠道 ID */
    private Long channelId;

    /** API Key 名称（用户自定义，用于区分不同的 Key） */
    private String keyName;

    /** API Key 值 */
    private String apiKey;

    /** 是否启用：1-启用 0-禁用 */
    private Integer enabled;

    /** 排序 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    public ChannelApiKey() {}

    public ChannelApiKey(Long channelId, String keyName, String apiKey) {
        this.channelId = channelId;
        this.keyName = keyName;
        this.apiKey = apiKey;
        this.enabled = 1;
        this.sortOrder = 0;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }

    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}