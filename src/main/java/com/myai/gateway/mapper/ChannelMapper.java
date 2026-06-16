package com.myai.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myai.gateway.entity.Channel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChannelMapper extends BaseMapper<Channel> {

    /**
     * 获取 SQLite 最后插入的行 ID
     * MyBatis Plus 无法正确获取 SQLite 的自增 ID，需要手动查询
     */
    @Select("SELECT last_insert_rowid()")
    Long getLastInsertId();
}
