package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 模型与渠道模型关联实体
 * 表示自定义模型下关联了哪个渠道的哪个具体模型
 */
@TableName("model_channel_rels")
public class ModelChannelRel {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 自定义模型 ID */
    private Long modelId;

    /** 渠道模型 ID */
    private Long channelModelId;

    /** 权重（预留） */
    private Integer weight;

    /** 排序顺序（越小越优先） */
    private Integer sortOrder;

    /** 是否启用 */
    private Integer enabled;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    // 非数据库字段 - 关联查询使用
    @TableField(exist = false)
    private String channelModelName;

    @TableField(exist = false)
    private String channelName;

    @TableField(exist = false)
    private String channelType;

    @TableField(exist = false)
    private Long channelId;

    public ModelChannelRel() {}

    public ModelChannelRel(Long modelId, Long channelModelId) {
        this.modelId = modelId;
        this.channelModelId = channelModelId;
        this.weight = 1;
        this.sortOrder = 0;
        this.enabled = 1;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }

    public Long getChannelModelId() { return channelModelId; }
    public void setChannelModelId(Long channelModelId) { this.channelModelId = channelModelId; }

    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getChannelModelName() { return channelModelName; }
    public void setChannelModelName(String channelModelName) { this.channelModelName = channelModelName; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
}
