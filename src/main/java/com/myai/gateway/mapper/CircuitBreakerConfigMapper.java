package com.myai.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myai.gateway.entity.CircuitBreakerConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CircuitBreakerConfigMapper extends BaseMapper<CircuitBreakerConfig> {
}
