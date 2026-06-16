package com.myai.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员配置表
 */
@Data
@TableName("admin_config")
public class AdminConfig {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String configKey;

    private String configValue;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}