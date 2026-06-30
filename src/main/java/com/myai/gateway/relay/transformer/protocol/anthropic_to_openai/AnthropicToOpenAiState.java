package com.myai.gateway.relay.transformer.protocol.anthropic_to_openai;

import com.myai.gateway.relay.transformer.registry.StreamTranslateState;

/**
 * Anthropic → OpenAI 流式翻译的跨 chunk 状态。
 *
 * <p>追踪 Anthropic SSE 流中跨多个事件块的上下文信息，
 * 用于正确映射到 OpenAI Chat Completion chunk 格式。
 *
 * <p>CPA 参考：对应 Go 中 {@code ConvertAnthropicResponseToOpenAIParams} 结构体。
 *
 * <p>状态字段说明：
 * <ul>
 *   <li>{@code responseId} — 从 message_start 中提取，用于后续所有 chunk</li>
 *   <li>{@code model} — 从 message_start 中提取的模型名</li>
 *   <li>{@code messageStartSent} — 是否已发出第一个 chunk（message_start 对应的事件）</li>
 *   <li>{@code pendingToolCallIndex} — 当前正在累积的 tool_call index</li>
 *   <li>{@code pendingToolCallId} — 当前正在累积的 tool_call id（来自 content_block_start）</li>
 *   <li>{@code pendingToolCallName} — 当前正在累积的 tool_call name</li>
 *   <li>{@code pendingArguments} — 当前正在累积的 tool_call arguments（跨 input_json_delta 累积）</li>
 *   <li>{@code finishReason} — 从 message_delta 中提取的停⽌原因</li>
 *   <li>{@code messageDeltaSent} — 是否已发出 message_delta 对应的最终 chunk</li>
 * </ul>
 */
public class AnthropicToOpenAiState implements StreamTranslateState {

    /** 响应 ID */
    String responseId;

    /** 模型名 */
    String model;

    /** message_start 是否已处理 */
    boolean messageStartSent;

    /** 正在累积的 tool_call index */
    int pendingToolCallIndex;

    /** 正在累积的 tool_call id */
    String pendingToolCallId;

    /** 正在累积的 tool_call name */
    String pendingToolCallName;

    /** 正在累积的 tool_call arguments JSON */
    final StringBuilder pendingArguments = new StringBuilder();

    /** 从 message_delta 提取的停⽌原因 */
    String finishReason;

    /** message_delta 是否已处理 */
    boolean messageDeltaSent;

    /** 累积的 prompt_tokens */
    int promptTokens;

    /** 累积的 completion_tokens */
    int completionTokens;
}
