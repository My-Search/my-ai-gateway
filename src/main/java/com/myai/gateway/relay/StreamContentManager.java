package com.myai.gateway.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式内容管理器
 * 负责捕获和累积流式响应过程中的内容，以及构建带有历史上下文的请求
 */
@Component
public class StreamContentManager {

    private static final Logger log = LoggerFactory.getLogger(StreamContentManager.class);

    /** traceId -> 累积的内容（使用 StringBuffer 保证线程安全） */
    private final ConcurrentHashMap<String, StringBuffer> contentAccumulator = new ConcurrentHashMap<>();

    /**
     * 追加内容到累积器
     */
    public void appendContent(String traceId, String content) {
        contentAccumulator.computeIfAbsent(traceId, k -> new StringBuffer())
                .append(content);
    }

    /**
     * 获取并清空累积的内容
     * 返回累积的内容并从 Map 中移除
     */
    public String getAndClearContent(String traceId) {
        StringBuffer sb = contentAccumulator.remove(traceId);
        if (sb == null || sb.length() == 0) {
            return null;
        }
        String content = sb.toString();
        log.debug("获取累积内容 - traceId={}, 内容长度={}", traceId, content.length());
        return content;
    }

    /**
     * 获取累积的内容（不清空）
     */
    public String getContent(String traceId) {
        StringBuffer sb = contentAccumulator.get(traceId);
        if (sb == null || sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    /**
     * 检查是否有累积的内容
     */
    public boolean hasContent(String traceId) {
        StringBuffer sb = contentAccumulator.get(traceId);
        return sb != null && sb.length() > 0;
    }

    /**
     * 清空累积的内容（不返回）
     */
    public void clearContent(String traceId) {
        contentAccumulator.remove(traceId);
        log.debug("清空累积内容 - traceId={}", traceId);
    }
}