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
 * Authenticates every {@code /api/ai/**} call in one place (Bearer token or HTTP Basic, verified by
 * decision-service), and additionally requires the admin role under {@code /api/ai/admin/**}. The
 * verified user is exposed to handlers as request attributes, so no endpoint can be added without
 * the check and none reads a self-declared {@code X-User}.
 */
@Configuration
public class AdminInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    public static final String USER_ATTRIBUTE = "com.dmnrules.ai.auth.verifiedUser";
    public static final String ADMIN_ATTRIBUTE = "com.dmnrules.ai.admin.verifiedAdmin";

    // Resolved per request rather than injected directly: @WebMvcTest slices load WebMvcConfigurers
    // but not plain @Components, and must still start without the authorizer.
    private final ObjectProvider<AdminAuthorizer> authorizer;

    public AdminInterceptor(ObjectProvider<AdminAuthorizer> authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/ai/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        AdminAuthorizer.VerifiedUser user = path.startsWith("/api/ai/admin/")
                ? authorizer.getObject().requireAdmin(authorization)
                : authorizer.getObject().requireUser(authorization);
        request.setAttribute(USER_ATTRIBUTE, user);
        request.setAttribute(ADMIN_ATTRIBUTE, user);
        return true;
    }
}
