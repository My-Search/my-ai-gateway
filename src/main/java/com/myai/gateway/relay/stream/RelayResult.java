package com.myai.gateway.relay.stream;

import reactor.core.publisher.Flux;

/**
 * 中继结果封装
 * 统一表示流式和非流式的中继响应
 */
public class RelayResult {

    private final boolean stream;
    private final String body;           // 非流式响应
    private final Flux<String> streamEvents; // 流式 SSE 事件

    private RelayResult(boolean stream, String body, Flux<String> streamEvents) {
        this.stream = stream;
        this.body = body;
        this.streamEvents = streamEvents;
    }

    /** 创建非流式结果 */
    public static RelayResult of(String body) {
        return new RelayResult(false, body, null);
    }

    /** 创建流式结果 */
    public static RelayResult ofStream(Flux<String> streamEvents) {
        return new RelayResult(true, null, streamEvents);
    }

    public boolean isStream() { return stream; }
    public String getBody() { return body; }
    public Flux<String> getStreamEvents() { return streamEvents; }
}
