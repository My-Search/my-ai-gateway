package com.myai.gateway.service;

import com.myai.gateway.mapper.RequestLogMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.myai.gateway.service.StatsSupport.SHANGHAI;
import static com.myai.gateway.service.StatsSupport.toLong;
import static com.myai.gateway.service.StatsSupport.toUtc;

/**
 * API Key 用量统计 - 日/周/月三个周期的 token 与请求次数聚合
 * <p>
 * 由 {@link StatsService} 组合调用，聚焦"API Key 管理页"单一职责。
 * </p>
 */
class ApiKeyUsageStatsCollector {

    private final RequestLogMapper requestLogMapper;

    ApiKeyUsageStatsCollector(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
    }

    /**
     * 获取所有 API Key 的日/周/月用量统计（token 和请求次数）
     * <p>
     * 返回 Map&lt;apiKeyId, Map&lt;period, stats&gt;&gt;，其中 period 为 "day"/"week"/"month"，
     * stats 包含 requestCount 和 totalTokens。所有时间范围基于上海时区计算。
     * </p>
     */
    Map<Long, Map<String, Map<String, Object>>> collect() {
        LocalDate now = LocalDate.now(SHANGHAI);
        // 今日起始（上海时区转 UTC）
        LocalDateTime dayStart = toUtc(now);
        // 本周起始（周一）
        LocalDateTime weekStart = toUtc(now.with(DayOfWeek.MONDAY));
        // 本月起始
        LocalDateTime monthStart = toUtc(now.withDayOfMonth(1));

        // 三个周期的查询
        List<Map<String, Object>> dayStats = requestLogMapper.selectApiKeyUsageStats(dayStart);
        List<Map<String, Object>> weekStats = requestLogMapper.selectApiKeyUsageStats(weekStart);
        List<Map<String, Object>> monthStats = requestLogMapper.selectApiKeyUsageStats(monthStart);

        // 构建结果：apiKeyId -> { day: {...}, week: {...}, month: {...} }
        Map<Long, Map<String, Map<String, Object>>> result = new LinkedHashMap<>();
        mergePeriodStats(result, "day", dayStats);
        mergePeriodStats(result, "week", weekStats);
        mergePeriodStats(result, "month", monthStats);
        return result;
    }

    /**
     * 将某一周期的统计结果合并到 result 中
     */
    private void mergePeriodStats(Map<Long, Map<String, Map<String, Object>>> result,
                                   String period, List<Map<String, Object>> statsList) {
        for (Map<String, Object> row : statsList) {
            Number idNum = (Number) row.get("gateway_api_key_id");
            if (idNum == null) continue;
            Long apiKeyId = idNum.longValue();
            Map<String, Map<String, Object>> periods = result.computeIfAbsent(apiKeyId, k -> new LinkedHashMap<>());
            Map<String, Object> periodStats = new LinkedHashMap<>();
            periodStats.put("requestCount", toLong(row.get("request_count")));
            periodStats.put("totalTokens", toLong(row.get("total_tokens")));
            periods.put(period, periodStats);
        }
    }
}
