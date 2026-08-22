package com.myai.gateway.service;

import com.myai.gateway.entity.RequestLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统计共用工具 - 聚合、时区换算与类型转换
 * <p>
 * 供统计相关服务（Dashboard/趋势/用量）复用的纯静态方法，避免各统计类重复实现
 * 时区与聚合口径保持一致：created_at 存储为 UTC，外部日期统一按 Asia/Shanghai 计算。
 * </p>
 */
final class StatsSupport {

    private StatsSupport() {
    }

    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 上海时区日期起始 -> UTC 时间（creat_at 存储为 UTC） */
    static LocalDateTime toUtc(LocalDate shanghaiDate) {
        return shanghaiDate.atStartOfDay().atZone(SHANGHAI).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return ((Number) value).longValue();
    }

    /** null 安全的 double 转换（SQL AVG 无匹配行时返回 null） */
    static double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        return ((Number) value).doubleValue();
    }

    /** null/空字符串/纯空白统一归一为 null，便于在 MyBatis 动态 SQL 中按空判断跳过条件。 */
    static String emptyToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 组装单周期 token 统计 Map */
    static Map<String, Object> buildPeriodMap(long requestCount, long promptTokens, long completionTokens, long totalTokens) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestCount", requestCount);
        map.put("promptTokens", promptTokens);
        map.put("completionTokens", completionTokens);
        map.put("totalTokens", totalTokens);
        return map;
    }
}
