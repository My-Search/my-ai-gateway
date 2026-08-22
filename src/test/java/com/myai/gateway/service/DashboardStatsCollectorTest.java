package com.myai.gateway.service;

import com.myai.gateway.entity.RequestLog;
import com.myai.gateway.mapper.RequestLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DashboardStatsCollector 单元测试 — 覆盖 7 天趋势（dailyTrend）的字段回填逻辑。
 * <p>
 * 范围：只验证 buildDailyTrend 的"SQL 行 → dailyTrend"组装（avgTime 四舍五入取整、
 * avgOutputSpeed 保留一位小数、缺失日期补 0、空数据兜底）。
 * 不做 SQL 集成测试（SQLite 聚合行为由人工/集成测试覆盖）。
 * </p>
 */
class DashboardStatsCollectorTest {

    private RequestLogMapper requestLogMapper;
    private DashboardStatsCollector collector;

    @BeforeEach
    void setUp() {
        requestLogMapper = mock(RequestLogMapper.class);
        collector = new DashboardStatsCollector(requestLogMapper);
        mockEmptyAggregates();
    }

    /** 默认让非趋势类聚合查询返回空数据，测试只关注 dailyTrend */
    private void mockEmptyAggregates() {
        when(requestLogMapper.selectTodayAggregatedStats(any())).thenReturn(new HashMap<>());
        when(requestLogMapper.selectYesterdayStartCount(any(), any())).thenReturn(0L);
        when(requestLogMapper.selectRangeAggregatedStats(any(), any())).thenReturn(new HashMap<>());
        when(requestLogMapper.countFailedTracesBetween(any(), any())).thenReturn(0L);
        when(requestLogMapper.countFailedTraces(any())).thenReturn(0L);
        when(requestLogMapper.selectMonthlyAggregatedStats(any(), any())).thenReturn(new HashMap<>());
        when(requestLogMapper.selectChannelRank(any(), any())).thenReturn(List.of());
        when(requestLogMapper.selectEntryModelRank(any(), any())).thenReturn(List.of());
        when(requestLogMapper.selectChannelModelRank(any(), any())).thenReturn(List.of());
        when(requestLogMapper.selectRecentTraces(any())).thenReturn(List.of());
        when(requestLogMapper.fallbackRecentTraces()).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dailyTrend(Map<String, Object> stats) {
        return (List<Map<String, Object>>) stats.get("dailyTrend");
    }

    private Map<String, Object> findDay(List<Map<String, Object>> trend, LocalDate date) {
        return trend.stream().filter(d -> date.toString().equals(d.get("date"))).findFirst().orElseThrow();
    }

    @Test
    void dailyTrend_roundsAvgTimeToIntegerAndAvgSpeedToOneDecimal() {
        // SQL 返回两天聚合：avg_time 为毫秒小数、avg_output_speed 为小数
        // 注意：趋势日期序列基于 UTC（上海今日 00:00 转 UTC 是 UTC 昨日 16:00，故末日 = UTC 昨天）
        LocalDate trendLastDay = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        when(requestLogMapper.selectDailyTrend(any())).thenReturn(List.of(
                trendRow(trendLastDay, 5L, 4L, 1L, 3760.4, 49.65),
                trendRow(trendLastDay.minusDays(1), 2L, 1L, 1L, 1234.5, 12.34)
        ));

        Map<String, Object> stats = collector.collect("today", "today", null);
        List<Map<String, Object>> trend = dailyTrend(stats);
        assertThat(trend).hasSize(7);

        // 四舍五入到整数毫秒；avgOutputSpeed 一位小数
        Map<String, Object> today = findDay(trend, trendLastDay);
        assertThat(today.get("avgTime")).isEqualTo(3760L);
        assertThat(today.get("avgOutputSpeed")).isEqualTo(49.7);

        Map<String, Object> yesterday = findDay(trend, trendLastDay.minusDays(1));
        assertThat(yesterday.get("avgTime")).isEqualTo(1235L);
        assertThat(yesterday.get("avgOutputSpeed")).isEqualTo(12.3);

        // 无数据的日期补 0（avgTime=0L、avgOutputSpeed=0.0）
        Map<String, Object> dayAgo2 = findDay(trend, trendLastDay.minusDays(2));
        assertThat(dayAgo2.get("requests")).isEqualTo(0L);
        assertThat(dayAgo2.get("avgTime")).isEqualTo(0L);
        assertThat(dayAgo2.get("avgOutputSpeed")).isEqualTo(0.0);
    }

    @Test
    void dailyTrend_nullAggregates_defaultToZero() {
        // avg_time / avg_output_speed 为 NULL（当天无有效记录）→ 按 0 输出
        LocalDate trendLastDay = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        when(requestLogMapper.selectDailyTrend(any())).thenReturn(List.of(
                trendRow(trendLastDay, 1L, 0L, 1L, null, null)
        ));

        Map<String, Object> stats = collector.collect("today", "today", null);
        Map<String, Object> today = findDay(dailyTrend(stats), trendLastDay);
        assertThat(today.get("avgTime")).isEqualTo(0L);
        assertThat(today.get("avgOutputSpeed")).isEqualTo(0.0);
    }

    @Test
    void dailyTrend_emptyDbRows_fillsSevenDaysWithZero() {
        when(requestLogMapper.selectDailyTrend(any())).thenReturn(List.of());

        Map<String, Object> stats = collector.collect("today", "today", null);
        List<Map<String, Object>> trend = dailyTrend(stats);

        assertThat(trend).hasSize(7);
        trend.forEach(day -> {
            assertThat(day.get("requests")).isEqualTo(0L);
            assertThat(day.get("success")).isEqualTo(0L);
            assertThat(day.get("fail")).isEqualTo(0L);
            assertThat(day.get("avgTime")).isEqualTo(0L);
            assertThat(day.get("avgOutputSpeed")).isEqualTo(0.0);
        });
    }

    @Test
    void yesterdayStats_computedWithSameSemanticsAsToday() {
        // 昨日：10 个请求、2 个完全失败 → 成功率 80%；avg 响应/速度取窗口聚合值
        when(requestLogMapper.selectYesterdayStartCount(any(), any())).thenReturn(10L);
        when(requestLogMapper.countFailedTracesBetween(any(), any())).thenReturn(2L);
        Map<String, Object> agg = new HashMap<>();
        agg.put("avg_response_time", 2345.6);
        agg.put("avg_output_speed", 33.45);
        when(requestLogMapper.selectRangeAggregatedStats(any(), any())).thenReturn(agg);

        Map<String, Object> stats = collector.collect("today", "today", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> yesterday = (Map<String, Object>) stats.get("yesterdayStats");

        assertThat(yesterday.get("requests")).isEqualTo(10L);
        assertThat(yesterday.get("successRate")).isEqualTo(80.0);
        assertThat(yesterday.get("avgResponseTime")).isEqualTo(2346L);
        assertThat(yesterday.get("avgOutputSpeed")).isEqualTo(33.5);
    }

    @Test
    void yesterdayStats_emptyYesterday_returnsZeroValues() {
        // 昨日无任何请求 → 全 0，前端环比计算兜底（prev=0 时不显示无意义百分比）
        when(requestLogMapper.selectYesterdayStartCount(any(), any())).thenReturn(0L);
        when(requestLogMapper.selectRangeAggregatedStats(any(), any())).thenReturn(new HashMap<>());

        Map<String, Object> stats = collector.collect("today", "today", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> yesterday = (Map<String, Object>) stats.get("yesterdayStats");

        assertThat(yesterday.get("requests")).isEqualTo(0L);
        assertThat(yesterday.get("successRate")).isEqualTo(0.0);
        assertThat(yesterday.get("avgResponseTime")).isEqualTo(0L);
        assertThat(yesterday.get("avgOutputSpeed")).isEqualTo(0.0);
    }

    @Test
    void recentLogs_windowShortOfTen_fallsBackToFullScan() {
        // 时间窗内不足 10 个 trace → 回退全量口径查询，展示行为与旧版一致
        List<RequestLog> windowLogs = List.of(new RequestLog(), new RequestLog());
        List<RequestLog> fallbackLogs = List.of(new RequestLog(), new RequestLog(),
                new RequestLog(), new RequestLog());
        when(requestLogMapper.selectRecentTraces(any())).thenReturn(windowLogs);
        when(requestLogMapper.fallbackRecentTraces()).thenReturn(fallbackLogs);

        Map<String, Object> stats = collector.collect("today", "today", null);

        assertThat((List<?>) stats.get("recentLogs")).isSameAs(fallbackLogs);
    }

    @Test
    void recentLogs_windowHasTenTraces_usesWindowResult() {
        // 时间窗内已有 10 个 trace → 直接使用窗口结果，不触发回退查询
        List<RequestLog> windowLogs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            windowLogs.add(new RequestLog());
        }
        when(requestLogMapper.selectRecentTraces(any())).thenReturn(windowLogs);

        Map<String, Object> stats = collector.collect("today", "today", null);

        assertThat((List<?>) stats.get("recentLogs")).isSameAs(windowLogs);
        org.mockito.Mockito.verify(requestLogMapper, org.mockito.Mockito.never()).fallbackRecentTraces();
    }

    /** 构造 selectDailyTrend 的 SQL 返回行；avgTime/avgOutputSpeed 可为 null 模拟无有效记录 */
    private static Map<String, Object> trendRow(LocalDate date, long requests, long success, long fail,
                                                Double avgTime, Double avgOutputSpeed) {
        Map<String, Object> row = new HashMap<>();
        row.put("date", date.toString());
        row.put("requests", requests);
        row.put("success", success);
        row.put("fail", fail);
        row.put("avg_time", avgTime);
        row.put("avg_output_speed", avgOutputSpeed);
        return row;
    }
}