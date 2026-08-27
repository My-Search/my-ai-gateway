package com.myai.gateway.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 渠道自定义请求头解析与合并工具类。
 * <p>从 {@link Channel} 移出的业务逻辑，保持 Entity 为纯 POJO。</p>
 */
public final class ChannelHeaders {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChannelHeaders() {}

    /**
     * 将渠道的自定义请求头 JSON 合并到给定的 headers map 中。
     * 自定义请求头可以覆盖默认请求头（如覆盖 Authorization）。
     *
     * @param rawJson 渠道的 customHeaders JSON 字符串，可能为 null 或空白
     * @param headers 目标 headers map，会被原地修改
     */
    public static void mergeInto(String rawJson, Map<String, String> headers) {
        Map<String, String> extra = parse(rawJson);
        if (extra != null) {
            headers.putAll(extra);
        }
    }

    /**
     * 解析自定义请求头 JSON 为 Map，解析失败或输入为空返回 null。
     */
    public static Map<String, String> parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        try {
            return MAPPER.readValue(rawJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
