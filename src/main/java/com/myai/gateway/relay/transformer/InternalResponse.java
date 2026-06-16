package com.myai.gateway.relay.transformer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 内部统一响应模型
 * 屏蔽 OpenAI 和 Anthropic 的响应体差异
 */
public class InternalResponse {

    /** 响应 ID */
    private String id;

    /** 模型名 */
    private String model;

    /** 响应内容列表（每个元素是一个 content block） */
    private List<InternalResponseContent> contents;

    /** 使用量 */
    private InternalUsage usage;

    /** 停止原因 */
    private String stopReason;

    /** 原始响应 JSON */
    private JsonNode originalResponseJson;

    /** 是否为流式聚合结果 */
    private boolean aggregatedFromStream;

    /** 额外参数 */
    private Map<String, Object> extraParams;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<InternalResponseContent> getContents() { return contents; }
    public void setContents(List<InternalResponseContent> contents) { this.contents = contents; }

    public InternalUsage getUsage() { return usage; }
    public void setUsage(InternalUsage usage) { this.usage = usage; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }

    public JsonNode getOriginalResponseJson() { return originalResponseJson; }
    public void setOriginalResponseJson(JsonNode originalResponseJson) { this.originalResponseJson = originalResponseJson; }

    public boolean isAggregatedFromStream() { return aggregatedFromStream; }
    public void setAggregatedFromStream(boolean aggregatedFromStream) { this.aggregatedFromStream = aggregatedFromStream; }

    public Map<String, Object> getExtraParams() { return extraParams; }
    public void setExtraParams(Map<String, Object> extraParams) { this.extraParams = extraParams; }

    /**
     * 获取拼接后的文本内容
     */
    public String getTextContent() {
        if (contents == null) return "";
        StringBuilder sb = new StringBuilder();
        for (InternalResponseContent c : contents) {
            if ("text".equals(c.getType()) && c.getText() != null) {
                sb.append(c.getText());
            }
        }
        return sb.toString();
    }

    /**
     * Content block 内部模型
     */
    public static class InternalResponseContent {
        private String type;       // text, tool_use, tool_result
        private String text;
        private String id;         // tool_use id
        private String name;       // tool name
        private Object input;      // tool input

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Object getInput() { return input; }
        public void setInput(Object input) { this.input = input; }
    }

    /**
     * 使用量内部模型
     */
    public static class InternalUsage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;

        public int getPromptTokens() { return promptTokens; }
        public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

        public int getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

        public int getTotalTokens() { return totalTokens; }
        public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    }
}
