package com.myai.gateway.relay;

import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.stream.SseEvent;
import com.myai.gateway.relay.transformer.InternalRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 上游调用者接口（函数式接口）
 * <p>用于解耦 CandidateRouter 与实际的 HTTP 调用，便于单元测试通过 spy 拦截。</p>
 * <p>核心抽象方法为 {@link #invokeNonStream}，{@link #invokeStream} 有默认实现抛出异常，
 * 流式路径需显式覆盖。</p>
 */
@FunctionalInterface
public interface ProviderInvoker {

    /**
     * 调用上游非流式接口
     */
    Mono<String> invokeNonStream(String authHeader, InternalRequest req,
                                  RoutingCandidate candidate, String provider);

    /**
     * 调用上游流式接口（默认实现，非流式路径无需覆盖）
     */
    default Flux<SseEvent> invokeStream(String authHeader, InternalRequest req,
                                         RoutingCandidate candidate, String provider,
                                         boolean internalClient, String traceId) {
        throw new UnsupportedOperationException("Stream invocation not supported by this invoker");
    }
}
