package com.myai.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myai.gateway.entity.ChannelApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChannelApiKeyMapper extends BaseMapper<ChannelApiKey> {

    /**
     * 获取 SQLite 最后插入的行 ID
     */
    @Select("SELECT last_insert_rowid()")
    Long getLastInsertId();

    /**
     * 获取渠道下所有启用的 API Keys
     */
    @Select("SELECT * FROM channel_api_keys WHERE channel_id = #{channelId} AND enabled = 1 ORDER BY sort_order")
    List<ChannelApiKey> selectEnabledByChannelId(Long channelId);

    /**
     * 获取渠道下所有 API Keys（不分启用状态）
     */
    @Select("SELECT * FROM channel_api_keys WHERE channel_id = #{channelId} ORDER BY sort_order")
    List<ChannelApiKey> selectAllByChannelId(Long channelId);
}