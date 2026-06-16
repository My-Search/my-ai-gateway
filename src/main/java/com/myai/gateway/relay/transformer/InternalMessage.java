package com.myai.gateway.relay.transformer;

import java.util.List;
import java.util.Map;

/**
 * 内部统一消息模型
 * 用于在 OpenAI 和 Anthropic 格式之间做桥接转换
 */
public class InternalMessage {

    /** 角色: system / user / assistant / tool */
    private String role;

    /** 文本内容（纯文本或JSON字符串） */
    private String content;

    /** 多媒体内容列表（当 content 为数组时使用，如图片） */
    private List<Map<String, Object>> contentParts;

    /** 工具调用（assistant 角色） */
    private List<Map<String, Object>> toolCalls;

    /** 工具调用ID（tool 角色） */
    private String toolCallId;

    /** 名称（tool 角色） */
    private String name;

    public InternalMessage() {}

    public InternalMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<Map<String, Object>> getContentParts() { return contentParts; }
    public void setContentParts(List<Map<String, Object>> contentParts) { this.contentParts = contentParts; }

    public List<Map<String, Object>> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<Map<String, Object>> toolCalls) { this.toolCalls = toolCalls; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
