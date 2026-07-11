package com.myai.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myai.gateway.entity.RequestLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface RequestLogMapper extends BaseMapper<RequestLog> {

    // ==================== Dashboard 今日10分钟级趋势 ====================

    /**
     * 今日全部请求每10分钟聚合（trace-level 去重）
     * 返回 { bucket: "12:00", requests: long, success: long }
     */
    @Select("SELECT " +
            "printf('%02d:%02d', CAST(STRFTIME('%H', DATETIME(created_at, '+8 hours')) AS INTEGER), (CAST(STRFTIME('%M', DATETIME(created_at, '+8 hours')) AS INTEGER) / 10) * 10) as bucket, " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as requests, " +
            "COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) as success " +
            "FROM request_logs WHERE created_at >= #{since} AND created_at < #{until} " +
            "GROUP BY bucket ORDER BY bucket ASC")
    List<Map<String, Object>> selectTodayBucketTrend(@Param("since") LocalDateTime since,
                                                      @Param("until") LocalDateTime until);

    /**
     * 今日按入口模型每10分钟聚合（trace-level 去重）
     * 返回 { bucket: "12:00", model_name, requests: long }
     */
    @Select("SELECT " +
            "printf('%02d:%02d', CAST(STRFTIME('%H', DATETIME(created_at, '+8 hours')) AS INTEGER), (CAST(STRFTIME('%M', DATETIME(created_at, '+8 hours')) AS INTEGER) / 10) * 10) as bucket, " +
            "model_name, " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as requests " +
            "FROM request_logs WHERE created_at >= #{since} AND created_at < #{until} " +
            "AND model_name IS NOT NULL AND model_name != '' " +
            "GROUP BY bucket, model_name ORDER BY bucket ASC")
    List<Map<String, Object>> selectTodayBucketEntryModelTrend(@Param("since") LocalDateTime since,
                                                                @Param("until") LocalDateTime until);

    /**
     * 今日按渠道模型每10分钟聚合（trace-level 去重）
     * 返回 { bucket: "12:00", channel_name, name: model_name, requests: long }
     */
    @Select("SELECT " +
            "printf('%02d:%02d', CAST(STRFTIME('%H', DATETIME(created_at, '+8 hours')) AS INTEGER), (CAST(STRFTIME('%M', DATETIME(created_at, '+8 hours')) AS INTEGER) / 10) * 10) as bucket, " +
            "channel_name, channel_model_name as name, " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as requests " +
            "FROM request_logs WHERE created_at >= #{since} AND created_at < #{until} " +
            "AND channel_model_name IS NOT NULL AND channel_model_name != '' " +
            "GROUP BY bucket, channel_name, channel_model_name ORDER BY bucket ASC")
    List<Map<String, Object>> selectTodayBucketChannelModelTrend(@Param("since") LocalDateTime since,
                                                                  @Param("until") LocalDateTime until);

    /**
     * 分页获取去重后的 traceId
     */
    @Select("SELECT trace_id FROM request_logs GROUP BY trace_id ORDER BY MAX(created_at) DESC LIMIT #{limit} OFFSET #{offset}")
    List<String> selectTraceIdsByPage(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 获取去重后的 traceId 总数
     */
    @Select("SELECT COUNT(DISTINCT trace_id) FROM request_logs")
    long countDistinctTraces();

    /**
     * 根据条件过滤后的分页 traceId 查询
     *
     * @param modelName       入口模型名（精确匹配，可选）
     * @param gatewayApiKeyId 网关 API Key 主键（精确匹配，可选；优先于 apiKeyName 使用）
     * @param apiKeyName      API Key 名（精确匹配，可选；旧字段，对应渠道 API Key 名）
     * @param startTime       开始时间（可选）
     * @param endTime         结束时间（可选）
     */
    @Select("<script>"
            + "SELECT trace_id FROM request_logs"
            + "<where>"
            + "<if test='modelName != null and modelName != \"\"'>AND model_name = #{modelName}</if>"
            + "<if test='gatewayApiKeyId != null'>AND gateway_api_key_id = #{gatewayApiKeyId}</if>"
            + "<if test='apiKeyName != null and apiKeyName != \"\"'>AND api_key_name = #{apiKeyName}</if>"
            + "<if test='startTime != null'>AND created_at &gt;= #{startTime}</if>"
            + "<if test='endTime != null'>AND created_at &lt;= #{endTime}</if>"
            + "</where>"
            + " GROUP BY trace_id ORDER BY MAX(created_at) DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<String> selectTraceIdsByFilters(@Param("modelName") String modelName,
                                         @Param("gatewayApiKeyId") Long gatewayApiKeyId,
                                         @Param("apiKeyName") String apiKeyName,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    /**
     * 根据条件过滤后的 traceId 总数
     */
    @Select("<script>"
            + "SELECT COUNT(DISTINCT trace_id) FROM request_logs"
            + "<where>"
            + "<if test='modelName != null and modelName != \"\"'>AND model_name = #{modelName}</if>"
            + "<if test='gatewayApiKeyId != null'>AND gateway_api_key_id = #{gatewayApiKeyId}</if>"
            + "<if test='apiKeyName != null and apiKeyName != \"\"'>AND api_key_name = #{apiKeyName}</if>"
            + "<if test='startTime != null'>AND created_at &gt;= #{startTime}</if>"
            + "<if test='endTime != null'>AND created_at &lt;= #{endTime}</if>"
            + "</where>"
            + "</script>")
    long countDistinctTracesByFilters(@Param("modelName") String modelName,
                                      @Param("gatewayApiKeyId") Long gatewayApiKeyId,
                                      @Param("apiKeyName") String apiKeyName,
                                      @Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime);

    /**
     * 根据 traceId 查询该 trace 下 channel_name 非空的日志数量
     */
    @Select("SELECT COUNT(*) FROM request_logs WHERE trace_id = #{traceId} AND channel_name IS NOT NULL AND channel_name != ''")
    int countByTraceIdWithChannel(@Param("traceId") String traceId);

    // ==================== Dashboard 聚合查询 ====================

    /**
     * 获取指定月份聚合统计（请求数使用 trace 去重，tokens 仅统计 success）
     */
    @Select("SELECT " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as monthly_requests, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(prompt_tokens, 0) ELSE 0 END), 0) as monthly_prompt_tokens, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(completion_tokens, 0) ELSE 0 END), 0) as monthly_completion_tokens, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as monthly_total_tokens, " +
            "COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) as monthly_success, " +
            "AVG(CASE WHEN response_time_ms > 0 THEN response_time_ms ELSE NULL END) as avg_response_time, " +
            "COUNT(DISTINCT CASE WHEN phase = 'fail' THEN trace_id END) as monthly_fail " +
            "FROM request_logs WHERE created_at >= #{monthStart} AND created_at < #{monthEnd}")
    Map<String, Object> selectMonthlyAggregatedStats(@Param("monthStart") LocalDateTime monthStart,
                                                      @Param("monthEnd") LocalDateTime monthEnd);

    /**
     * 获取今日聚合统计（trace-level 去重，tokens 仅统计 success）
     * <p>
     * today_requests:   今日发起请求的唯一 trace 数（phase='start' 去重）
     * today_success:   今日至少有一次 success 的唯一 trace 数（含跨日完成）
     * avg_response_time: 成功/失败尝试的平均响应时间
     * 注：today_fail 在 Java 层通过 MAX(0, today_requests - today_success) 计算
     * </p>
     */
    @Select("SELECT " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as today_requests, " +
            "COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) as today_success, " +
            "AVG(CASE WHEN response_time_ms > 0 THEN response_time_ms ELSE NULL END) as avg_response_time, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(prompt_tokens, 0) ELSE 0 END), 0) as prompt_tokens, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(completion_tokens, 0) ELSE 0 END), 0) as completion_tokens, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as total_tokens " +
            "FROM request_logs WHERE created_at >= #{todayStart}")
    Map<String, Object> selectTodayAggregatedStats(@Param("todayStart") LocalDateTime todayStart);

    /**
     * 获取昨日的唯一请求数（trace-level 去重，同比对比）
     */
    @Select("SELECT COALESCE(COUNT(DISTINCT trace_id), 0) FROM request_logs WHERE phase = 'start' AND created_at >= #{start} AND created_at < #{end}")
    long selectYesterdayStartCount(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 近 N 天每日趋势（trace-level 去重），一次 GROUP BY 替代逐日循环
     */
    @Select("SELECT DATE(created_at) as date, " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as requests, " +
            "COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) as success, " +
            "COUNT(DISTINCT CASE WHEN phase = 'fail' THEN trace_id END) as fail " +
            "FROM request_logs WHERE created_at >= #{since} " +
            "GROUP BY DATE(created_at) ORDER BY date ASC")
    List<Map<String, Object>> selectDailyTrend(@Param("since") LocalDateTime since);

    /**
     * 渠道排行 Top10（trace-level 去重，支持可选 end 时间上限）
     */
    @Select("<script>" +
            "SELECT channel_name as name, " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as requests, " +
            "COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) as success, " +
            "AVG(CASE WHEN response_time_ms > 0 THEN response_time_ms ELSE NULL END) as avg_time, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as total_tokens " +
            "FROM request_logs WHERE created_at &gt;= #{since} " +
            "<if test='end != null'>AND created_at &lt; #{end}</if> " +
            "AND channel_name IS NOT NULL AND channel_name != '' " +
            "GROUP BY channel_name ORDER BY requests DESC LIMIT 10" +
            "</script>")
    List<Map<String, Object>> selectChannelRank(@Param("since") LocalDateTime since, @Param("end") LocalDateTime end);

    /**
     * 入口模型排行 Top10（trace-level 去重，支持可选 end 时间上限）
     */
    @Select("<script>" +
            "SELECT model_name as name, " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as requests, " +
            "COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) as success, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as total_tokens " +
            "FROM request_logs WHERE created_at &gt;= #{since} " +
            "<if test='end != null'>AND created_at &lt; #{end}</if> " +
            "AND model_name IS NOT NULL AND model_name != '' " +
            "GROUP BY model_name ORDER BY requests DESC LIMIT 10" +
            "</script>")
    List<Map<String, Object>> selectEntryModelRank(@Param("since") LocalDateTime since, @Param("end") LocalDateTime end);

    /**
     * 渠道模型排行 Top10（trace-level 去重，支持可选 end 时间上限）
     */
    @Select("<script>" +
            "SELECT channel_name, channel_model_name as name, " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as requests, " +
            "COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) as success, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as total_tokens " +
            "FROM request_logs WHERE created_at &gt;= #{since} " +
            "<if test='end != null'>AND created_at &lt; #{end}</if> " +
            "AND channel_model_name IS NOT NULL AND channel_model_name != '' " +
            "GROUP BY channel_name, channel_model_name ORDER BY requests DESC LIMIT 10" +
            "</script>")
    List<Map<String, Object>> selectChannelModelRank(@Param("since") LocalDateTime since, @Param("end") LocalDateTime end);

    /**
     * 统计今日发起的请求中从未 success（完全失败）的 trace 数
     */
    @Select("SELECT COUNT(DISTINCT r1.trace_id) FROM request_logs r1 " +
            "WHERE r1.phase = 'start' AND r1.created_at >= #{since} " +
            "AND NOT EXISTS (SELECT 1 FROM request_logs r2 WHERE r2.trace_id = r1.trace_id AND r2.phase = 'success')")
    long countFailedTraces(@Param("since") LocalDateTime since);

    // ==================== 渠道列表用量统计 ====================

    /**
     * 所有渠道的汇总用量统计（用于渠道列表页展示）
     * <p>
     * 在 SQL 层面完成 GROUP BY 聚合，替代原来的全量加载 + Java 4 次流处理，
     * 仅扫描 phase='success' 的行，按 channel_name 分组聚合。
     * </p>
     *
     * @return List of { channel_name, request_count, prompt_tokens, completion_tokens, total_tokens }
     */
    @Select("SELECT channel_name, " +
            "COUNT(*) as request_count, " +
            "COALESCE(SUM(COALESCE(prompt_tokens, 0)), 0) as prompt_tokens, " +
            "COALESCE(SUM(COALESCE(completion_tokens, 0)), 0) as completion_tokens, " +
            "COALESCE(SUM(COALESCE(total_tokens, 0)), 0) as total_tokens " +
            "FROM request_logs " +
            "WHERE phase = 'success' " +
            "AND channel_name IS NOT NULL AND channel_name != '' " +
            "GROUP BY channel_name")
    List<Map<String, Object>> selectChannelSummaryStats();

    /**
     * 获取最近 10 个独特 trace 的最新日志条目（trace-level 去重）
     * <p>
     * 替代原来的 LIMIT 10 原始日志，确保"最近活动"面板显示不同的请求而非被同一条 trace 刷屏。
     * 子查询取每个 trace_id 的最新 id，再 JOIN 回全表获取完整行。
     * </p>
     */
    @Select("SELECT r.* FROM request_logs r " +
            "INNER JOIN (" +
            "  SELECT MAX(id) as id FROM request_logs " +
            "  GROUP BY trace_id " +
            "  ORDER BY MAX(created_at) DESC LIMIT 10" +
            ") latest ON r.id = latest.id " +
            "ORDER BY r.created_at DESC")
    List<RequestLog> selectRecentTraces();

    // ==================== 请求日志使用图表（按日 + 入口模型聚合） ====================

    /**
     * 在指定时间范围内，按 (date, model_name) 聚合 token 用量。
     * <p>
     * 用于"请求日志"页面顶部的"使用历史"堆叠柱状图：每日一根柱、按入口模型堆叠。
     * 仅统计成功请求的 token（与其他用量统计保持一致口径）。可选按入口模型 / API Key 过滤。
     * </p>
     *
     * @param since            起始时间（含）
     * @param until            结束时间（不含）
     * @param modelName        入口模型名（可选；为 null/空时不过滤）
     * @param gatewayApiKeyId  网关 API Key 主键（可选；优先于 apiKeyName 使用）
     * @param apiKeyName       API Key 名（可选；旧字段，对应渠道 API Key 名；为 null/空时不过滤）
     * @return 每行包含 date (yyyy-MM-dd)、model_name、total_tokens
     */
    @Select("<script>" +
            "SELECT DATE(created_at) as date, " +
            "model_name, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as total_tokens " +
            "FROM request_logs " +
            "WHERE created_at &gt;= #{since} AND created_at &lt; #{until} " +
            "AND model_name IS NOT NULL AND model_name != '' " +
            "<if test='modelName != null and modelName != \"\"'>AND model_name = #{modelName}</if>" +
            "<if test='gatewayApiKeyId != null'>AND gateway_api_key_id = #{gatewayApiKeyId}</if>" +
            "<if test='apiKeyName != null and apiKeyName != \"\"'>AND api_key_name = #{apiKeyName}</if>" +
            "GROUP BY DATE(created_at), model_name " +
            "ORDER BY date ASC, total_tokens DESC" +
            "</script>")
    List<Map<String, Object>> selectDailyModelTokenUsage(@Param("since") LocalDateTime since,
                                                        @Param("until") LocalDateTime until,
                                                        @Param("modelName") String modelName,
                                                        @Param("gatewayApiKeyId") Long gatewayApiKeyId,
                                                        @Param("apiKeyName") String apiKeyName);

    /**
     * 在指定时间范围内，按 trace 级最终结果按 (date, channel_model_name) 聚合请求数。
     * <p>
     * 用于"请求日志"页面顶部"使用历史"图表选择"渠道模型"模式时：
     * 按 trace 统计，请求最终成功（有 phase=success）则归入该次的渠道模型名，
     * 请求最终失败（无 phase=success）则统一归为"请求失败"。
     * 可选按入口模型 / API Key 过滤。
     * </p>
     *
     * @param since            起始时间（含）
     * @param until            结束时间（不含）
     * @param modelName        入口模型名（可选；为 null/空时不过滤）
     * @param gatewayApiKeyId  网关 API Key 主键（可选；优先于 apiKeyName 使用）
     * @param apiKeyName       API Key 名（可选；旧字段；为 null/空时不过滤）
     * @return 每行包含 date (yyyy-MM-dd)、model_name、total_tokens（此处表示请求数）
     */
    @Select("<script>" +
            "SELECT date, model_name, COUNT(1) as total_tokens " +
            "FROM ( " +
            "  SELECT r.trace_id, " +
            "         DATE(MIN(r.created_at)) as date, " +
            "         CASE WHEN MAX(CASE WHEN r.phase = 'success' THEN 1 ELSE 0 END) = 1 " +
            "              THEN MAX(CASE WHEN r.phase = 'success' THEN r.channel_model_name END) " +
            "              ELSE '\u8bf7\u6c42\u5931\u8d25' " +
            "         END as model_name " +
            "  FROM request_logs r " +
            "  WHERE r.trace_id IN ( " +
            "    SELECT DISTINCT trace_id FROM request_logs " +
            "    WHERE created_at &gt;= #{since} AND created_at &lt; #{until} " +
            "    AND channel_model_name IS NOT NULL AND channel_model_name != '' " +
            "    <if test='modelName != null and modelName != \"\"'>AND model_name = #{modelName}</if> " +
            "    <if test='gatewayApiKeyId != null'>AND gateway_api_key_id = #{gatewayApiKeyId}</if> " +
            "    <if test='apiKeyName != null and apiKeyName != \"\"'>AND api_key_name = #{apiKeyName}</if> " +
            "  ) " +
            "  GROUP BY r.trace_id " +
            ") t " +
            "GROUP BY date, model_name " +
            "ORDER BY date ASC, total_tokens DESC" +
            "</script>")
    List<Map<String, Object>> selectDailyChannelModelTokenUsage(@Param("since") LocalDateTime since,
                                                                @Param("until") LocalDateTime until,
                                                                @Param("modelName") String modelName,
                                                                @Param("gatewayApiKeyId") Long gatewayApiKeyId,
                                                                @Param("apiKeyName") String apiKeyName);

    // ==================== 模型管理页统计 ====================

    /**
     * 按入口模型聚合今日统计（trace-level 去重）。
     * 返回 { model_name, requests, success, avg_response_time }
     */
    @Select("SELECT " +
            "model_name, " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as requests, " +
            "COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) as success, " +
            "AVG(CASE WHEN response_time_ms > 0 THEN response_time_ms ELSE NULL END) as avg_response_time " +
            "FROM request_logs WHERE created_at >= #{since} " +
            "AND model_name IS NOT NULL AND model_name != '' " +
            "GROUP BY model_name")
    List<Map<String, Object>> selectTodayModelStats(@Param("since") LocalDateTime since);

    /**
     * 今日按入口模型每10分钟聚合请求数（trace-level 去重）。
     * 返回 { bucket, model_name, requests }
     */
    @Select("SELECT " +
            "printf('%02d:%02d', CAST(STRFTIME('%H', DATETIME(created_at, '+8 hours')) AS INTEGER), (CAST(STRFTIME('%M', DATETIME(created_at, '+8 hours')) AS INTEGER) / 10) * 10) as bucket, " +
            "model_name, " +
            "COUNT(DISTINCT CASE WHEN phase = 'start' THEN trace_id END) as requests " +
            "FROM request_logs WHERE created_at >= #{since} AND created_at < #{until} " +
            "AND model_name IS NOT NULL AND model_name != '' " +
            "GROUP BY bucket, model_name ORDER BY bucket ASC")
    List<Map<String, Object>> selectTodayModelBucketTrend(@Param("since") LocalDateTime since,
                                                           @Param("until") LocalDateTime until);
}
