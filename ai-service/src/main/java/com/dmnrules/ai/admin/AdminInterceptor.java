package com.dmnrules.ai.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Guards every {@code /api/ai/admin/**} endpoint in one place, so a new admin endpoint cannot be
 * added without the check. The verified admin is exposed to handlers as a request attribute.
 */
@Configuration
public class AdminInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    public static final String ADMIN_ATTRIBUTE = "com.dmnrules.ai.admin.verifiedAdmin";

    // Resolved per request rather than injected directly: @WebMvcTest slices load WebMvcConfigurers
    // but not plain @Components, and must still start without the authorizer.
    private final ObjectProvider<AdminAuthorizer> authorizer;

    public AdminInterceptor(ObjectProvider<AdminAuthorizer> authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/ai/admin/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ADMIN_ATTRIBUTE, authorizer.getObject().requireAdmin(request.getHeader(HttpHeaders.AUTHORIZATION)));
        return true;
    }
}
