package com.myai.gateway.service;

import com.myai.gateway.mapper.RequestLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StatsService 单元测试 — 覆盖 getLogUsageChart 的核心组装逻辑。
 * <p>
 * 范围：只验证"按日×模型聚合"的服务层组装（days 完整性、模型排序、values 矩阵、过滤透传、空数据）。
 * 不做 SQL 集成测试（SQLite + MyBatis XML 行为由人工/集成测试覆盖）。
 * </p>
 */
class StatsServiceTest {

    private RequestLogMapper requestLogMapper;
    private StatsService service;

    @BeforeEach
    void setUp() {
        requestLogMapper = mock(RequestLogMapper.class);
        service = new StatsService(requestLogMapper);
    }

    @Test
    void chart_emptyMonth_returnsFullDayRangeAndEmptyModels() {
        // SQL 查不到任何行
        when(requestLogMapper.selectDailyModelTokenUsage(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = service.getLogUsageChart(2026, 6, null, null);

        // 6 月 30 天，days 长度必须是 30
        @SuppressWarnings("unchecked")
        List<String> days = (List<String>) result.get("days");
        assertThat(days).hasSize(30);
        assertThat(days.get(0)).isEqualTo("2026-06-01");
        assertThat(days.get(29)).isEqualTo("2026-06-30");

        // 空数据：models 空、values 空、maxValue/totalValue 为 0
        assertThat(result.get("models")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) result.get("values");
        assertThat(values).isEmpty();
        assertThat(result.get("maxValue")).isEqualTo(0L);
        assertThat(result.get("totalValue")).isEqualTo(0L);
        assertThat(result.get("year")).isEqualTo(2026);
        assertThat(result.get("month")).isEqualTo(6);
    }

    @Test
    void chart_februaryNonLeap_returns28Days() {
        when(requestLogMapper.selectDailyModelTokenUsage(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = service.getLogUsageChart(2025, 2, null, null);
        @SuppressWarnings("unchecked")
        List<String> days = (List<String>) result.get("days");
        assertThat(days).hasSize(28);
        assertThat(days.get(27)).isEqualTo("2025-02-28");
    }

    @Test
    void chart_februaryLeap_returns29Days() {
        when(requestLogMapper.selectDailyModelTokenUsage(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = service.getLogUsageChart(2024, 2, null, null);
        @SuppressWarnings("unchecked")
        List<String> days = (List<String>) result.get("days");
        assertThat(days).hasSize(29);
        assertThat(days.get(28)).isEqualTo("2024-02-29");
    }

    @Test
    void chart_buildsDailyMatrixAndSortsModelsByTotalDesc() {
        // 模拟数据库返回：A 模型 6/15=100、6/16=50；B 模型 6/15=200；C 模型 6/15=300
        // B 总和=200、A 总和=150、C 总和=300 → 期望排序 C > B > A
        when(requestLogMapper.selectDailyModelTokenUsage(any(), any(), any(), any(), any()))
                .thenReturn(List.of(
                        row("2026-06-15", "A", 100L),
                        row("2026-06-16", "A", 50L),
                        row("2026-06-15", "B", 200L),
                        row("2026-06-15", "C", 300L)
                ));

        Map<String, Object> result = service.getLogUsageChart(2026, 6, null, null);

        // 模型按总用量降序：C(300) > B(200) > A(150)
        @SuppressWarnings("unchecked")
        List<String> models = (List<String>) result.get("models");
        assertThat(models).containsExactly("C", "B", "A");

        // values 矩阵：每个模型一个 30 长度数组
        @SuppressWarnings("unchecked")
        Map<String, List<Long>> values = (Map<String, List<Long>>) result.get("values");
        assertThat(values.get("C")).hasSize(30);
        // C 仅 6/15 有值
        assertThat(values.get("C").get(14)).isEqualTo(300L);
        // A 在 6/15=100, 6/16=50
        assertThat(values.get("A").get(14)).isEqualTo(100L);
        assertThat(values.get("A").get(15)).isEqualTo(50L);
        // A 在 6/14 = 0（缺省为 0）
        assertThat(values.get("A").get(13)).isEqualTo(0L);
        // B 仅 6/15 有值
        assertThat(values.get("B").get(14)).isEqualTo(200L);

        // maxValue=300（C 单日最高），totalValue=300+200+150=650
        assertThat(result.get("maxValue")).isEqualTo(300L);
        assertThat(result.get("totalValue")).isEqualTo(650L);
    }

    @Test
    void chart_passesSinceUntilAsHalfOpenInterval() {
        when(requestLogMapper.selectDailyModelTokenUsage(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getLogUsageChart(2026, 6, null, null);

        // 验证 [since, until) 半开区间：since=2026-06-01 00:00、until=2026-07-01 00:00
        ArgumentCaptor<LocalDateTime> sinceCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> untilCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(requestLogMapper).selectDailyModelTokenUsage(
                sinceCap.capture(), untilCap.capture(), eq(null), eq(null), eq(null));
        assertThat(sinceCap.getValue()).isEqualTo(LocalDateTime.of(2026, 6, 1, 0, 0));
        assertThat(untilCap.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
    }

    @Test
    void chart_normalizesEmptyFiltersToNull() {
        when(requestLogMapper.selectDailyModelTokenUsage(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getLogUsageChart(2026, 6, "", "  ");

        // 空串/空白应被归一为 null，避免 SQL 触发 '' 过滤而误匹配
        ArgumentCaptor<String> modelCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(requestLogMapper).selectDailyModelTokenUsage(
                any(), any(), modelCap.capture(), eq(null), keyCap.capture());
        assertThat(modelCap.getValue()).isNull();
        assertThat(keyCap.getValue()).isNull();
    }

    @Test
    void chart_passesNonEmptyFilters() {
        when(requestLogMapper.selectDailyModelTokenUsage(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getLogUsageChart(2026, 6, "gpt-4o", "prod-key");

        ArgumentCaptor<String> modelCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(requestLogMapper).selectDailyModelTokenUsage(
                any(), any(), modelCap.capture(), eq(null), keyCap.capture());
        assertThat(modelCap.getValue()).isEqualTo("gpt-4o");
        assertThat(keyCap.getValue()).isEqualTo("prod-key");
    }

    @Test
    void chart_passesGatewayApiKeyIdToMapper() {
        // 新增 gateway_api_key_id 过滤：传 5 参版本时必须原样透传到 mapper
        when(requestLogMapper.selectDailyModelTokenUsage(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getLogUsageChart(2026, 6, null, 7L, "ignored-name");

        ArgumentCaptor<Long> keyIdCap = ArgumentCaptor.forClass(Long.class);
        verify(requestLogMapper).selectDailyModelTokenUsage(
                any(), any(), eq(null), keyIdCap.capture(), eq("ignored-name"));
        assertThat(keyIdCap.getValue()).isEqualTo(7L);
    }

    @Test
    void chart_ignoresRowsWithUnknownDateOrEmptyModel() {
        // 异常行：未识别日期、空 modelName —— 应被忽略而非污染矩阵
        when(requestLogMapper.selectDailyModelTokenUsage(any(), any(), any(), any(), any()))
                .thenReturn(List.of(
                        row("2026-06-15", "A", 100L),
                        row("2026-13-99", "B", 999L),     // 越界日期：忽略
                        row("2026-06-16", "", 50L),       // 空模型名：忽略
                        row("2026-06-16", null, 50L)     // null 模型名：忽略
                ));

        Map<String, Object> result = service.getLogUsageChart(2026, 6, null, null);

        @SuppressWarnings("unchecked")
        List<String> models = (List<String>) result.get("models");
        assertThat(models).containsExactly("A");
        @SuppressWarnings("unchecked")
        Map<String, List<Long>> values = (Map<String, List<Long>>) result.get("values");
        assertThat(values.get("A").get(14)).isEqualTo(100L);
        assertThat(result.get("totalValue")).isEqualTo(100L);
    }

    /** 工具方法：构造 SQL 返回行（HashMap，model 可为 null 以验证空值过滤）。 */
    private static Map<String, Object> row(String date, String model, long tokens) {
        Map<String, Object> m = new HashMap<>();
        m.put("date", date);
        m.put("model_name", model);
        m.put("total_tokens", tokens);
        return m;
    }
}
