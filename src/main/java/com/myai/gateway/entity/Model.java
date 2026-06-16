package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 自定义/对外模型实体
 * 对应 models 表，表示用户自定义的对外暴露的模型名
 */
@TableName("models")
public class Model {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 自定义模型名称（对外暴露的模型名） */
    private String modelName;

    /** 模型描述 */
    private String description;

    /** 关联渠道模型的选择策略：random / round_robin / failover */
    private String strategy;

    /** 是否启用 */
    private Integer enabled;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    public Model() {}

    public Model(String modelName, String description, String strategy) {
        this.modelName = modelName;
        this.description = description;
        this.strategy = strategy;
        this.enabled = 1;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
