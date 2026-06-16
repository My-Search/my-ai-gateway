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
     */
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);

        // 今日所有日志
        List<RequestLog> todayLogs = requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .ge(RequestLog::getCreatedAt, todayStart));

        // 昨日日志（用于对比）
        List<RequestLog> yesterdayLogs = requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .ge(RequestLog::getCreatedAt, yesterdayStart)
                        .lt(RequestLog::getCreatedAt, todayStart));

        // 总请求数（以 start 阶段计数）
        long todayRequests = todayLogs.stream()
                .filter(l -> "start".equals(l.getPhase()))
                .count();
        long yesterdayRequests = yesterdayLogs.stream()
                .filter(l -> "start".equals(l.getPhase()))
                .count();

        // 成功/失败数
        long todaySuccess = todayLogs.stream()
                .filter(l -> "success".equals(l.getPhase()))
                .count();
        long todayFail = todayLogs.stream()
                .filter(l -> "fail".equals(l.getPhase()))
                .count();

        // 平均响应时间
        double avgResponseTime = todayLogs.stream()
                .filter(l -> l.getResponseTimeMs() != null && l.getResponseTimeMs() > 0)
                .mapToInt(RequestLog::getResponseTimeMs)
                .average()
                .orElse(0.0);

        // 成功率
        long totalFinished = todaySuccess + todayFail;
        double successRate = totalFinished > 0 ? (double) todaySuccess / totalFinished * 100 : 0;

        stats.put("todayRequests", todayRequests);
        stats.put("yesterdayRequests", yesterdayRequests);
        stats.put("todaySuccess", todaySuccess);
        stats.put("todayFail", todayFail);
        stats.put("avgResponseTime", Math.round(avgResponseTime));
        stats.put("successRate", Math.round(successRate * 10) / 10.0);

        // 渠道排行（按请求数）
        stats.put("channelRank", buildChannelRank(todayLogs));

        // 模型排行（按请求数）
        stats.put("modelRank", buildModelRank(todayLogs));

        // 最近7天趋势
        List<Map<String, Object>> dailyTrend = buildDailyTrend(7);
        long maxDailyRequests = dailyTrend.stream()
                .mapToLong(d -> ((Number) d.get("requests")).longValue())
                .max().orElse(1);
        if (maxDailyRequests == 0) maxDailyRequests = 1;
        stats.put("dailyTrend", dailyTrend);
        stats.put("maxDailyRequests", maxDailyRequests);

        // 最近10条日志
        List<RequestLog> recentLogs = requestLogMapper.selectList(
                new LambdaQueryWrapper<RequestLog>()
                        .orderByDesc(RequestLog::getCreatedAt)
                        .last("LIMIT 10"));
        stats.put("recentLogs", recentLogs);

        return stats;
    }

    private List<Map<String, Object>> buildChannelRank(List<RequestLog> todayLogs) {
        Map<String, Long> channelCounts = todayLogs.stream()
                .filter(l -> "start".equals(l.getPhase()) && l.getChannelName() != null)
                .collect(Collectors.groupingBy(RequestLog::getChannelName, Collectors.counting()));

        Map<String, Long> channelSuccess = todayLogs.stream()
                .filter(l -> "success".equals(l.getPhase()) && l.getChannelName() != null)
                .collect(Collectors.groupingBy(RequestLog::getChannelName, Collectors.counting()));

        Map<String, Double> channelAvgTime = todayLogs.stream()
                .filter(l -> l.getChannelName() != null && l.getResponseTimeMs() != null && l.getResponseTimeMs() > 0)
                .collect(Collectors.groupingBy(RequestLog::getChannelName,
                        Collectors.averagingInt(RequestLog::getResponseTimeMs)));

        return channelCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", e.getKey());
                    item.put("requests", e.getValue());
                    item.put("success", channelSuccess.getOrDefault(e.getKey(), 0L));
                    item.put("avgTime", Math.round(channelAvgTime.getOrDefault(e.getKey(), 0.0)));
                    return item;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildModelRank(List<RequestLog> todayLogs) {
        Map<String, Long> modelCounts = todayLogs.stream()
                .filter(l -> "start".equals(l.getPhase()) && l.getModelName() != null)
                .collect(Collectors.groupingBy(RequestLog::getModelName, Collectors.counting()));

        Map<String, Long> modelSuccess = todayLogs.stream()
                .filter(l -> "success".equals(l.getPhase()) && l.getModelName() != null)
                .collect(Collectors.groupingBy(RequestLog::getModelName, Collectors.counting()));

        return modelCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", e.getKey());
                    item.put("requests", e.getValue());
                    item.put("success", modelSuccess.getOrDefault(e.getKey(), 0L));
                    return item;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildDailyTrend(int days) {
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.plusDays(1).atStartOfDay();

            List<RequestLog> dayLogs = requestLogMapper.selectList(
                    new LambdaQueryWrapper<RequestLog>()
                            .ge(RequestLog::getCreatedAt, start)
                            .lt(RequestLog::getCreatedAt, end));

            long requests = dayLogs.stream().filter(l -> "start".equals(l.getPhase())).count();
            long success = dayLogs.stream().filter(l -> "success".equals(l.getPhase())).count();
            long fail = dayLogs.stream().filter(l -> "fail".equals(l.getPhase())).count();

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date.toString());
            day.put("label", (date.getMonthValue()) + "/" + date.getDayOfMonth());
            day.put("requests", requests);
            day.put("success", success);
            day.put("fail", fail);
            trend.add(day);
        }
        return trend;
    }
}
