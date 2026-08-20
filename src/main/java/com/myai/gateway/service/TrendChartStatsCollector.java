package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.myai.gateway.service.StatsSupport.SHANGHAI;
import static com.myai.gateway.service.StatsSupport.aggregateCompletionTokens;
import static com.myai.gateway.service.StatsSupport.aggregatePromptTokens;
import static com.myai.gateway.service.StatsSupport.aggregateRequestCounts;
import static com.myai.gateway.service.StatsSupport.aggregateTotalTokens;
import static com.myai.gateway.service.StatsSupport.buildPeriodMap;
import static com.myai.gateway.service.StatsSupport.emptyToNull;
import static com.myai.gateway.service.StatsSupport.toLong;
import static com.myai.gateway.service.StatsSupport.toUtc;

/**
 * 趋势图表统计 - 今日分桶趋势、渠道汇总、渠道模型用量、月度使用历史、模型列表统计
 * <p>
 * 由 {@link StatsService} 组合调用，聚焦"图表/列表页"单一职责。
 * </p>
 */
class TrendChartStatsCollector {

    private final RequestLogMapper requestLogMapper;

    TrendChartStatsCollector(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
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
    Map<String, Object> collectTodayHourlyTrend(String mode, String date) {
        // 使用 Asia/Shanghai 时区计算今日范围，因为 created_at 存储为 UTC
        // 将上海时区的今日起止转换为 UTC 用于 SQL WHERE
        LocalDate refDate = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now(SHANGHAI);
        LocalDateTime todayStart = toUtc(refDate);
        LocalDateTime tomorrowStart = toUtc(refDate.plusDays(1));

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
     *
     * @return Map: channelName -> { requestCount, promptTokens, completionTokens, totalTokens }
     */
    Map<String, Map<String, Object>> collectChannelSummaryStats() {
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
     *
     * @return Map: { modelStats: List[{ modelName, requestCount, promptTokens, completionTokens, totalTokens, avgResponseTimeRecent30, today, week, month }],
     *                channelAvgResponseTimeRecent30: long }
     */
    Map<String, Object> collectChannelModelUsageStats(String channelName) {
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

        // 计算时间段边界（上海时区 -> UTC，created_at 存储为 UTC）
        LocalDate nowSh = LocalDate.now(SHANGHAI);
        LocalDateTime todayStartUtc = nowSh.atStartOfDay().atZone(SHANGHAI).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime weekStartUtc = nowSh.with(DayOfWeek.MONDAY).atStartOfDay().atZone(SHANGHAI).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime monthStartUtc = nowSh.withDayOfMonth(1).atStartOfDay().atZone(SHANGHAI).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();

        // 全量统计
        Map<String, Long> requestCounts = aggregateRequestCounts(successLogs, null);
        Map<String, Long> promptSums = aggregatePromptTokens(successLogs, null);
        Map<String, Long> completionSums = aggregateCompletionTokens(successLogs, null);
        Map<String, Long> totalSums = aggregateTotalTokens(successLogs, null);

        // 今日统计
        Map<String, Long> todayRequestCounts = aggregateRequestCounts(successLogs, todayStartUtc);
        Map<String, Long> todayPromptSums = aggregatePromptTokens(successLogs, todayStartUtc);
        Map<String, Long> todayCompletionSums = aggregateCompletionTokens(successLogs, todayStartUtc);
        Map<String, Long> todayTotalSums = aggregateTotalTokens(successLogs, todayStartUtc);

        // 本周统计
        Map<String, Long> weekRequestCounts = aggregateRequestCounts(successLogs, weekStartUtc);
        Map<String, Long> weekPromptSums = aggregatePromptTokens(successLogs, weekStartUtc);
        Map<String, Long> weekCompletionSums = aggregateCompletionTokens(successLogs, weekStartUtc);
        Map<String, Long> weekTotalSums = aggregateTotalTokens(successLogs, weekStartUtc);

        // 本月统计
        Map<String, Long> monthRequestCounts = aggregateRequestCounts(successLogs, monthStartUtc);
        Map<String, Long> monthPromptSums = aggregatePromptTokens(successLogs, monthStartUtc);
        Map<String, Long> monthCompletionSums = aggregateCompletionTokens(successLogs, monthStartUtc);
        Map<String, Long> monthTotalSums = aggregateTotalTokens(successLogs, monthStartUtc);

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

        Set<String> allModels = new LinkedHashSet<>();
        allModels.addAll(requestCounts.keySet());
        allModels.addAll(todayRequestCounts.keySet());
        allModels.addAll(weekRequestCounts.keySet());
        allModels.addAll(monthRequestCounts.keySet());

        List<Map<String, Object>> modelStatsList = allModels.stream()
                .sorted((a, b) -> Long.compare(
                        requestCounts.getOrDefault(b, 0L),
                        requestCounts.getOrDefault(a, 0L)))
                .map(modelName -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("modelName", modelName);
                    item.put("requestCount", requestCounts.getOrDefault(modelName, 0L));
                    item.put("promptTokens", promptSums.getOrDefault(modelName, 0L));
                    item.put("completionTokens", completionSums.getOrDefault(modelName, 0L));
                    item.put("totalTokens", totalSums.getOrDefault(modelName, 0L));
                    item.put("avgResponseTimeRecent30", modelAvgResponseTimeRecent30.getOrDefault(modelName, 0L));

                    item.put("today", buildPeriodMap(
                            todayRequestCounts.getOrDefault(modelName, 0L),
                            todayPromptSums.getOrDefault(modelName, 0L),
                            todayCompletionSums.getOrDefault(modelName, 0L),
                            todayTotalSums.getOrDefault(modelName, 0L)));
                    item.put("week", buildPeriodMap(
                            weekRequestCounts.getOrDefault(modelName, 0L),
                            weekPromptSums.getOrDefault(modelName, 0L),
                            weekCompletionSums.getOrDefault(modelName, 0L),
                            weekTotalSums.getOrDefault(modelName, 0L)));
                    item.put("month", buildPeriodMap(
                            monthRequestCounts.getOrDefault(modelName, 0L),
                            monthPromptSums.getOrDefault(modelName, 0L),
                            monthCompletionSums.getOrDefault(modelName, 0L),
                            monthTotalSums.getOrDefault(modelName, 0L)));

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
     *
     * @param year            目标年份（如 2026）
     * @param month           目标月份，1-12
     * @param modelType       模型类型："entry"（入口模型，默认）或 "channel"（渠道模型）
     * @param modelName       入口模型过滤（可选；null/空表示不过滤）
     * @param gatewayApiKeyId 网关 API Key 主键过滤（可选；与 apiKeyName 同时存在时优先使用 id）
     * @param apiKeyName      API Key 过滤（可选；null/空表示不过滤，兼容旧调用，对应渠道 Key 名）
     * @return 包含 year/month/days/models/values/maxValue/totalValue 的 Map
     */
    Map<String, Object> collectLogUsageChart(int year, int month, String modelType, String modelName,
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
        long[] dailyTotals = new long[daysInMonth];
        for (String model : sortedModels) {
            long[] bucket = modelValues.get(model);
            List<Long> series = new ArrayList<>(daysInMonth);
            for (int i = 0; i < daysInMonth; i++) {
                long v = bucket[i];
                series.add(v);
                dailyTotals[i] += v;
                totalValue += v;
            }
            values.put(model, series);
        }
        for (long dt : dailyTotals) {
            if (dt > maxValue) maxValue = dt;
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

    /**
     * 获取模型管理页所需的各模型统计与趋势数据。
     *
     * @param modelNames 所有已知模型名称列表（用于补齐无数据的模型趋势）
     * @param date       参考日期（yyyy-MM-dd，可选，null 表示今天）
     * @return Map: {
     *   stats: List[{ modelName, requests, successRate, avgResponseTime }],
     *   trends: Map<modelName, List[requests]>
     * }
     */
    Map<String, Object> collectModelListStats(List<String> modelNames, String date) {
        LocalDate refDate = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now(SHANGHAI);
        LocalDateTime todayStart = toUtc(refDate);
        LocalDateTime todayEnd = toUtc(refDate.plusDays(1));

        // 1. 今日各模型统计
        List<Map<String, Object>> modelStatsRows = requestLogMapper.selectTodayModelStats(todayStart);
        Map<String, Map<String, Object>> statsMap = new LinkedHashMap<>();
        for (Map<String, Object> row : modelStatsRows) {
            String name = (String) row.get("model_name");
            if (name == null || name.isEmpty()) continue;
            long requests = toLong(row.get("requests"));
            long success = toLong(row.get("success"));
            double avgResponse = row.get("avg_response_time") != null
                    ? ((Number) row.get("avg_response_time")).doubleValue() : 0.0;
            double successRate = requests > 0 ? (double) success / requests * 100 : 0.0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("modelName", name);
            item.put("requests", requests);
            item.put("successRate", Math.round(successRate * 10) / 10.0);
            item.put("avgResponseTime", Math.round(avgResponse));
            statsMap.put(name, item);
        }

        // 2. 今日每10分钟趋势（按模型分组）
        List<Map<String, Object>> trendRows = requestLogMapper.selectTodayModelBucketTrend(todayStart, todayEnd);
        // 预生成 bucket 列表（00:00 ~ 23:50，每10分钟）
        List<String> buckets = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            for (int m = 0; m < 60; m += 10) {
                buckets.add(String.format("%02d:%02d", h, m));
            }
        }
        Map<String, Integer> bucketIndex = new HashMap<>();
        for (int i = 0; i < buckets.size(); i++) bucketIndex.put(buckets.get(i), i);

        // 按模型收集趋势数据
        Map<String, long[]> modelTrendArrays = new LinkedHashMap<>();
        for (Map<String, Object> row : trendRows) {
            String name = (String) row.get("model_name");
            String bucket = (String) row.get("bucket");
            if (name == null || name.isEmpty() || bucket == null) continue;
            Integer idx = bucketIndex.get(bucket);
            if (idx == null) continue;
            long[] arr = modelTrendArrays.computeIfAbsent(name, k -> new long[buckets.size()]);
            arr[idx] = toLong(row.get("requests"));
        }

        // 3. 补齐所有已知模型的 trend（无数据填 0）
        Map<String, List<Long>> trends = new LinkedHashMap<>();
        for (String name : modelNames) {
            long[] arr = modelTrendArrays.get(name);
            if (arr == null) arr = new long[buckets.size()];
            List<Long> list = new ArrayList<>(buckets.size());
            for (long v : arr) list.add(v);
            trends.put(name, list);
        }
        // 也包含不在 modelNames 中但有数据的模型
        for (Map.Entry<String, long[]> entry : modelTrendArrays.entrySet()) {
            if (!trends.containsKey(entry.getKey())) {
                List<Long> list = new ArrayList<>(buckets.size());
                for (long v : entry.getValue()) list.add(v);
                trends.put(entry.getKey(), list);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", new ArrayList<>(statsMap.values()));
        result.put("trends", trends);
        result.put("buckets", buckets);
        return result;
    }
}
