package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 渠道实体
 * 对应 channels 表，表示一个 AI 服务提供商渠道
 */
@TableName("channels")
public class Channel {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 渠道名称，如 OpenAI、Anthropic */
    private String name;

    /** 渠道类型：openai / anthropic */
    private String channelType;

    /** 渠道 API Key */
    private String apiKey;

    /** 渠道基础 URL */
    private String baseUrl;

    /** 是否启用，1-启用 0-禁用 */
    private Integer enabled;

    /** 排序 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    // 非数据库字段：渠道下的 API Keys
    @TableField(exist = false)
    private List<ChannelApiKey> apiKeys;

    // 非数据库字段：渠道下的模型（编辑时使用）
    @TableField(exist = false)
    private List<ChannelModel> models;

    public Channel() {}

    public Channel(String name, String channelType, String baseUrl) {
        this.name = name;
        this.channelType = channelType;
        this.baseUrl = baseUrl;
        this.enabled = 1;
        this.sortOrder = 0;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<ChannelApiKey> getApiKeys() { return apiKeys; }
    public void setApiKeys(List<ChannelApiKey> apiKeys) { this.apiKeys = apiKeys; }

    public List<ChannelModel> getModels() { return models; }
    public void setModels(List<ChannelModel> models) { this.models = models; }
}
