package com.myai.gateway.relay.balancer;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;

/**
 * 随机选择策略
 */
@Component
public class RandomBalancer implements LoadBalancer {

    private static final SecureRandom random = new SecureRandom();

    @Override
    public RoutingCandidate select(List<RoutingCandidate> candidates, Long modelId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int index = random.nextInt(candidates.size());
        return candidates.get(index);
    }
}
