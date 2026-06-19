package com.myai.gateway.relay.balancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 轮询选择策略（LRU - 最近最少使用）
 * 按最后使用时间排序，第一个距离上次最长的候选优先。
 * 同一候选的失败重试仍由 RelayService 处理（故障转移），
 * 从列表中移除失败候选后，下一次 select 自然落到下一个最久未使用的候选。
 */
@Component
public class RoundRobinBalancer implements LoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(RoundRobinBalancer.class);

    @Override
    public RoutingCandidate select(List<RoutingCandidate> candidates, Long modelId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // 按渠道模型的 lastUsedAt ASC 排序（null 表示从未使用，排最前）
        // 最久未使用的排第一个
        candidates.sort(Comparator.comparing(
                (RoutingCandidate c) -> c.getChannelModel().getLastUsedAt(),
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return candidates.get(0);
    }

    @Override
    public void markSuccess(RoutingCandidate candidate) {
        // 不需要额外操作，updateLastUsed 在 RelayService 中已更新数据库，
        // 下次 select 时会根据最新的 lastUsedAt 重新排序
    }

    @Override
    public void markFailed(RoutingCandidate candidate) {
        // 失败由 RelayService 处理（从列表中移除），无需额外操作
    }
}
