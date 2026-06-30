package com.myai.gateway.relay.transformer.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.myai.gateway.relay.transformer.InternalRequest;

/**
 * 协议翻译器接口。
 *
 * <p>每个实现表示一个方向性的协议转换（如 Anthropic → OpenAI）。
 * 注册到 {@link TranslatorRegistry} 后，调用方通过 source + target 格式查找。
 *
 * <p>包含请求翻译、流式响应翻译、非流式响应翻译三部分。
 *
 * <p>CPA 参考：对应 Go 中 {@code translator.Register(from, to, request, response)} 的注册模式。
 */
public interface ProtocolTranslator {

    /** 源协议格式标识，如 "openai"、"anthropic" */
    String sourceFormat();

    /** 目标协议格式标识，如 "openai"、"anthropic" */
    String targetFormat();

    // ==================== 请求翻译 ====================

    /**
     * 将客户端请求体翻译为目标提供商格式。
     *
     * @param request     内部统一请求
     * @param targetModel 目标渠道模型名
     * @return 目标格式的请求 JSON 字符串
     */
    String translateRequest(InternalRequest request, String targetModel);

    // ==================== 非流式响应翻译 ====================

    /**
     * 将上游提供商响应翻译为客户端格式。
     *
     * @param providerResponse 上游响应的 JSON 节点
     * @param originalModel    客户端请求的原始模型名
     * @return 客户端格式的响应 JSON 字符串
     */
    String translateResponse(JsonNode providerResponse, String originalModel);

    // ==================== 流式响应翻译 ====================

    /**
     * 翻译一个 SSE 事件。
     *
     * <p>每个 chunk 调用一次，通过 {@code state} 维护跨 chunk 上下文。
     * 返回 {@code null} 表示跳过该事件（不输出到客户端）。
     *
     * @param eventType     SSE 事件类型（Anthropic 有 event: xxx，OpenAI 为 null）
     * @param eventData     SSE data 部分的 JSON 字符串
     * @param originalModel 客户端请求的原始模型名
     * @param state         跨 chunk 状态（调用方传入，翻译器内部修改）
     * @return 翻译后的 SSE data 字符串，或 null 表示跳过
     */
    String translateStreamEvent(String eventType, String eventData,
                                String originalModel, StreamTranslateState state);

    /**
     * 流结束时调用（收到 {@code [DONE]} 信号）。
     *
     * <p>用于刷出终结事件，如 Anthropic 的 message_delta（含 usage/stop_reason）。
     *
     * @param originalModel 客户端请求的原始模型名
     * @param state         当前累积的跨 chunk 状态
     * @return 终结 SSE data 字符串，或 null 无需额外事件
     */
    String translateStreamEnd(String originalModel, StreamTranslateState state);

    /**
     * 创建新的流式翻译状态实例。
     * 每个流式请求开始时调用一次。
     */
    StreamTranslateState createStreamState();
}
