package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计服务 - 从请求日志中聚合数据
 */
@Service
public class StatsService {

    private final RequestLogMapper requestLogMapper;

    public StatsService(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
    }

    /**
     * 获取Dashboard统计数据
     * <p>
     * 使用 SQL 聚合查询替代原来的全量加载+内存聚合方式，大幅减少数据扫描量。
     * 优化前：10+ 次全量查询（含 7 次逐日循环），加载全部行到内存做 stream 聚合
     * 优化后：7 次轻量聚合查询，SQLite 直接返回聚合结果
     * </p>
     */
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime sevenDaysAgo = todayStart.minusDays(6);

        // 1. 今日聚合统计（一次查询）
        Map<String, Object> todayAgg = requestLogMapper.selectTodayAggregatedStats(todayStart);
        long todayRequests = toLong(todayAgg.get("today_requests"));
        long todaySuccess = toLong(todayAgg.get("today_success"));
        long todayFail = toLong(todayAgg.get("today_fail"));
        double avgResponseTime = todayAgg.get("avg_response_time") != null
                ? ((Number) todayAgg.get("avg_response_time")).doubleValue() : 0.0;

        // 2. 昨日 start 请求数（对比用）
        long yesterdayRequests = requestLogMapper.selectYesterdayStartCount(yesterdayStart, todayStart);

        // 3. 成功率
        long totalFinished = todaySuccess + todayFail;
        double successRate = totalFinished > 0 ? (double) todaySuccess / totalFinished * 100 : 0;

        stats.put("todayRequests", todayRequests);
        stats.put("yesterdayRequests", yesterdayRequests);
        stats.put("todaySuccess", todaySuccess);
        stats.put("todayFail", todayFail);
        stats.put("avgResponseTime", Math.round(avgResponseTime));
        stats.put("successRate", Math.round(successRate * 10) / 10.0);

        // 4. Token 用量
        Map<String, Object> tokenStats = new LinkedHashMap<>();
        tokenStats.put("promptTokens", toLong(todayAgg.get("prompt_tokens")));
        tokenStats.put("completionTokens", toLong(todayAgg.get("completion_tokens")));
        tokenStats.put("totalTokens", toLong(todayAgg.get("total_tokens")));
        stats.put("todayTokenStats", tokenStats);

        // 5. 渠道排行、模型排行（各一次聚合查询）
        stats.put("channelRank", requestLogMapper.selectChannelRank(todayStart));
        stats.put("modelRank", requestLogMapper.selectEntryModelRank(todayStart));
        stats.put("channelModelRank", requestLogMapper.selectChannelModelRank(todayStart));

        // 6. 7天趋势（一次 GROUP BY 替代原来 7 次循环）
        List<Map<String, Object>> dailyTrend = buildDailyTrend(sevenDaysAgo);
        long maxDailyRequests = dailyTrend.stream()
                .mapToLong(d -> toLong(d.get("requests")))
                .max().orElse(1);
        if (maxDailyRequests == 0) maxDailyRequests = 1;
        stats.put("dailyTrend", dailyTrend);
        stats.put("maxDailyRequests", maxDailyRequests);

        // 7. 最近10条日志（已带 LIMIT，保持不动）
        List<RequestLog> recentLogs = requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .orderByDesc(RequestLog::getCreatedAt)
                        .last("LIMIT 10"));
        stats.put("recentLogs", recentLogs);

        return stats;
    }

    /**
     * 从聚合查询结果中安全转为 long
     */
    private long toLong(Object value) {
        if (value == null) return 0L;
        return ((Number) value).longValue();
    }

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
            } else {
                day.put("requests", 0L);
                day.put("success", 0L);
                day.put("fail", 0L);
            }
            result.add(day);
        }
        return result;
    }

    /**
     * 获取所有渠道的汇总用量统计（用于渠道列表页展示）
     * 按 channel_name 聚合成功请求的 token 用量和请求次数
     *
     * @return Map: channelName -> { requestCount, promptTokens, completionTokens, totalTokens }
     */
    public Map<String, Map<String, Object>> getChannelSummaryStats() {
        List<RequestLog> successLogs = requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .eq(RequestLog::getPhase, "success")
                        .isNotNull(RequestLog::getChannelName)
                        .ne(RequestLog::getChannelName, ""));

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Map<String, Long> requestCounts = successLogs.stream()
                .collect(Collectors.groupingBy(RequestLog::getChannelName, Collectors.counting()));
        Map<String, Long> promptSums = successLogs.stream()
                .collect(Collectors.groupingBy(RequestLog::getChannelName,
                        Collectors.summingLong(l -> l.getPromptTokens() != null ? l.getPromptTokens() : 0)));
        Map<String, Long> completionSums = successLogs.stream()
                .collect(Collectors.groupingBy(RequestLog::getChannelName,
                        Collectors.summingLong(l -> l.getCompletionTokens() != null ? l.getCompletionTokens() : 0)));
        Map<String, Long> totalSums = successLogs.stream()
                .collect(Collectors.groupingBy(RequestLog::getChannelName,
                        Collectors.summingLong(l -> l.getTotalTokens() != null ? l.getTotalTokens() : 0)));

        for (String channelName : requestCounts.keySet()) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("requestCount", requestCounts.get(channelName));
            stats.put("promptTokens", promptSums.getOrDefault(channelName, 0L));
            stats.put("completionTokens", completionSums.getOrDefault(channelName, 0L));
            stats.put("totalTokens", totalSums.getOrDefault(channelName, 0L));
            result.put(channelName, stats);
        }
        return result;
    }

    /**
     * 获取指定渠道下各模型的用量统计（用于渠道模型详情页展示）
     * 按 channel_model_name 聚合成功请求的 token 用量和请求次数
     * 同时计算每个模型最近30次请求的平均响应时间，以及渠道整体最近30次的平均响应时间
     *
     * @param channelName 渠道名称
     * @return Map: { modelStats: List[{ modelName, requestCount, promptTokens, completionTokens, totalTokens, avgResponseTimeRecent30 }],
     *                channelAvgResponseTimeRecent30: long }
     */
    public Map<String, Object> getChannelModelUsageStats(String channelName) {
        // Token 统计仅按 success 聚合
        List<RequestLog> successLogs = requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .eq(RequestLog::getPhase, "success")
                        .eq(RequestLog::getChannelName, channelName)
                        .isNotNull(RequestLog::getChannelModelName)
                        .ne(RequestLog::getChannelModelName, ""));

        // 响应时间按 success+fail 聚合（失败请求也有响应时间），按时间倒序用于截取最近 N 条
        List<RequestLog> responseTimeLogs = requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .in(RequestLog::getPhase, "success", "fail")
                        .eq(RequestLog::getChannelName, channelName)
                        .isNotNull(RequestLog::getChannelModelName)
                        .ne(RequestLog::getChannelModelName, "")
                        .isNotNull(RequestLog::getResponseTimeMs)
                        .gt(RequestLog::getResponseTimeMs, 0)
                        .orderByDesc(RequestLog::getCreatedAt));

        Map<String, Long> requestCounts = successLogs.stream()
                .collect(Collectors.groupingBy(RequestLog::getChannelModelName, Collectors.counting()));
        Map<String, Long> promptSums = successLogs.stream()
                .collect(Collectors.groupingBy(RequestLog::getChannelModelName,
                        Collectors.summingLong(l -> l.getPromptTokens() != null ? l.getPromptTokens() : 0)));
        Map<String, Long> completionSums = successLogs.stream()
                .collect(Collectors.groupingBy(RequestLog::getChannelModelName,
                        Collectors.summingLong(l -> l.getCompletionTokens() != null ? l.getCompletionTokens() : 0)));
        Map<String, Long> totalSums = successLogs.stream()
                .collect(Collectors.groupingBy(RequestLog::getChannelModelName,
                        Collectors.summingLong(l -> l.getTotalTokens() != null ? l.getTotalTokens() : 0)));

        // 按模型分组（已按时间倒序），每组取最近 30 条计算平均响应时间
        Map<String, List<RequestLog>> logsByModel = responseTimeLogs.stream()
                .collect(Collectors.groupingBy(RequestLog::getChannelModelName));

        Map<String, Long> modelAvgResponseTimeRecent30 = new LinkedHashMap<>();
        for (Map.Entry<String, List<RequestLog>> entry : logsByModel.entrySet()) {
            double avg = entry.getValue().stream()
                    .limit(30)
                    .mapToInt(RequestLog::getResponseTimeMs)
                    .average()
                    .orElse(0.0);
            modelAvgResponseTimeRecent30.put(entry.getKey(), Math.round(avg));
        }

        // 渠道级：所有模型合在一起取最近 30 条的平均响应时间
        long channelAvgResponseTimeRecent30 = Math.round(
                responseTimeLogs.stream()
                        .limit(30)
                        .mapToInt(RequestLog::getResponseTimeMs)
                        .average()
                        .orElse(0.0));

        List<Map<String, Object>> modelStatsList = requestCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("modelName", e.getKey());
                    item.put("requestCount", e.getValue());
                    item.put("promptTokens", promptSums.getOrDefault(e.getKey(), 0L));
                    item.put("completionTokens", completionSums.getOrDefault(e.getKey(), 0L));
                    item.put("totalTokens", totalSums.getOrDefault(e.getKey(), 0L));
                    item.put("avgResponseTimeRecent30", modelAvgResponseTimeRecent30.getOrDefault(e.getKey(), 0L));
                    return item;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelStats", modelStatsList);
        result.put("channelAvgResponseTimeRecent30", channelAvgResponseTimeRecent30);
        return result;
    }

}
