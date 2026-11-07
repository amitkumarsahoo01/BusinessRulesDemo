package com.dmnrules.decision.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks a username/password against {@code app_user}. BCrypt is deliberately slow (~50-100 ms), and
 * Basic auth sends the password on every request, so a successful check is remembered for a short
 * time, keyed by a SHA-256 of the credentials (the password itself is never kept). A changed password
 * or role takes effect within {@link #CACHE_TTL}.
 */
@Component
public class CredentialVerifier {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int MAX_CACHE_ENTRIES = 1_000;

    private final AppUserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(AppUserRow user, Instant expiresAt) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CredentialVerifier(AppUserRepository userRepository) {
        this(userRepository, Clock.systemUTC());
    }

    CredentialVerifier(AppUserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    public Optional<AppUserRow> verify(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        String key = cacheKey(username, password);
        Instant now = clock.instant();
        Cached hit = cache.get(key);
        if (hit != null && now.isBefore(hit.expiresAt())) {
            return Optional.of(hit.user());
        }
        Optional<AppUserRow> user = userRepository.findByUsername(username)
                .filter(u -> encoder.matches(password, u.passwordHash()));
        user.ifPresent(u -> {
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(key, new Cached(u, now.plus(CACHE_TTL)));
        });
        return user;
    }

    private static String cacheKey(String username, String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(username.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
