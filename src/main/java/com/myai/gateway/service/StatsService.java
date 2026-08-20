package com.myai.gateway.service;

import com.myai.gateway.mapper.RequestLogMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 统计服务 - 从请求日志中聚合数据（门面）
 * <p>
 * Dashboard / 趋势图表 / API Key 用量的具体聚合逻辑分别由
 * {@link DashboardStatsCollector}、{@link TrendChartStatsCollector}、
 * {@link ApiKeyUsageStatsCollector} 承担，本类仅做职责编排与对外暴露，
 * 保持对外 API 稳定（controller/前端零改动）。
 * </p>
 */
@Service
public class StatsService {

    private final DashboardStatsCollector dashboardStats;
    private final TrendChartStatsCollector trendChartStats;
    private final ApiKeyUsageStatsCollector apiKeyUsageStats;

    public StatsService(RequestLogMapper requestLogMapper) {
        this.dashboardStats = new DashboardStatsCollector(requestLogMapper);
        this.trendChartStats = new TrendChartStatsCollector(requestLogMapper);
        this.apiKeyUsageStats = new ApiKeyUsageStatsCollector(requestLogMapper);
    }

    /**
     * 获取Dashboard统计数据
     * <p>
     * 使用 SQL 聚合查询替代原来的全量加载+内存聚合方式，大幅减少数据扫描量。
     * </p>
     *
     * @param channelRankPeriod 渠道排行时间周期：today / yesterday / week / month
     * @param modelRankPeriod   模型排行时间周期：today / yesterday / week / month
     * @param date              参考日期（yyyy-MM-dd，可选，null 表示今天）
     */
    public Map<String, Object> getDashboardStats(String channelRankPeriod, String modelRankPeriod, String date) {
        return dashboardStats.collect(channelRankPeriod, modelRankPeriod, date);
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
        return trendChartStats.collectTodayHourlyTrend(mode, date);
    }

    /**
     * 获取所有渠道的汇总用量统计（用于渠道列表页展示）
     *
     * @return Map: channelName -> { requestCount, promptTokens, completionTokens, totalTokens }
     */
    public Map<String, Map<String, Object>> getChannelSummaryStats() {
        return trendChartStats.collectChannelSummaryStats();
    }

    /**
     * 获取指定渠道下各模型的用量统计（用于渠道模型详情页展示）
     * <p>
     * 按 channel_model_name 聚合成功请求的 token 用量和请求次数，
     * 额外返回今日/本周/本月的请求次数和 Token 用量（按 Asia/Shanghai 时区计算）。
     * </p>
     *
     * @param channelName 渠道名称
     * @return Map: { modelStats: List[...], channelAvgResponseTimeRecent30: long }
     */
    public Map<String, Object> getChannelModelUsageStats(String channelName) {
        return trendChartStats.collectChannelModelUsageStats(channelName);
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
    public Map<String, Object> getLogUsageChart(int year, int month, String modelType, String modelName,
                                                Long gatewayApiKeyId, String apiKeyName) {
        return trendChartStats.collectLogUsageChart(year, month, modelType, modelName, gatewayApiKeyId, apiKeyName);
    }

    /** 兼容旧调用：仅传 modelName + apiKeyName */
    public Map<String, Object> getLogUsageChart(int year, int month, String modelName, String apiKeyName) {
        return trendChartStats.collectLogUsageChart(year, month, "entry", modelName, null, apiKeyName);
    }

    /** 兼容旧调用：传 modelName + gatewayApiKeyId + apiKeyName */
    public Map<String, Object> getLogUsageChart(int year, int month, String modelName, Long gatewayApiKeyId, String apiKeyName) {
        return trendChartStats.collectLogUsageChart(year, month, "entry", modelName, gatewayApiKeyId, apiKeyName);
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
    public Map<String, Object> getModelListStats(List<String> modelNames, String date) {
        return trendChartStats.collectModelListStats(modelNames, date);
    }

    /**
     * 获取所有 API Key 的日/周/月用量统计（token 和请求次数）
     * <p>
     * 返回 Map&lt;apiKeyId, Map&lt;period, stats&gt;&gt;，其中 period 为 "day"/"week"/"month"，
     * stats 包含 requestCount 和 totalTokens。所有时间范围基于上海时区计算。
     * </p>
     */
    public Map<Long, Map<String, Map<String, Object>>> getApiKeyUsageStats() {
        return apiKeyUsageStats.collect();
    }
}
