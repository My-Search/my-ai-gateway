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
 * RoundRobinBalancer 单元测试
 * 验证轮询选择行为及按 modelId 隔离计数器
 */
class RoundRobinBalancerTest {

    private final RoundRobinBalancer balancer = new RoundRobinBalancer();

    @Test
    void select_roundRobinAcrossCandidates() {
        List<RoutingCandidate> candidates = List.of(
                createCandidate(1L, "a1", "ak1"),
                createCandidate(2L, "a2", "ak1"),
                createCandidate(3L, "b1", "bk1"));

        // 连续 3 次调用应覆盖所有候选（顺序可能因取模不同，但应覆盖全部 3 个）
        String first = balancer.select(candidates, 1L).getChannelModel().getModelName();
        String second = balancer.select(candidates, 1L).getChannelModel().getModelName();
        String third = balancer.select(candidates, 1L).getChannelModel().getModelName();

        assertThat(List.of(first, second, third))
                .containsExactlyInAnyOrder("a1", "a2", "b1");
    }

    @Test
    void select_countersIsolatedByModelId() {
        // 模型 A 有 3 个候选，模型 B 有 2 个候选
        List<RoutingCandidate> candidatesA = List.of(
                createCandidate(1L, "a1", "ak1"),
                createCandidate(2L, "a2", "ak1"),
                createCandidate(3L, "a3", "ak1"));
        List<RoutingCandidate> candidatesB = List.of(
                createCandidate(4L, "b1", "bk1"),
                createCandidate(5L, "b2", "bk1"));

        // 交替调用模型 A 和模型 B，验证 B 的两个候选都能被选中
        // 如果使用全局计数器（如修复前），B 在交替调用下会始终命中同一候选
        balancer.select(candidatesA, 100L);
        String bFirst = balancer.select(candidatesB, 200L).getChannelModel().getModelName();
        balancer.select(candidatesA, 100L);
        String bSecond = balancer.select(candidatesB, 200L).getChannelModel().getModelName();

        assertThat(bFirst).isNotEqualTo(bSecond);
    }

    @Test
    void select_emptyList_returnsNull() {
        assertThat(balancer.select(List.of(), 1L)).isNull();
    }

    @Test
    void select_nullList_returnsNull() {
        assertThat(balancer.select(null, 1L)).isNull();
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
