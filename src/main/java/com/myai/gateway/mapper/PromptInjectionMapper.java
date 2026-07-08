package com.myai.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myai.gateway.entity.PromptInjection;
import org.apache.ibatis.annotations.Mapper;

/**
 * Prompt 注入规则 Mapper
 */
@Mapper
public interface PromptInjectionMapper extends BaseMapper<PromptInjection> {
}
