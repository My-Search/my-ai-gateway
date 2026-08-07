package com.myai.gateway.relay.transformer.protocol.openai_to_anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.relay.transformer.registry.StreamTranslateState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiToAnthropicTranslator 流式翻译回归测试
 *
 * 重点覆盖：上游（如 DeepSeek/商汤）在普通内容块中携带空字符串 finish_reason:"" 的场景，
 * 空字符串必须视为"非结束"，否则所有内容块都会被误丢弃（历史上导致 Anthropic 协议流式响应无内容）。
 */
class OpenAiToAnthropicTranslatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiToAnthropicTranslator translator = new OpenAiToAnthropicTranslator(objectMapper);

    /** 构造一个 OpenAI 流式 chunk */
    private String chunk(String deltaJson, String finishReason) {
        return "{\"id\":\"chunk-1\",\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,"
                + "\"delta\":" + deltaJson
                + ",\"finish_reason\":\"" + finishReason + "\"}]}";
    }

    @Test
    void 空字符串finishReason不应丢弃文本内容() {
        StreamTranslateState state = translator.createStreamState();
        String out = translator.translateStreamEvent(null,
                chunk("{\"content\":\"Hello\"}", ""), "lite", state);

        assertThat(out).isNotNull()
                .contains("\"type\":\"message_start\"")
                .contains("\"type\":\"content_block_start\"")
                .contains("\"type\":\"content_block_delta\"")
                .contains("\"type\":\"text_delta\"")
                .contains("Hello");
    }

    @Test
    void 空字符串finishReason不应丢弃思考内容() {
        StreamTranslateState state = translator.createStreamState();
        String out = translator.translateStreamEvent(null,
                chunk("{\"reasoning_content\":\"thinking...\"}", ""), "lite", state);

        assertThat(out).isNotNull()
                .contains("\"type\":\"content_block_start\"")
                .contains("\"type\":\"thinking\"")
                .contains("\"type\":\"thinking_delta\"")
                .contains("thinking...");
    }

    @Test
    void 非空finishReason且带usage时输出终结事件() {
        StreamTranslateState state = translator.createStreamState();
        // 先消费一个内容块（含空 finish_reason）
        translator.translateStreamEvent(null, chunk("{\"content\":\"Hello\"}", ""), "lite", state);

        String finishChunk = "{\"id\":\"chunk-2\",\"model\":\"deepseek-v4-flash\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";
        String out = translator.translateStreamEvent(null, finishChunk, "lite", state);

        assertThat(out).isNotNull()
                .contains("\"type\":\"content_block_stop\"")
                .contains("\"type\":\"message_delta\"")
                .contains("\"stop_reason\":\"end_turn\"")
                .contains("\"type\":\"message_stop\"");
    }

    @Test
    void 缺失finishReason字段的普通内容块正常输出() {
        StreamTranslateState state = translator.createStreamState();
        String out = translator.translateStreamEvent(null,
                "{\"id\":\"chunk-3\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}",
                "lite", state);

        assertThat(out).isNotNull()
                .contains("content_block_delta")
                .contains("Hi");
    }
}
