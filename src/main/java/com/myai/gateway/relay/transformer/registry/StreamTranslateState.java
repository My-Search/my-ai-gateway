package com.myai.gateway.relay.transformer.registry;

/**
 * 流式翻译跨 chunk 状态标记接口。
 *
 * <p>每个流式请求对应一个状态实例，在 SSE 事件逐 chunk 处理过程中传递。
 * 不同翻译方向有各自的状态实现，用于追踪跨 chunk 的上下文，例如：
 * <ul>
 *   <li>Anthropic → OpenAI：追踪 message_start 是否已发送、当前 tool_use 累积状态</li>
 *   <li>OpenAI → Anthropic：追踪 content_block index、finish_reason 是否已出现</li>
 * </ul>
 *
 * CPA 参考：对应 Go 中 {@code param *any} 的模式，在 Java 中以类型安全的方式实现。
 */
public interface StreamTranslateState {
    // 标记接口，具体状态由各翻译器实现
}
