package com.myai.gateway.relay.balancer;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询选择策略
 * 按顺序轮流选择
 */
@Component
public class RoundRobinBalancer implements LoadBalancer {

    private final ConcurrentHashMap<Long, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public RoutingCandidate select(List<RoutingCandidate> candidates, Long modelId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        AtomicInteger counter = counters.computeIfAbsent(modelId, k -> new AtomicInteger(0));
        int index = Math.abs(counter.get()) % candidates.size();
        return candidates.get(index);
    }

    @Override
    public void markFailed(RoutingCandidate candidate) {
        // 失败后，将计数器前进到下一个
        counters.computeIfAbsent(candidate.getModelId(), k -> new AtomicInteger(0))
                .incrementAndGet();
    }
}
