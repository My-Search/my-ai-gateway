package com.myai.gateway.service;

import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.myai.gateway.service.StatsSupport.SHANGHAI;
import static com.myai.gateway.service.StatsSupport.toLong;
import static com.myai.gateway.service.StatsSupport.toUtc;

/**
 * Dashboard 概览统计 - 顶部汇总卡片 + 渠道/模型排行 + 7 天趋势 + 最近请求
 * <p>
 * 由 {@link StatsService} 组合调用，聚焦"概览页"单一职责。
 * </p>
 */
class DashboardStatsCollector {

    private final RequestLogMapper requestLogMapper;

    DashboardStatsCollector(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
    }

    /**
     * 获取Dashboard统计数据（集中部署的 SQL 聚合，避免全量加载+内存聚合）
     */
    Map<String, Object> collect(String channelRankPeriod, String modelRankPeriod, String date) {
        Map<String, Object> stats = new LinkedHashMap<>();
        // 统一使用 Asia/Shanghai 时区计算日期范围，与今日趋势保持一致
        // created_at 存储为 UTC，需要将上海时区的日期起止转换为 UTC 用于 SQL WHERE
        LocalDate refDate = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now(SHANGHAI);
        LocalDateTime todayStart = refDate.atStartOfDay().atZone(SHANGHAI).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime sevenDaysAgo = todayStart.minusDays(6);

        // 1. 今日聚合统计（trace-level 去重）
        Map<String, Object> todayAgg = requestLogMapper.selectTodayAggregatedStats(todayStart);
        long todayRequests = toLong(todayAgg.get("today_requests"));       // 今日发起的唯一请求数
        double avgResponseTime = todayAgg.get("avg_response_time") != null
                ? ((Number) todayAgg.get("avg_response_time")).doubleValue() : 0.0;

        // 2. 昨日唯一请求数（同比对比，trace-level 去重）
        long yesterdayRequests = requestLogMapper.selectYesterdayStartCount(yesterdayStart, todayStart);

        // 2.1 昨日聚合（成功率/首字节/生成速度，口径与今日一致），供卡片"较昨日"环比
        Map<String, Object> yesterdayAgg = requestLogMapper.selectRangeAggregatedStats(yesterdayStart, todayStart);
        long yesterdayFail = requestLogMapper.countFailedTracesBetween(yesterdayStart, todayStart);
        long yesterdaySuccess = Math.max(0, yesterdayRequests - yesterdayFail);
        double yesterdaySuccessRate = yesterdayRequests > 0 ? (double) yesterdaySuccess / yesterdayRequests * 100 : 0;
        double yesterdayAvgResponse = yesterdayAgg.get("avg_response_time") != null
                ? ((Number) yesterdayAgg.get("avg_response_time")).doubleValue() : 0.0;
        double yesterdayAvgOutputSpeed = yesterdayAgg.get("avg_output_speed") != null
                ? ((Number) yesterdayAgg.get("avg_output_speed")).doubleValue() : 0.0;
        Map<String, Object> yesterdayStats = new LinkedHashMap<>();
        yesterdayStats.put("requests", yesterdayRequests);
        yesterdayStats.put("successRate", Math.round(yesterdaySuccessRate * 10) / 10.0);
        yesterdayStats.put("avgResponseTime", Math.round(yesterdayAvgResponse));
        yesterdayStats.put("avgOutputSpeed", Math.round(yesterdayAvgOutputSpeed * 10.0) / 10.0);
        stats.put("yesterdayStats", yesterdayStats);

        // 3. 以 trace-level 计算成功/失败数与成功率
        //    todayFail: 今日发起且从未 success（所有尝试均失败）的 trace 数
        //    todaySuccess: 今日发起且至少有一次 success 的 trace 数
        long todayFail = requestLogMapper.countFailedTraces(todayStart);
        long todaySuccess = Math.max(0, todayRequests - todayFail);
        double successRate = todayRequests > 0 ? (double) todaySuccess / todayRequests * 100 : 0;

        stats.put("todayRequests", todayRequests);
        stats.put("yesterdayRequests", yesterdayRequests);
        stats.put("todaySuccess", todaySuccess);
        stats.put("todayFail", todayFail);
        stats.put("avgResponseTime", Math.round(avgResponseTime));
        double avgOutputSpeed = todayAgg.get("avg_output_speed") != null
                ? ((Number) todayAgg.get("avg_output_speed")).doubleValue() : 0.0;
        stats.put("avgOutputSpeed", Math.round(avgOutputSpeed * 10.0) / 10.0);
        stats.put("successRate", Math.round(successRate * 10) / 10.0);

        // 4. Token 用量
        Map<String, Object> tokenStats = new LinkedHashMap<>();
        tokenStats.put("promptTokens", toLong(todayAgg.get("prompt_tokens")));
        tokenStats.put("completionTokens", toLong(todayAgg.get("completion_tokens")));
        tokenStats.put("totalTokens", toLong(todayAgg.get("total_tokens")));
        stats.put("todayTokenStats", tokenStats);

        // 5. 本月统计（上海时区日期转 UTC 查询）
        LocalDateTime monthStart = toUtc(refDate.withDayOfMonth(1));
        LocalDateTime monthEnd = toUtc(refDate.plusMonths(1).withDayOfMonth(1));
        Map<String, Object> monthAgg = requestLogMapper.selectMonthlyAggregatedStats(monthStart, monthEnd);
        long monthlyRequests = toLong(monthAgg.get("monthly_requests"));
        long monthlySuccess = toLong(monthAgg.get("monthly_success"));
        long monthlyFail = toLong(monthAgg.get("monthly_fail"));
        double monthlyAvgResponse = monthAgg.get("avg_response_time") != null
                ? ((Number) monthAgg.get("avg_response_time")).doubleValue() : 0.0;
        double monthlySuccessRate = monthlyRequests > 0 ? (double) monthlySuccess / monthlyRequests * 100 : 0.0;

        Map<String, Object> monthlyStats = new LinkedHashMap<>();
        monthlyStats.put("requests", monthlyRequests);
        monthlyStats.put("promptTokens", toLong(monthAgg.get("monthly_prompt_tokens")));
        monthlyStats.put("completionTokens", toLong(monthAgg.get("monthly_completion_tokens")));
        monthlyStats.put("totalTokens", toLong(monthAgg.get("monthly_total_tokens")));
        monthlyStats.put("successRate", Math.round(monthlySuccessRate * 10) / 10.0);
        monthlyStats.put("avgResponseTime", Math.round(monthlyAvgResponse));
        double monthlyAvgOutputSpeed = monthAgg.get("avg_output_speed") != null
                ? ((Number) monthAgg.get("avg_output_speed")).doubleValue() : 0.0;
        monthlyStats.put("avgOutputSpeed", Math.round(monthlyAvgOutputSpeed * 10.0) / 10.0);
        monthlyStats.put("failCount", monthlyFail);

        // 5.1 上月统计（用于环比）
        LocalDateTime prevMonthStart = toUtc(refDate.minusMonths(1).withDayOfMonth(1));
        LocalDateTime prevMonthEnd = toUtc(refDate.withDayOfMonth(1));
        Map<String, Object> prevMonthAgg = requestLogMapper.selectMonthlyAggregatedStats(prevMonthStart, prevMonthEnd);
        long prevMonthlyRequests = toLong(prevMonthAgg.get("monthly_requests"));
        long prevMonthlySuccess = toLong(prevMonthAgg.get("monthly_success"));
        long prevMonthlyFail = toLong(prevMonthAgg.get("monthly_fail"));
        double prevMonthlyAvgResponse = prevMonthAgg.get("avg_response_time") != null
                ? ((Number) prevMonthAgg.get("avg_response_time")).doubleValue() : 0.0;
        double prevMonthlySuccessRate = prevMonthlyRequests > 0 ? (double) prevMonthlySuccess / prevMonthlyRequests * 100 : 0.0;

        Map<String, Object> prevMonthlyStats = new LinkedHashMap<>();
        prevMonthlyStats.put("requests", prevMonthlyRequests);
        prevMonthlyStats.put("totalTokens", toLong(prevMonthAgg.get("monthly_total_tokens")));
        prevMonthlyStats.put("successRate", Math.round(prevMonthlySuccessRate * 10) / 10.0);
        prevMonthlyStats.put("avgResponseTime", Math.round(prevMonthlyAvgResponse));
        double prevMonthlyAvgOutputSpeed = prevMonthAgg.get("avg_output_speed") != null
                ? ((Number) prevMonthAgg.get("avg_output_speed")).doubleValue() : 0.0;
        prevMonthlyStats.put("avgOutputSpeed", Math.round(prevMonthlyAvgOutputSpeed * 10.0) / 10.0);
        prevMonthlyStats.put("failCount", prevMonthlyFail);

        monthlyStats.put("prev", prevMonthlyStats);
        stats.put("monthlyStats", monthlyStats);

        // 6. 渠道排行、模型排行（按周期参数聚合）
        PeriodRange channelPeriod = calculatePeriodRange(channelRankPeriod, refDate);
        PeriodRange modelPeriod = calculatePeriodRange(modelRankPeriod, refDate);
        stats.put("channelRank", requestLogMapper.selectChannelRank(channelPeriod.since(), channelPeriod.end()));
        stats.put("modelRank", requestLogMapper.selectEntryModelRank(modelPeriod.since(), modelPeriod.end()));
        stats.put("channelModelRank", requestLogMapper.selectChannelModelRank(modelPeriod.since(), modelPeriod.end()));

        // 7. 7天趋势（一次 GROUP BY 替代原来 7 次循环）
        List<Map<String, Object>> dailyTrend = buildDailyTrend(sevenDaysAgo);
        long maxDailyRequests = dailyTrend.stream()
                .mapToLong(d -> toLong(d.get("requests")))
                .max().orElse(1);
        if (maxDailyRequests == 0) maxDailyRequests = 1;
        stats.put("dailyTrend", dailyTrend);
        stats.put("maxDailyRequests", maxDailyRequests);

        // 8. 最近 10 条独特 trace 的最新日志条目（trace-level 去重）
        // 先在近 48h 时间窗内取（基于当前时间，避免全表 GROUP BY），不足 10 个 trace 时回退全量口径，展示行为与旧版一致
        LocalDateTime recentSince = LocalDateTime.now(ZoneOffset.UTC).minusHours(48);
        List<RequestLog> recentLogs = requestLogMapper.selectRecentTraces(recentSince);
        if (recentLogs.size() < 10) {
            recentLogs = requestLogMapper.fallbackRecentTraces();
        }
        stats.put("recentLogs", recentLogs);

        return stats;
    }

    /**
     * 根据周期字符串计算查询时间范围
     * 返回的时间范围已转换为 UTC（上海时区日期 → UTC）
     */
    private PeriodRange calculatePeriodRange(String period, LocalDate refDate) {
        if (period == null) period = "today";
        if (refDate == null) refDate = LocalDate.now(SHANGHAI);
        return switch (period) {
            case "yesterday" -> {
                LocalDate yesterday = refDate.minusDays(1);
                yield new PeriodRange(toUtc(yesterday), toUtc(refDate));
            }
            case "week" -> {
                LocalDate weekStart = refDate.with(DayOfWeek.MONDAY);
                yield new PeriodRange(toUtc(weekStart), null);
            }
            case "month" -> {
                LocalDate monthStart = refDate.withDayOfMonth(1);
                yield new PeriodRange(toUtc(monthStart), null);
            }
            default -> new PeriodRange(toUtc(refDate), null);
        };
    }

    /** 时间范围记录 */
    private record PeriodRange(LocalDateTime since, LocalDateTime end) {}

    /**
     * 从聚合查询结果构建 7 天趋势（带 label 字段，兼容前端）
     */
    private List<Map<String, Object>> buildDailyTrend(LocalDateTime since) {
        List<Map<String, Object>> dbRows = requestLogMapper.selectDailyTrend(since);
        // 用 Map 索引实现 O(1) 查找，替代原来的 O(n²) 双层循环
        Map<String, Map<String, Object>> dateIndex = new HashMap<>();
        for (Map<String, Object> row : dbRows) {
            dateIndex.put((String) row.get("date"), row);
        }
        LocalDate startDate = since.toLocalDate();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = startDate.plusDays(i);
            String dateStr = date.toString();
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", dateStr);
            day.put("label", date.getMonthValue() + "/" + date.getDayOfMonth());
            Map<String, Object> row = dateIndex.get(dateStr);
            if (row != null) {
                day.put("requests", toLong(row.get("requests")));
                day.put("success", toLong(row.get("success")));
                day.put("fail", toLong(row.get("fail")));
                // 首字节平均时间：毫秒取整；平均生成速度：保留一位小数
                double avgTime = row.get("avg_time") != null ? ((Number) row.get("avg_time")).doubleValue() : 0.0;
                day.put("avgTime", Math.round(avgTime));
                double avgOutputSpeed = row.get("avg_output_speed") != null
                        ? ((Number) row.get("avg_output_speed")).doubleValue() : 0.0;
                day.put("avgOutputSpeed", Math.round(avgOutputSpeed * 10.0) / 10.0);
            } else {
                day.put("requests", 0L);
                day.put("success", 0L);
                day.put("fail", 0L);
                day.put("avgTime", 0L);
                day.put("avgOutputSpeed", 0.0);
            }
            result.add(day);
        }
        return result;
    }
}
