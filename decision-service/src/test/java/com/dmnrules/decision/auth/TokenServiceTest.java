package com.dmnrules.decision.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    private TokenService at(Instant now, String secret) {
        return new TokenService(secret, Duration.ofHours(1), mapper, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenVerifiesWithClaims() {
        TokenService service = at(NOW, "secret");
        String token = service.issue("admin", "admin");

        TokenService.Claims claims = service.verify(token).orElseThrow();
        assertThat(claims.username()).isEqualTo("admin");
        assertThat(claims.role()).isEqualTo("admin");
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void expiredTokenIsRejected() {
        String token = at(NOW, "secret").issue("admin", "admin");
        assertThat(at(NOW.plus(Duration.ofHours(1)), "secret").verify(token)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = at(NOW, "secret-a").issue("admin", "admin");
        assertThat(at(NOW, "secret-b").verify(token)).isEmpty();
    }

    @Test
    void malformedTokensAreRejected() {
        TokenService service = at(NOW, "secret");
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("abc")).isEmpty();
        assertThat(service.verify("a.b.c")).isEmpty();
        assertThat(service.verify("!!!.???")).isEmpty();
    }

    @Test
    void blankSecretStillProducesWorkingTokensForThisProcess() {
        TokenService service = at(NOW, "");
        assertThat(service.verify(service.issue("analyst", "rule-author"))).isPresent();
        // ...but a second process (new random secret) does not accept them.
        assertThat(at(NOW, "").verify(service.issue("analyst", "rule-author"))).isEmpty();
    }
}
