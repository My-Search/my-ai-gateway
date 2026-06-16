package com.myai.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myai.gateway.entity.RequestLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RequestLogMapper extends BaseMapper<RequestLog> {

    /**
     * 分页获取去重后的 traceId
     */
    @Select("SELECT trace_id FROM request_logs GROUP BY trace_id ORDER BY MAX(created_at) DESC LIMIT #{limit} OFFSET #{offset}")
    List<String> selectTraceIdsByPage(int offset, int limit);

    /**
     * 获取去重后的 traceId 总数
     */
    @Select("SELECT COUNT(DISTINCT trace_id) FROM request_logs")
    long countDistinctTraces();
}
