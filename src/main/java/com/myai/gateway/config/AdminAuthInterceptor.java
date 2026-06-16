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
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthInterceptor.class);

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
