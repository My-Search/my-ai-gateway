package com.myai.gateway.relay.transformer.protocol.openai_to_anthropic;

import com.myai.gateway.relay.transformer.registry.StreamTranslateState;

/**
 * OpenAI → Anthropic 流式翻译的跨 chunk 状态。
 *
 * <p>追踪 OpenAI SSE chunk 流中跨多个 chunk 的上下文信息，
 * 用于正确映射到 Anthropic Messages SSE 事件格式。
 *
 * <p>CPA 参考：对应 Go 中 {@code ConvertOpenAIResponseToAnthropicParams} 结构体。
 *
 * <p>状态字段说明：
 * <ul>
 *   <li>{@code contentBlockIndex} — 当前 content block 索引（文本和 tool_use 交替递增）</li>
 *   <li>{@code textContentStarted} — 当前文本 content block 是否已开始（避免重复发 start）</li>
 *   <li>{@code toolCallAccumulators} — 按 index 累积 tool_calls 的状态（id、name、arguments）</li>
 *   <li>{@code finishReasonSeen} — 是否已遇到 finish_reason</li>
 *   <li>{@code messageDeltaSent} — message_delta 事件是否已发出</li>
 * </ul>
 */
public class OpenAiToAnthropicState implements StreamTranslateState {

    /** 当��� content block 索引 */
    int contentBlockIndex;

    /** 当前文本 content block 是否已通过 content_block_start 发出 */
    boolean textContentStarted;

    /** 按 tool_calls[index] 累积的工具调用定义 */
    final java.util.Map<Integer, ToolCallAccumulator> toolCallAccumulators = new java.util.HashMap<>();

    /** 是否已遇到 finish_reason */
    boolean finishReasonSeen;

    /** message_delta 事件是否已发出 */
    boolean messageDeltaSent;

    /** 累积的 prompt_tokens */
    int promptTokens;

    /** 累积的 completion_tokens */
    int completionTokens;

    /**
     * 单个 tool_call 的累积状态。
     */
    static class ToolCallAccumulator {
        int index;
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
        boolean contentBlockStarted;
        boolean contentBlockStopped;
    }
}
