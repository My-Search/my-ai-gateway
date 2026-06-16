package com.myai.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myai.gateway.entity.AdminConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminConfigMapper extends BaseMapper<AdminConfig> {

    @Select("SELECT config_value FROM admin_config WHERE config_key = #{key}")
    String getValueByKey(@Param("key") String key);
}