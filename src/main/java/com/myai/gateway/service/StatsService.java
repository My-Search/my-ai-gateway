package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
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
     *
     * @param channelRankPeriod 渠道排行时间周期：today / yesterday / week / month
     * @param modelRankPeriod   模型排行时间周期：today / yesterday / week / month
     * @param date              参考日期（yyyy-MM-dd，可选，null 表示今天）
     */
    public Map<String, Object> getDashboardStats(String channelRankPeriod, String modelRankPeriod, String date) {
        Map<String, Object> stats = new LinkedHashMap<>();
        LocalDate refDate = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now();
        LocalDateTime todayStart = refDate.atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime sevenDaysAgo = todayStart.minusDays(6);

        // 1. 今日聚合统计（trace-level 去重）
        Map<String, Object> todayAgg = requestLogMapper.selectTodayAggregatedStats(todayStart);
        long todayRequests = toLong(todayAgg.get("today_requests"));       // 今日发起的唯一请求数
        double avgResponseTime = todayAgg.get("avg_response_time") != null
                ? ((Number) todayAgg.get("avg_response_time")).doubleValue() : 0.0;

        // 2. 昨日唯一请求数（同比对比，trace-level 去重）
        long yesterdayRequests = requestLogMapper.selectYesterdayStartCount(yesterdayStart, todayStart);

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
        stats.put("successRate", Math.round(successRate * 10) / 10.0);

        // 4. Token 用量
        Map<String, Object> tokenStats = new LinkedHashMap<>();
        tokenStats.put("promptTokens", toLong(todayAgg.get("prompt_tokens")));
        tokenStats.put("completionTokens", toLong(todayAgg.get("completion_tokens")));
        tokenStats.put("totalTokens", toLong(todayAgg.get("total_tokens")));
        stats.put("todayTokenStats", tokenStats);

        // 5. 本月统计
        LocalDateTime monthStart = refDate.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = refDate.plusMonths(1).withDayOfMonth(1).atStartOfDay();
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
        monthlyStats.put("failCount", monthlyFail);

        // 5.1 上月统计（用于环比）
        LocalDateTime prevMonthStart = refDate.minusMonths(1).withDayOfMonth(1).atStartOfDay();
        LocalDateTime prevMonthEnd = refDate.withDayOfMonth(1).atStartOfDay();
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
        List<RequestLog> recentLogs = requestLogMapper.selectRecentTraces();
        stats.put("recentLogs", recentLogs);

        return stats;
    }

    /**
     * 根据周期字符串计算查询时间范围
     */
    private PeriodRange calculatePeriodRange(String period, LocalDate refDate) {
        if (period == null) period = "today";
        if (refDate == null) refDate = LocalDate.now();
        return switch (period) {
            case "yesterday" -> {
                LocalDate yesterday = refDate.minusDays(1);
                yield new PeriodRange(yesterday.atStartOfDay(), refDate.atStartOfDay());
            }
            case "week" -> {
                LocalDate weekStart = refDate.with(DayOfWeek.MONDAY);
                yield new PeriodRange(weekStart.atStartOfDay(), null);
            }
            case "month" -> {
                LocalDate monthStart = refDate.withDayOfMonth(1);
                yield new PeriodRange(monthStart.atStartOfDay(), null);
            }
            default -> new PeriodRange(refDate.atStartOfDay(), null);
        };
    }

    /** 时间范围记录 */
    private record PeriodRange(LocalDateTime since, LocalDateTime end) {}

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
     * 获取今日每10分钟请求趋势（折线图数据）
     * <p>
     * 支持三种模式：
     * <ul>
     *   <li>all — 全部请求，拆分为成功/失败两条线</li>
     *   <li>entry — 按入口模型分组，每个模型一条线</li>
     *   <li>channel — 按渠道模型分组，每个渠道模型一条线</li>
     * </ul>
     * 返回全天 144 个时间桶（00:00, 00:10, ..., 23:50）的请求数，缺省桶补 0。
     * </p>
     */
    public Map<String, Object> getTodayHourlyTrend(String mode, String date) {
        // 使用 Asia/Shanghai 时区计算今日范围，因为 created_at 存储为 UTC
        // 将上海时区的今日起止转换为 UTC 用于 SQL WHERE
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        LocalDate refDate = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now(shanghai);
        LocalDateTime todayStart = refDate.atStartOfDay().atZone(shanghai).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime tomorrowStart = refDate.plusDays(1).atStartOfDay().atZone(shanghai).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        // 预填 144 个时间桶标签 ["00:00", "00:10", ..., "23:50"]
        int bucketCount = 24 * 6; // 144
        String[] buckets = new String[bucketCount];
        Map<String, Integer> bucketIndex = new HashMap<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            String label = String.format("%02d:%02d", i / 6, (i % 6) * 10);
            buckets[i] = label;
            bucketIndex.put(label, i);
        }

        Map<String, long[]> seriesMap = new LinkedHashMap<>();
        if ("entry".equals(mode)) {
            List<Map<String, Object>> rows = requestLogMapper.selectTodayBucketEntryModelTrend(todayStart, tomorrowStart);
            for (Map<String, Object> row : rows) {
                String bucket = (String) row.get("bucket");
                Integer idx = bucketIndex.get(bucket);
                if (idx == null) continue;
                String model = (String) row.get("model_name");
                long requests = toLong(row.get("requests"));
                if (model == null || model.isEmpty()) continue;
                seriesMap.computeIfAbsent(model, k -> new long[bucketCount])[idx] += requests;
            }
        } else if ("channel".equals(mode)) {
            List<Map<String, Object>> rows = requestLogMapper.selectTodayBucketChannelModelTrend(todayStart, tomorrowStart);
            for (Map<String, Object> row : rows) {
                String bucket = (String) row.get("bucket");
                Integer idx = bucketIndex.get(bucket);
                if (idx == null) continue;
                String channelName = (String) row.get("channel_name");
                String modelName = (String) row.get("name");
                long requests = toLong(row.get("requests"));
                String key = channelName != null ? channelName + "/" + modelName : modelName;
                if (modelName == null || modelName.isEmpty()) continue;
                seriesMap.computeIfAbsent(key, k -> new long[bucketCount])[idx] += requests;
            }
        } else {
            // all 模式 — 拆分为成功/失败两条线
            List<Map<String, Object>> rows = requestLogMapper.selectTodayBucketTrend(todayStart, tomorrowStart);
            long[] total = new long[bucketCount];
            long[] success = new long[bucketCount];
            for (Map<String, Object> row : rows) {
                String bucket = (String) row.get("bucket");
                Integer idx = bucketIndex.get(bucket);
                if (idx == null) continue;
                total[idx] += toLong(row.get("requests"));
                success[idx] += toLong(row.get("success"));
            }
            long[] fail = new long[bucketCount];
            for (int i = 0; i < bucketCount; i++) {
                fail[i] = Math.max(0, total[i] - success[i]);
            }
            seriesMap.put("success", success);
            seriesMap.put("fail", fail);
        }

        // 按总请求量降序排列模型
        List<String> sortedModels = seriesMap.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(
                        Comparator.comparingLong(a -> {
                            long sum = 0;
                            for (long v : a) sum += v;
                            return -sum;
                        })).reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 构建 series 输出
        Map<String, Object> series = new LinkedHashMap<>();
        for (String model : sortedModels) {
            long[] values = seriesMap.get(model);
            List<Long> list = new ArrayList<>(bucketCount);
            for (long v : values) list.add(v);
            series.put(model, list);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buckets", Arrays.asList(buckets));
        result.put("mode", mode != null ? mode : "all");
        result.put("series", series);
        return result;
    }

    /**
     * 获取所有渠道的汇总用量统计（用于渠道列表页展示）
     * <p>
     * 使用 SQL GROUP BY 聚合替代原来的全量加载 + 4 次流处理，
     * 大幅减少从 request_logs 表扫描的数据量和 Java 堆内存占用。
     * </p>
     *
     * @return Map: channelName -> { requestCount, promptTokens, completionTokens, totalTokens }
     */
    public Map<String, Map<String, Object>> getChannelSummaryStats() {
        List<Map<String, Object>> rows = requestLogMapper.selectChannelSummaryStats();

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String channelName = (String) row.get("channel_name");
            if (channelName == null || channelName.isEmpty()) continue;
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("requestCount", toLong(row.get("request_count")));
            stats.put("promptTokens", toLong(row.get("prompt_tokens")));
            stats.put("completionTokens", toLong(row.get("completion_tokens")));
            stats.put("totalTokens", toLong(row.get("total_tokens")));
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

    /**
     * 获取"请求日志"页面顶部"使用历史"堆叠柱状图数据。
     * <p>
     * 主干流程：
     * <ol>
     *   <li>把 year/month 转成 [since, until) 半开区间（含起始日 00:00、不含次月 00:00）</li>
     *   <li>按 modelType 分支调用 SQL 聚合该月数据：
     *     <ul>
     *       <li>entry 模式：按入口模型名聚合 token 用量（仅统计 success 行）</li>
     *       <li>channel 模式：按 trace 级聚合，最终成功归入该次渠道模型名，最终失败归为"请求失败"，统计请求次数</li>
     *     </ul>
     *   </li>
     *   <li>遍历当月每一天，组装 days/dates 数组（无论是否有数据都补齐，便于前端稳定渲染）</li>
     *   <li>将模型按该月总用量降序排序，确保色板稳定分配（最常用模型固定拿主色）</li>
     *   <li>构建 values 矩阵：model -> List&lt;Long&gt;，长度等于 days.length</li>
     * </ol>
     * </p>
     *
     * @param year            目标年份（如 2026）
     * @param month           目标月份，1-12
     * @param modelType       模型类型："entry"（入口模型，默认）或 "channel"（渠道模型）
     * @param modelName       入口模型过滤（可选；null/空表示不过滤）
     * @param gatewayApiKeyId 网关 API Key 主键过滤（可选；与 apiKeyName 同时存在时优先使用 id）
     * @param apiKeyName      API Key 过滤（可选；null/空表示不过滤，兼容旧调用，对应渠道 Key 名）
     * @return 包含 year/month/days/models/values/maxValue/totalValue 的 Map
     */
    public Map<String, Object> getLogUsageChart(int year, int month, String modelType, String modelName,
                                                Long gatewayApiKeyId, String apiKeyName) {
        return getLogUsageChartInternal(year, month, modelType, modelName, gatewayApiKeyId, apiKeyName);
    }

    /** 兼容旧调用：仅传 modelName + apiKeyName */
    public Map<String, Object> getLogUsageChart(int year, int month, String modelName, String apiKeyName) {
        return getLogUsageChartInternal(year, month, "entry", modelName, null, apiKeyName);
    }

    /** 兼容旧调用：传 modelName + gatewayApiKeyId + apiKeyName */
    public Map<String, Object> getLogUsageChart(int year, int month, String modelName, Long gatewayApiKeyId, String apiKeyName) {
        return getLogUsageChartInternal(year, month, "entry", modelName, gatewayApiKeyId, apiKeyName);
    }

    private Map<String, Object> getLogUsageChartInternal(int year, int month, String modelType,
                                                         String modelName,
                                                         Long gatewayApiKeyId, String apiKeyName) {
        // 1. 规范化入参并计算 [since, until)
        YearMonth ym = YearMonth.of(year, month);
        LocalDate sinceDate = ym.atDay(1);
        LocalDate untilDate = ym.plusMonths(1).atDay(1);
        LocalDateTime since = sinceDate.atStartOfDay();
        LocalDateTime until = untilDate.atStartOfDay();
        int daysInMonth = ym.lengthOfMonth();

        // 2. 按 modelType 分支拉取该月 (date, model_name, total_tokens) 聚合行
        boolean isChannel = "channel".equals(modelType);
        List<Map<String, Object>> rows = isChannel
            ? requestLogMapper.selectDailyChannelModelTokenUsage(
                since, until, emptyToNull(modelName), gatewayApiKeyId, emptyToNull(apiKeyName))
            : requestLogMapper.selectDailyModelTokenUsage(
                since, until, emptyToNull(modelName), gatewayApiKeyId, emptyToNull(apiKeyName));

        // 3. 预生成 days 数组（yyyy-MM-dd 形式）+ 用于 O(1) 查找的 dateIndex
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<String> days = new ArrayList<>(daysInMonth);
        Map<String, Integer> dateIndex = new HashMap<>(daysInMonth);
        for (int d = 1; d <= daysInMonth; d++) {
            String dateStr = sinceDate.withDayOfMonth(d).format(dateFmt);
            days.add(dateStr);
            dateIndex.put(dateStr, d - 1);
        }

        // 4. 遍历聚合行：累加到 modelTotals（用于排序）和 modelValues（按日填充）
        //    使用 LinkedHashMap 保证遍历顺序稳定（与数据库返回顺序一致）
        Map<String, long[]> modelValues = new LinkedHashMap<>();
        Map<String, Long> modelTotals = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String date = (String) row.get("date");
            String model = (String) row.get("model_name");
            long tokens = toLong(row.get("total_tokens"));
            Integer idx = dateIndex.get(date);
            if (idx == null || model == null || model.isEmpty()) continue;
            long[] bucket = modelValues.computeIfAbsent(model, k -> new long[daysInMonth]);
            bucket[idx] += tokens;
            modelTotals.merge(model, tokens, Long::sum);
        }

        // 5. 按月总用量降序排序模型列表（保证前端颜色映射稳定：TopN 模型固定拿主色）
        List<String> sortedModels = modelValues.keySet().stream()
                .sorted(Comparator.comparingLong((String m) -> modelTotals.getOrDefault(m, 0L)).reversed())
                .collect(Collectors.toList());

        // 6. 构建 values 矩阵（model -> List<Long>）+ 累计 maxValue/totalValue
        Map<String, Object> values = new LinkedHashMap<>();
        long maxValue = 0L;
        long totalValue = 0L;
        for (String model : sortedModels) {
            long[] bucket = modelValues.get(model);
            List<Long> series = new ArrayList<>(daysInMonth);
            for (long v : bucket) {
                series.add(v);
                if (v > maxValue) maxValue = v;
                totalValue += v;
            }
            values.put(model, series);
        }

        // 7. 组装返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("month", month);
        result.put("days", days);
        result.put("models", sortedModels);
        result.put("values", values);
        result.put("maxValue", maxValue);
        result.put("totalValue", totalValue);
        return result;
    }

    /** null/空字符串/纯空白统一归一为 null，便于在 MyBatis 动态 SQL 中按空判断跳过条件。 */
    private String emptyToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
