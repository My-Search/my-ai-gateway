package com.myai.gateway.relay.balancer;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;

/**
 * 随机选择策略（打乱 + 故障转移）
 * 每次首先打乱候选列表顺序，然后按顺序尝试第一个；
 * 失败后由 RelayService 移除该候选，再次 select 时返回列表中的下一个。
 */
@Component
public class RandomBalancer implements LoadBalancer {

    private static final SecureRandom random = new SecureRandom();

    @Override
    public RoutingCandidate select(List<RoutingCandidate> candidates, Long modelId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            // 第一次 select：打乱列表顺序（后续失败重试时不再打乱）
            Collections.shuffle(candidates, random);
        }
        return candidates.get(0);
    }
}
