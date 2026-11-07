package com.dmnrules.ai.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Decides whether a request may use the admin console. Identity is never taken from the
 * {@code X-User} header here (anyone can set that): the caller's bearer token is verified by
 * decision-service, which owns users and the signing secret, and the role it reports — re-read
 * from the user table — must be {@code admin}.
 */
@Component
public class AdminAuthorizer {

    public static final String ADMIN_ROLE = "admin";

    private final RestClient restClient;

    public AdminAuthorizer(@Value("${rules-ai.decision-service-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public record VerifiedUser(String username, String displayName, String role) {
    }

    /** @return the verified admin; throws {@link AdminAccessDeniedException} (401 or 403) otherwise. */
    public VerifiedUser requireAdmin(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw AdminAccessDeniedException.notSignedIn();
        }
        VerifiedUser user;
        try {
            user = restClient.get().uri("/api/auth/me")
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve().body(VerifiedUser.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(401))) {
                throw AdminAccessDeniedException.notSignedIn();
            }
            throw e;
        }
        if (user == null || !ADMIN_ROLE.equals(user.role())) {
            throw AdminAccessDeniedException.notAdmin();
        }
        return user;
    }
}
