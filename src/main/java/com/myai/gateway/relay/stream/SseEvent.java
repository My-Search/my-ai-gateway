package com.myai.gateway.relay.stream;

/**
 * SSE 事件记录，包含事件名称和数据内容
 *
 * @param event 事件名称（可为 null，此时为默认事件）
 * @param data  事件数据内容
 */
public record SseEvent(String event, String data) {
}
