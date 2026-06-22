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

    /**
     * 关联模式
     * - 'self_add'：使用本模型在 model_channel_rels 中的自有关联（默认）
     * - 'inherit'：关联列表实时映射自 inherit_from_model_id 所指向的源入口模型（只读）
     */
    private String relMode = RelMode.SELF_ADD;

    /**
     * 继承源模型 ID（仅在 relMode='inherit' 时有效）
     * 关联的源入口模型（也存于 models 表）
     */
    private Long inheritFromModelId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    public Model() {}

    public Model(String modelName, String description, String strategy) {
        this.modelName = modelName;
        this.description = description;
        this.strategy = strategy;
        this.enabled = 1;
        this.relMode = "self_add";
    }

    /** 关联模式常量 */
    public static final class RelMode {
        public static final String SELF_ADD = "self_add";
        public static final String INHERIT = "inherit";

        private RelMode() {}
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

    public String getRelMode() { return relMode; }
    public void setRelMode(String relMode) { this.relMode = relMode; }

    public Long getInheritFromModelId() { return inheritFromModelId; }
    public void setInheritFromModelId(Long inheritFromModelId) { this.inheritFromModelId = inheritFromModelId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
