package com.myai.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Actuator 端点安全过滤器
 *
 * <p>保护 {@code /actuator/prometheus} 端点，要求请求携带配置的访问令牌。
 * 令牌通过环境变量 {@code ACTUATOR_ACCESS_TOKEN} 或配置 {@code actuator.access-token} 设置；
 * 未配置时放行所有请求（向后兼容，不改变现有行为）。</p>
 *
 * <p>Prometheus 抓取配置示例：</p>
 * <pre>
 * scrape_configs:
 *   - job_name: 'mag'
 *     bearer_token: '&lt;ACTUATOR_ACCESS_TOKEN 的值&gt;'
 *     metrics_path: '/actuator/prometheus'
 * </pre>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActuatorSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ActuatorSecurityFilter.class);
    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    @Value("${actuator.access-token:}")
    private String configuredToken;

    /**
     * 启动时提醒：未配置令牌时 /actuator/prometheus 完全公开，
     * 生产环境暴露运行时指标存在信息泄露风险，应尽早配置。
     */
    @jakarta.annotation.PostConstruct
    void warnIfTokenMissing() {
        if (configuredToken == null || configuredToken.isBlank()) {
            log.warn("未配置 actuator.access-token（或 ACTUATOR_ACCESS_TOKEN），/actuator/prometheus 端点将公开访问；生产环境建议配置访问令牌");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // 仅拦截 /actuator/prometheus，其他路径直接放行
        if (!PROMETHEUS_PATH.equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 未配置 token 时放行（向后兼容）
        if (configuredToken == null || configuredToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 检查 Authorization: Bearer <token> 或 X-Actuator-Token 头
        String authHeader = request.getHeader("Authorization");
        String actuatorToken = request.getHeader("X-Actuator-Token");

        String providedToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            providedToken = authHeader.substring(7).trim();
        } else if (actuatorToken != null) {
            providedToken = actuatorToken.trim();
        }

        if (providedToken != null && tokenMatches(providedToken)) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Prometheus 端点访问被拒绝（令牌不匹配）- remoteAddr={}", request.getRemoteAddr());
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Invalid or missing access token for /actuator/prometheus\"}");
        }
    }

    /**
     * 常量时间令牌比较，避免计时侧信道逐字节猜测令牌。
     */
    private boolean tokenMatches(String providedToken) {
        return MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8));
    }
}
