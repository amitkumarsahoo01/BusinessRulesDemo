package com.dmnrules.decision.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Issues and verifies short, HMAC-SHA256-signed session tokens ({@code payload.signature}, both
 * base64url). Used for actions that must not be reachable by forging the {@code X-User} header —
 * today that is the admin console (LLM provider settings). The rest of the demo still identifies
 * callers by {@code X-User}; see docs/DECISIONS.md.
 *
 * <p>The signing secret comes from {@code AUTH_TOKEN_SECRET}. When it is not set, a random secret
 * is generated per process: tokens then stop working after a restart (users simply sign in again),
 * and multi-instance deployments must set the env var so every instance agrees.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public TokenService(
            @Value("${demo.auth.token-secret:}") String configuredSecret,
            @Value("${demo.auth.token-ttl:PT12H}") Duration ttl,
            ObjectMapper objectMapper) {
        this(configuredSecret, ttl, objectMapper, Clock.systemUTC());
    }

    TokenService(String configuredSecret, Duration ttl, ObjectMapper objectMapper, Clock clock) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("AUTH_TOKEN_SECRET is not set: using a random per-process signing secret. "
                    + "Sessions end on restart; set AUTH_TOKEN_SECRET when running more than one instance.");
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.ttl = ttl;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public record Claims(String username, String role, Instant expiresAt) {
    }

    public String issue(String username, String role) {
        Instant exp = clock.instant().plus(ttl);
        try {
            byte[] payload = objectMapper.writeValueAsBytes(
                    Map.of("sub", username, "role", role, "exp", exp.getEpochSecond()));
            String encodedPayload = B64.encodeToString(payload);
            return encodedPayload + "." + B64.encodeToString(sign(encodedPayload));
        } catch (Exception e) {
            throw new IllegalStateException("Could not issue session token", e);
        }
    }

    /** Empty for anything malformed, tampered with, or expired. */
    public Optional<Claims> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        String encodedPayload = token.substring(0, dot);
        try {
            byte[] expected = sign(encodedPayload);
            byte[] actual = B64D.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }
            Map<?, ?> payload = objectMapper.readValue(B64D.decode(encodedPayload), Map.class);
            Instant exp = Instant.ofEpochSecond(((Number) payload.get("exp")).longValue());
            if (!clock.instant().isBefore(exp)) {
                return Optional.empty();
            }
            return Optional.of(new Claims((String) payload.get("sub"), (String) payload.get("role"), exp));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
    }
}
