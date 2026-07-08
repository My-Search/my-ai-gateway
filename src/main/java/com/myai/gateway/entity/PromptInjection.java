package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * Prompt 注入规则实体
 * 对应 prompt_injections 表，表示按入口模型配置的自动消息注入规则
 */
@TableName("prompt_injections")
public class PromptInjection {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的入口模型 ID */
    private Long modelId;

    /** 规则名称（便于管理） */
    private String name;

    /** 注入角色：system / user / assistant */
    private String injectRole;

    /** 注入位置：prepend（消息前）/ append（消息后）/ replace_system（替换系统消息） */
    private String injectPosition;

    /** 注入的 prompt 文本内容 */
    private String content;

    /** 是否启用 */
    private Integer enabled;

    /** 优先级（越小越优先执行） */
    private Integer priority;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    public PromptInjection() {}

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getInjectRole() { return injectRole; }
    public void setInjectRole(String injectRole) { this.injectRole = injectRole; }

    public String getInjectPosition() { return injectPosition; }
    public void setInjectPosition(String injectPosition) { this.injectPosition = injectPosition; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
