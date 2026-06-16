package com.myai.gateway.relay.balancer;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负载均衡器工厂
 * 根据策略名称获取对应的负载均衡器
 */
@Component
public class LoadBalancerFactory {

    private final Map<String, LoadBalancer> balancerMap;

    public LoadBalancerFactory(RandomBalancer randomBalancer,
                               RoundRobinBalancer roundRobinBalancer,
                               FailoverBalancer failoverBalancer) {
        balancerMap = new ConcurrentHashMap<>();
        balancerMap.put("random", randomBalancer);
        balancerMap.put("round_robin", roundRobinBalancer);
        balancerMap.put("failover", failoverBalancer);
    }

    /**
     * 根据策略名称获取负载均衡器
     *
     * @param strategy 策略名称：random / round_robin / failover
     * @return 对应的负载均衡器，默认为 failover
     */
    public LoadBalancer getBalancer(String strategy) {
        return balancerMap.getOrDefault(strategy, balancerMap.get("failover"));
    }
}
