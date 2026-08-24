package com.myai.gateway.relay;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端原始请求信息载体
 * <p>在 Controller 入口处捕获客户端传入的真实请求头与基础信息，
 * 用于请求日志中保存完整的原始请求数据。</p>
 *
 * @param headers     真实请求头快照（不可变，header 名为小写）
 * @param method      HTTP 方法
 * @param path        请求路径（含 query string）
 * @param clientIp    客户端 IP
 */
public record ClientRequestInfo(Map<String, String> headers, String method, String path, String clientIp) {

    public ClientRequestInfo {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** 鉴权头原值：优先 Authorization，其次 x-api-key */
    public String authHeader() {
        String auth = headers.get("authorization");
        if (auth != null && !auth.isBlank()) return auth;
        return headers.get("x-api-key");
    }

    /**
     * 从 Servlet 请求捕获完整快照：全部真实请求头 + 方法 + 路径 + 客户端 IP
     */
    public static ClientRequestInfo from(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                List<String> values = new ArrayList<>();
                Enumeration<String> it = request.getHeaders(name);
                while (it.hasMoreElements()) {
                    values.add(it.nextElement());
                }
                headers.put(name.toLowerCase(), String.join(", ", values));
            }
        }
        String query = request.getQueryString();
        String path = request.getRequestURI() + (query == null || query.isBlank() ? "" : "?" + query);
        return new ClientRequestInfo(headers, request.getMethod(), path, resolveClientIp(request));
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String[] candidateHeaders = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : candidateHeaders) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return comma > 0 ? value.substring(0, comma).trim() : value.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
