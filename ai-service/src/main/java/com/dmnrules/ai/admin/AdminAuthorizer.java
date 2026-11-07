package com.dmnrules.ai.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identifies the caller of an ai-service endpoint. Identity is never taken from the {@code X-User}
 * header (anyone can set that): the caller's {@code Authorization} header (Bearer token from login,
 * or HTTP Basic) is checked by decision-service's {@code GET /api/auth/me}, which owns users, passwords
 * and the token secret. Successful checks are cached briefly so a chat message doesn't add a round trip.
 */
@Component
public class AdminAuthorizer {

    public static final String ADMIN_ROLE = "admin";
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final int MAX_CACHE_ENTRIES = 1_000;

    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(VerifiedUser user, Instant expiresAt) {
    }

    public AdminAuthorizer(@Value("${rules-ai.decision-service-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public record VerifiedUser(String username, String displayName, String role) {
    }

    /** @return the verified caller; throws {@link AdminAccessDeniedException} (401) otherwise. */
    public VerifiedUser requireUser(String authorizationHeader) {
        if (authorizationHeader == null
                || !(authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)
                        || authorizationHeader.regionMatches(true, 0, "Basic ", 0, 6))) {
            throw AdminAccessDeniedException.notSignedIn();
        }
        String key = sha256(authorizationHeader);
        Cached hit = cache.get(key);
        if (hit != null && Instant.now().isBefore(hit.expiresAt())) {
            return hit.user();
        }
        VerifiedUser user;
        try {
            user = restClient.get().uri("/api/auth/me")
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .retrieve().body(VerifiedUser.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(401))) {
                throw AdminAccessDeniedException.notSignedIn();
            }
            throw e;
        }
        if (user == null) {
            throw AdminAccessDeniedException.notSignedIn();
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new Cached(user, Instant.now().plus(CACHE_TTL)));
        return user;
    }

    /** @return the verified admin; throws {@link AdminAccessDeniedException} (401 or 403) otherwise. */
    public VerifiedUser requireAdmin(String authorizationHeader) {
        VerifiedUser user = requireUser(authorizationHeader);
        if (!ADMIN_ROLE.equals(user.role())) {
            throw AdminAccessDeniedException.notAdmin();
        }
        return user;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
