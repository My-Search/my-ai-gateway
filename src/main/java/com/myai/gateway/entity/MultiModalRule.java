package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 多模态规则实体
 * 对应 multimodal_rules 表，通过正则匹配模型名称，
 * 自动标记渠道模型的 input 类型（如 text、text,image）
 */
@TableName("multimodal_rules")
public class MultiModalRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 正则表达式（匹配模型名称） */
    private String pattern;

    /** 匹配后添加的模态类型，默认 image */
    private String appendType;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    public MultiModalRule() {}

    public MultiModalRule(String pattern, String appendType) {
        this.pattern = pattern;
        this.appendType = appendType != null ? appendType : "image";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }

    public String getAppendType() { return appendType; }
    public void setAppendType(String appendType) { this.appendType = appendType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
