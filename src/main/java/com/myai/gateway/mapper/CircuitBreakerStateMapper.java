package com.myai.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myai.gateway.entity.CircuitBreakerState;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CircuitBreakerStateMapper extends BaseMapper<CircuitBreakerState> {
}
