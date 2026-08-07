package com.myai.gateway.relay.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.relay.transformer.registry.TranslatorRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MessageTransformer 请求构建回归测试
 *
 * 重点覆盖：Anthropic 客户端的 thinking 块转发到 OpenAI 格式渠道时的处理。
 * 历史上 thinking 块被原样透传进 OpenAI content 数组，被上游（如商汤）以 400
 * "Invalid value: thinking" 拒绝，导致 Anthropic 协议的多轮对话测试失败。
 */
class MessageTransformerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MessageTransformer transformer =
            new MessageTransformer(objectMapper, new TranslatorRegistry(new ArrayList<>()));

    /** 构造一个带 thinking + text contentParts 的 assistant 消息 */
    private InternalMessage assistantWithThinking(String thinking, String text) {
        InternalMessage msg = new InternalMessage();
        msg.setRole("assistant");
        List<Map<String, Object>> parts = new ArrayList<>();
        if (thinking != null) {
            Map<String, Object> thinkingPart = new LinkedHashMap<>();
            thinkingPart.put("type", "thinking");
            thinkingPart.put("thinking", thinking);
            parts.add(thinkingPart);
        }
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", text);
        parts.add(textPart);
        msg.setContentParts(parts);
        return msg;
    }

    private InternalRequest buildReq(String model) {
        InternalRequest req = new InternalRequest();
        req.setModel(model);
        req.setStream(true);
        req.setClientApiFormat("anthropic");
        req.setMessages(List.of(
                new InternalMessage("user", "hi"),
                assistantWithThinking("let me think step by step", "Hello!"),
                new InternalMessage("user", "what is 2+2?")
        ));
        return req;
    }

    @Test
    void thinking块在DeepSeek模型上转换为reasoning_content() throws Exception {
        String body = transformer.buildOpenAiRequest(buildReq("deepseek-v4-flash"));
        JsonNode root = objectMapper.readTree(body);

        JsonNode assistantMsg = root.get("messages").get(1);
        // content 数组不再包含 thinking 块
        assertThat(assistantMsg.get("content").toString())
                .doesNotContain("thinking")
                .contains("\"text\":\"Hello!\"");
        // 提取为 reasoning_content 字段
        assertThat(assistantMsg.get("reasoning_content").asText())
                .isEqualTo("let me think step by step");
    }

    @Test
    void thinking块在非DeepSeek模型上直接丢弃() throws Exception {
        String body = transformer.buildOpenAiRequest(buildReq("minimax-m3"));
        JsonNode root = objectMapper.readTree(body);

        JsonNode assistantMsg = root.get("messages").get(1);
        assertThat(assistantMsg.get("content").toString())
                .doesNotContain("thinking")
                .contains("\"text\":\"Hello!\"");
        assertThat(assistantMsg.has("reasoning_content")).isFalse();
    }

    @Test
    void openai客户端消息不受影响() throws Exception {
        InternalRequest req = new InternalRequest();
        req.setModel("deepseek-v4-flash");
        req.setStream(true);
        req.setClientApiFormat("openai");
        InternalMessage assistant = new InternalMessage("assistant", "Hello!");
        assistant.setReasoningContent("thinking from openai client");
        req.setMessages(List.of(new InternalMessage("user", "hi"), assistant));

        String body = transformer.buildOpenAiRequest(req);
        JsonNode root = objectMapper.readTree(body);
        JsonNode assistantMsg = root.get("messages").get(1);
        assertThat(assistantMsg.get("reasoning_content").asText())
                .isEqualTo("thinking from openai client");
        assertThat(assistantMsg.get("content").asText()).isEqualTo("Hello!");
    }
}
