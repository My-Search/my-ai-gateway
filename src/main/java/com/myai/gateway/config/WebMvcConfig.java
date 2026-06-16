package com.myai.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web MVC 配置
 * 支持 Vue SPA 前端和 API 接口
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    public WebMvcConfig(AdminAuthInterceptor adminAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 静态资源
        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/");
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");

        // Vue SPA 前端资源
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/");

        // SPA fallback: 所有非 API 请求返回 index.html
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);
                        // 如果资源存在或者是 API 请求，正常返回
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }
                        // 如果是 API 请求，返回 null 让 Spring 处理
                        if (resourcePath.startsWith("admin/api/")) {
                            return null;
                        }
                        // 否则返回 index.html (SPA fallback)
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 管理后台认证拦截器
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**")
                .addPathPatterns("/")
                .excludePathPatterns("/admin/api/auth/**", "/static/**", "/assets/**", "/index.html");
    }

    /**
     * 扩展消息转换器
     * 将已有的 StringHttpMessageConverter 默认编码改为 UTF-8，
     * 解决 SseEmitter 发送中文时使用 ISO-8859-1 导致乱码的问题。
     * 使用 extendMessageConverters 而非 configureMessageConverters，
     * 后者会替换所有默认转换器（Jackson 等），导致 JSON 解析等功能丢失。
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof StringHttpMessageConverter) {
                converters.set(i, new StringHttpMessageConverter(StandardCharsets.UTF_8));
                break;
            }
        }
    }
}
