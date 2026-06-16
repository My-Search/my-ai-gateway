package com.myai.gateway.relay.balancer;

import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelApiKey;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.ModelChannelRel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FailoverBalancer 单元测试
 * 验证严格顺序选择行为
 */
class FailoverBalancerTest {

    private final FailoverBalancer balancer = new FailoverBalancer();

    @Test
    void select_returnsFirstCandidate() {
        List<RoutingCandidate> candidates = List.of(
                createCandidate(1L, "a1", "ak1"),
                createCandidate(2L, "a2", "ak1"),
                createCandidate(3L, "b1", "bk1"));

        RoutingCandidate selected = balancer.select(candidates, 1L);

        assertThat(selected.getChannelModel().getModelName()).isEqualTo("a1");
        assertThat(selected.getChannelApiKey().getKeyName()).isEqualTo("ak1");
    }

    @Test
    void select_afterMarkFailed_stillReturnsFirstRemainingCandidate() {
        RoutingCandidate c1 = createCandidate(1L, "a1", "ak1");
        RoutingCandidate c2 = createCandidate(2L, "a2", "ak1");
        RoutingCandidate c3 = createCandidate(3L, "b1", "bk1");
        List<RoutingCandidate> candidates = new ArrayList<>(List.of(c1, c2, c3));

        // 第一次选择 a1
        assertThat(balancer.select(candidates, 1L).getChannelModel().getModelName()).isEqualTo("a1");

        // 模拟 RelayService 移除失败候选后，应选中下一个 a2
        candidates.remove(c1);
        assertThat(balancer.select(candidates, 1L).getChannelModel().getModelName()).isEqualTo("a2");

        // 继续移除，选中 b1
        candidates.remove(c2);
        assertThat(balancer.select(candidates, 1L).getChannelModel().getModelName()).isEqualTo("b1");
    }

    @Test
    void select_emptyList_returnsNull() {
        assertThat(balancer.select(List.of(), 1L)).isNull();
    }

    private RoutingCandidate createCandidate(long id, String modelName, String keyName) {
        Channel channel = new Channel();
        channel.setId(1L);
        channel.setName("A");

        ChannelModel channelModel = new ChannelModel();
        channelModel.setId(id);
        channelModel.setChannelId(1L);
        channelModel.setModelName(modelName);

        ChannelApiKey apiKey = new ChannelApiKey();
        apiKey.setId(id * 10);
        apiKey.setChannelId(1L);
        apiKey.setKeyName(keyName);

        ModelChannelRel rel = new ModelChannelRel(100L, id);
        rel.setModelId(100L);

        return new RoutingCandidate(rel, channel, channelModel, apiKey);
    }
}
