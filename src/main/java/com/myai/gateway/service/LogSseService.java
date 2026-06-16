package com.myai.gateway.service;

import com.myai.gateway.entity.RequestLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 日志 SSE 广播服务
 * <p>
 * 使用 Reactor Sinks 实现多播：RequestLogService 写入日志后调用 publish()，
 * AdminApiController 的 SSE 端点通过 subscribe() 获取 Flux 并推送给前端。
 * </p>
 */
@Service
public class LogSseService {

    private static final Logger log = LoggerFactory.getLogger(LogSseService.class);

    /**
     * 多播 Sink，背压缓冲上限 256 条。
     * directBestEffort：尽可能推送给所有订阅者，慢订阅者会丢事件（不影响 DB 持久化）。
     */
    private final Sinks.Many<RequestLog> sink = Sinks.many().multicast()
            .onBackpressureBuffer(256, false);

    /**
     * 发布一条新日志到所有 SSE 订阅者
     */
    public void publish(RequestLog record) {
        Sinks.EmitResult result = sink.tryEmitNext(record);
        if (result != Sinks.EmitResult.OK) {
            log.warn("SSE 日志推送失败: {} (traceId={})", result, record.getTraceId());
        }
    }

    /**
     * 获取日志流，供 SSE 端点订阅
     */
    public Flux<RequestLog> subscribe() {
        return sink.asFlux();
    }
}
