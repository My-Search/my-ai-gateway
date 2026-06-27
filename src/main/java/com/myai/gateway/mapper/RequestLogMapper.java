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
     * @param modelName 入口模型名（精确匹配，可选）
     * @param startTime 开始时间（可选）
     * @param endTime   结束时间（可选）
     */
    @Select("<script>"
            + "SELECT trace_id FROM request_logs"
            + "<where>"
            + "<if test='modelName != null and modelName != \"\"'>AND model_name = #{modelName}</if>"
            + "<if test='startTime != null'>AND created_at &gt;= #{startTime}</if>"
            + "<if test='endTime != null'>AND created_at &lt;= #{endTime}</if>"
            + "</where>"
            + " GROUP BY trace_id ORDER BY MAX(created_at) DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<String> selectTraceIdsByFilters(@Param("modelName") String modelName,
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
            + "<if test='startTime != null'>AND created_at &gt;= #{startTime}</if>"
            + "<if test='endTime != null'>AND created_at &lt;= #{endTime}</if>"
            + "</where>"
            + "</script>")
    long countDistinctTracesByFilters(@Param("modelName") String modelName,
                                      @Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime);

    /**
     * 根据 traceId 查询该 trace 下 channel_name 非空的日志数量
     */
    @Select("SELECT COUNT(*) FROM request_logs WHERE trace_id = #{traceId} AND channel_name IS NOT NULL AND channel_name != ''")
    int countByTraceIdWithChannel(@Param("traceId") String traceId);

    // ==================== Dashboard 聚合查询 ====================

    /**
     * 获取今日聚合统计，一次查询替代全量加载后内存聚合
     */
    @Select("SELECT " +
            "COALESCE(SUM(CASE WHEN phase = 'start' THEN 1 ELSE 0 END), 0) as today_requests, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN 1 ELSE 0 END), 0) as today_success, " +
            "COALESCE(SUM(CASE WHEN phase = 'fail' THEN 1 ELSE 0 END), 0) as today_fail, " +
            "AVG(CASE WHEN response_time_ms > 0 THEN response_time_ms ELSE NULL END) as avg_response_time, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(prompt_tokens, 0) ELSE 0 END), 0) as prompt_tokens, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(completion_tokens, 0) ELSE 0 END), 0) as completion_tokens, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as total_tokens " +
            "FROM request_logs WHERE created_at >= #{todayStart}")
    Map<String, Object> selectTodayAggregatedStats(@Param("todayStart") LocalDateTime todayStart);

    /**
     * 获取昨日的 start 请求数（同比对比）
     */
    @Select("SELECT COALESCE(COUNT(*), 0) FROM request_logs WHERE phase = 'start' AND created_at >= #{start} AND created_at < #{end}")
    long selectYesterdayStartCount(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 近 N 天每日趋势，一次 GROUP BY 替代逐日循环
     */
    @Select("SELECT DATE(created_at) as date, " +
            "COALESCE(SUM(CASE WHEN phase = 'start' THEN 1 ELSE 0 END), 0) as requests, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN 1 ELSE 0 END), 0) as success, " +
            "COALESCE(SUM(CASE WHEN phase = 'fail' THEN 1 ELSE 0 END), 0) as fail " +
            "FROM request_logs WHERE created_at >= #{since} " +
            "GROUP BY DATE(created_at) ORDER BY date ASC")
    List<Map<String, Object>> selectDailyTrend(@Param("since") LocalDateTime since);

    /**
     * 渠道排行 Top10
     */
    @Select("SELECT channel_name as name, " +
            "COALESCE(SUM(CASE WHEN phase = 'start' THEN 1 ELSE 0 END), 0) as requests, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN 1 ELSE 0 END), 0) as success, " +
            "AVG(CASE WHEN response_time_ms > 0 THEN response_time_ms ELSE NULL END) as avg_time, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as total_tokens " +
            "FROM request_logs WHERE created_at >= #{since} AND channel_name IS NOT NULL AND channel_name != '' " +
            "GROUP BY channel_name ORDER BY requests DESC LIMIT 10")
    List<Map<String, Object>> selectChannelRank(@Param("since") LocalDateTime since);

    /**
     * 入口模型排行 Top10
     */
    @Select("SELECT model_name as name, " +
            "COALESCE(SUM(CASE WHEN phase = 'start' THEN 1 ELSE 0 END), 0) as requests, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN 1 ELSE 0 END), 0) as success, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as total_tokens " +
            "FROM request_logs WHERE created_at >= #{since} AND model_name IS NOT NULL AND model_name != '' " +
            "GROUP BY model_name ORDER BY requests DESC LIMIT 10")
    List<Map<String, Object>> selectEntryModelRank(@Param("since") LocalDateTime since);

    /**
     * 渠道模型排行 Top10（按 channel_name + channel_model_name 复合分组）
     */
    @Select("SELECT channel_name, channel_model_name as name, " +
            "COALESCE(SUM(CASE WHEN phase = 'start' THEN 1 ELSE 0 END), 0) as requests, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN 1 ELSE 0 END), 0) as success, " +
            "COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) as total_tokens " +
            "FROM request_logs WHERE created_at >= #{since} AND channel_model_name IS NOT NULL AND channel_model_name != '' " +
            "GROUP BY channel_name, channel_model_name ORDER BY requests DESC LIMIT 10")
    List<Map<String, Object>> selectChannelModelRank(@Param("since") LocalDateTime since);
}
