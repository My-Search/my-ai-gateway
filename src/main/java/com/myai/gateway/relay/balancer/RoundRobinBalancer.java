package com.myai.gateway.relay.balancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轮询选择策略。
 * <p>采用原子计数器实现真正的 round-robin，每次 {@link #select} 调用计数器自增并按候选数取模，
 * 保证并发请求分散到不同候选，避免基于时间戳排序在高并发下因时间戳相同导致多个请求集中选同一候选。</p>
 *
 * <p>同一候选的失败重试仍由 RelayService 处理（故障转移）：失败候选从列表中移除后，
 * 下一次 select 的取模结果自然落到剩余列表中的某个候选。</p>
 *
 * <p>计数器按 modelId 维度独立维护，避免不同模型互相干扰。
 * 当候选列表大小变化（移除失败候选）时，取模结果仍均匀分布。</p>
 */
@Component
public class RoundRobinBalancer implements LoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(RoundRobinBalancer.class);

    /**
     * 按 modelId 维度独立维护的轮询计数器。
     * <p>使用 computeIfAbsent 保证并发安全：多个线程同时首次访问同一 modelId 时只会创建一个 AtomicLong。</p>
     */
    private final ConcurrentHashMap<Long, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public RoutingCandidate select(List<RoutingCandidate> candidates, Long modelId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int size = candidates.size();
        // 按 modelId 取对应计数器，确保不同模型互不干扰
        AtomicLong counter = counters.computeIfAbsent(
                modelId != null ? modelId : 0L, k -> new AtomicLong(0));
        // 原子自增并取模，保证并发请求获得不同索引
        long idx = Math.floorMod(counter.getAndIncrement(), size);
        RoutingCandidate selected = candidates.get((int) idx);
        log.debug("RoundRobin 选中候选 index={}/{} channel={} model={}",
                idx, size, selected.getChannel().getName(), selected.getChannelModel().getModelName());
        return selected;
    }

    @Override
    public void markSuccess(RoutingCandidate candidate) {
        // 不需要额外操作，updateLastUsed 在 RelayService 中已更新数据库
    }

    @Override
    public void markFailed(RoutingCandidate candidate) {
        // 失败由 RelayService 处理（从列表中移除），无需额外操作
    }
}
