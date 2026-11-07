package com.dmnrules.decision.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Integration tests call the API as a seeded demo user. Since every {@code /api} call now needs
 * credentials, this turns the {@code X-User} a test sends into HTTP Basic auth for that user (demo
 * password), defaulting to {@code analyst}. Requests that already carry an Authorization header, and
 * the {@code /api/auth/*} endpoints (whose tests check unauthenticated behaviour), are left alone.
 */
@TestConfiguration
public class DemoUserBasicAuth {

    @Bean
    RestTemplateCustomizer demoUserBasicAuthCustomizer() {
        return restTemplate -> restTemplate.getInterceptors().add((request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            if (!headers.containsKey(HttpHeaders.AUTHORIZATION) && !request.getURI().getPath().startsWith("/api/auth/")) {
                String user = headers.getFirst("X-User");
                headers.setBasicAuth(user == null || user.isBlank() ? "analyst" : user, "demo");
            }
            return execution.execute(request, body);
        });
    }
}
