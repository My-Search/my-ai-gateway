package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 渠道模型实体
 * 对应 channel_models 表，表示某个渠道下的具体模型
 */
@TableName("channel_models")
public class ChannelModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属渠道 ID */
    private Long channelId;

    /** 关联的 API Key ID（可选，如果不指定则使用第一个可用的 API Key） */
    private Long channelApiKeyId;

    /** 模型名称（渠道侧，如 gpt-4、claude-3-opus-20240229） */
    private String modelName;

    /** 显示名称 */
    private String displayName;

    /** 是否启用 */
    private Integer enabled;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /** 关联的渠道信息（非数据库字段） */
    @TableField(exist = false)
    private String channelName;

    @TableField(exist = false)
    private String channelType;

    /** 关联的 API Key 信息（非数据库字段） */
    @TableField(exist = false)
    private String apiKeyName;

    public ChannelModel() {}

    public ChannelModel(Long channelId, String modelName, String displayName) {
        this.channelId = channelId;
        this.modelName = modelName;
        this.displayName = displayName;
        this.enabled = 1;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }

    public Long getChannelApiKeyId() { return channelApiKeyId; }
    public void setChannelApiKeyId(Long channelApiKeyId) { this.channelApiKeyId = channelApiKeyId; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public String getApiKeyName() { return apiKeyName; }
    public void setApiKeyName(String apiKeyName) { this.apiKeyName = apiKeyName; }
}
