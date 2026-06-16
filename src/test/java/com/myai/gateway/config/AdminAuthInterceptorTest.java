package com.myai.gateway.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * AdminAuthInterceptor 单元测试
 * 锁定关键认证逻辑：白名单、401 JSON、JWT/ Session 认证
 */
class AdminAuthInterceptorTest {

    private AdminAuthInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private HttpSession session;
    private StringWriter responseWriter;
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() throws Exception {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        interceptor = new AdminAuthInterceptor(jwtTokenProvider);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin/login",
            "/admin/setup",
            "/",
            "/static/css/main.css",
            "/admin/api/auth/check",
            "/admin/api/auth/login",
            "/admin/api/auth/setup"
    })
    void whitelistedPathsPassWithoutSession(String uri) throws Exception {
        when(request.getRequestURI()).thenReturn(uri);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verifyNoInteractions(response);
    }

    @Test
    void apiPathWithValidSessionPasses() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/api/channels");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("adminUser")).thenReturn("admin");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void apiPathWithValidJwtPasses() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/api/channels");
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-jwt-token");
        when(jwtTokenProvider.validateToken("valid-jwt-token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("valid-jwt-token")).thenReturn("admin");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verify(request).setAttribute("adminUser", "admin");
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void apiPathWithInvalidJwtWithoutSessionReturns401Json() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/api/channels");
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid-jwt-token");
        when(jwtTokenProvider.validateToken("invalid-jwt-token")).thenReturn(false);
        when(request.getSession(false)).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        verify(response).setContentType("application/json;charset=UTF-8");
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(responseWriter.toString())
                .contains("\"success\":false")
                .contains("\"error\":\"未登录\"")
                .contains("\"authenticated\":false");
    }

    @Test
    void apiPathWithoutSessionReturns401Json() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/api/channels");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getSession(false)).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        verify(response).setContentType("application/json;charset=UTF-8");
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(responseWriter.toString())
                .contains("\"success\":false")
                .contains("\"error\":\"未登录\"")
                .contains("\"authenticated\":false");
    }

    @Test
    void apiPathWithEmptySessionReturns401Json() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/api/dashboard/stats");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("adminUser")).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void pagePathWithValidSessionPasses() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/dashboard");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("adminUser")).thenReturn("admin");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    void pagePathWithoutSessionPasses() throws Exception {
        // SPA fallback handles auth check on the frontend side
        when(request.getRequestURI()).thenReturn("/admin/dashboard");
        when(request.getSession(false)).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
    }
}