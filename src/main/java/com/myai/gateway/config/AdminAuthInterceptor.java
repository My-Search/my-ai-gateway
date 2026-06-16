package com.myai.gateway.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理后台认证拦截器
 * 拦截所有 /admin/api/** 请求，未登录时返回 401 JSON
 * Vue SPA 前端页面由 WebMvcConfig 的 SPA fallback 处理
 * <p>
 * 认证方式（按优先级）：
 * 1. Authorization: Bearer <JWT Token> 请求头
 * 2. HttpSession（向后兼容）
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthInterceptor.class);

    private final JwtTokenProvider jwtTokenProvider;

    public AdminAuthInterceptor(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 静态资源和前端页面不拦截
        if (uri.startsWith("/static/") || uri.startsWith("/assets/")
                || uri.equals("/") || uri.equals("/index.html")) {
            return true;
        }

        // API 认证接口不拦截（登录、检查、设置）
        if (uri.startsWith("/admin/api/auth/")) {
            return true;
        }

        // 其他 API 请求需要认证
        if (uri.startsWith("/admin/api/")) {
            // 方式1：JWT Token 认证（优先级高）
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (jwtTokenProvider.validateToken(token)) {
                    // 将用户名设置到 request attribute 中，方便后续使用
                    String username = jwtTokenProvider.getUsernameFromToken(token);
                    request.setAttribute("adminUser", username);
                    return true;
                }
                log.debug("JWT Token 无效: uri={}", uri);
            }

            // 方式2：Session 认证（向后兼容）
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute("adminUser") != null) {
                return true;
            }

            log.debug("API 请求未登录: uri={}, session={}, adminUser={}",
                    uri, session, session != null ? session.getAttribute("adminUser") : null);
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"success\":false,\"error\":\"未登录\",\"authenticated\":false}");
            return false;
        }

        // Vue SPA 前端页面（/admin/** 但不是 API）- 由 SPA fallback 处理，不需要拦截
        // 前端通过 /admin/api/auth/check 检查登录状态
        return true;
    }
}
