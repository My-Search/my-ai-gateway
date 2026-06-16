package com.myai.gateway.relay.balancer;

import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.ModelChannelRel;

/**
 * 路由候选对象
 * 封装了一次路由决策所需的全部信息，包含关联关系、渠道、渠道模型和 API Key。
 * 不可变对象，构造后字段不可修改。
 */
public class RoutingCandidate {

    private final ModelChannelRel rel;
    private final Channel channel;
    private final ChannelModel channelModel;
    private final ChannelApiKey channelApiKey;

    public RoutingCandidate(ModelChannelRel rel, Channel channel,
                            ChannelModel channelModel, ChannelApiKey channelApiKey) {
        this.rel = rel;
        this.channel = channel;
        this.channelModel = channelModel;
        this.channelApiKey = channelApiKey;
    }

    public ModelChannelRel getRel() {
        return rel;
    }

    public Channel getChannel() {
        return channel;
    }

    public ChannelModel getChannelModel() {
        return channelModel;
    }

    public ChannelApiKey getChannelApiKey() {
        return channelApiKey;
    }

    /**
     * 获取自定义模型 ID，委托给关联实体
     */
    public Long getModelId() {
        return rel.getModelId();
    }
}
