package com.myai.gateway.relay.balancer;

import java.util.List;

/**
 * 负载均衡器接口
 * 定义了从可用路由候选列表中选择一个的策略
 */
public interface LoadBalancer {

    /**
     * 从可用列表中选取一个路由候选
     *
     * @param candidates 可用的路由候选列表
     * @param modelId    自定义模型 ID
     * @return 选中的路由候选，如果没有可用则返回 null
     */
    RoutingCandidate select(List<RoutingCandidate> candidates, Long modelId);

    /**
     * 标记一个路由候选调用失败（用于故障转移策略）
     */
    default void markFailed(RoutingCandidate candidate) {
    }

    /**
     * 标记一个路由候选调用成功
     */
    default void markSuccess(RoutingCandidate candidate) {
    }
}
