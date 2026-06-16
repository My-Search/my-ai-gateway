package com.myai.gateway.relay.balancer;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 故障转移策略（默认）
 * 严格按候选列表顺序选择第一个可用候选；失败后由 RelayService 从列表中移除该候选，
 * 下次选择自然落到列表中的下一个候选，保证按模型关联顺序与 API Key 顺序逐一尝试。
 */
@Component
public class FailoverBalancer implements LoadBalancer {

    @Override
    public RoutingCandidate select(List<RoutingCandidate> candidates, Long modelId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.get(0);
    }
}
