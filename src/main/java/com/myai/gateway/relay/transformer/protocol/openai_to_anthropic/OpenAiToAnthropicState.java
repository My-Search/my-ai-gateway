package com.myai.gateway.relay.transformer.protocol.openai_to_anthropic;

import com.myai.gateway.relay.transformer.registry.StreamTranslateState;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI → Anthropic 流式翻译的跨 chunk 状态。
 *
 * <p>参考 new-api-main 的 {@code ConvertOpenAIResponseToAnthropicParams}：
 * 显式追踪当前打开的 content block 类型，类型切换时先 stop 旧 block 再 start 新 block。
 * 支持 message_start 首事件、延迟关闭（finish_reason 与 usage 解耦）、并行 tool_use 索引管理。
 */
public class OpenAiToAnthropicState implements StreamTranslateState {

    /** message_start 是否已发出 */
    boolean messageStartSent;

    /** 当前 content block 索引（线性递增，每个 block 有唯一 index，从 0 开始） */
    int contentBlockIndex = -1;

    /** 当前打开的 block 类型：none / text / thinking / tools */
    String currentBlockType = "none";

    /** 当前这组 tool_use block 的起始 index */
    int toolCallBaseIndex;

    /** 当前这组 tool_use block 的最大偏移量 */
    int toolCallMaxIndexOffset;

    /** 按 OpenAI toolCall.Index 累积的工具调用定义 */
    final Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();

    /** 是否已遇到 finish_reason */
    boolean finishReasonSeen;

    /** 暂存的 finish_reason 值 */
    String pendingFinishReason;

    /** message_delta 事件是否已发出 */
    boolean messageDeltaSent;

    /** 是否已收到 usage（用于延迟关闭） */
    boolean usageReceived;

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
    }
}
