package com.myai.gateway.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 强制 JVM 默认时区为 UTC
 *
 * LocalDateTime.now() 使用 JVM 默认时区获取当前时间。
 * 如果 JVM 时区不一致（本地开发 UTC+8 vs Docker UTC），
 * 同一段代码会产生不同的时间值，前端无法区分。
 *
 * 这里统一设为 UTC，确保无论在哪运行，LocalDateTime.now() 都返回 UTC 时间。
 * 前端收到带 Z 后缀的 ISO 8601 字符串后，统一转换为浏览器本地时区。
 */
@Configuration
public class TimeZoneConfig {

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
