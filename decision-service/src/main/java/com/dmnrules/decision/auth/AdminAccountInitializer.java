package com.dmnrules.decision.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Ensures the {@code admin} account exists with the configured password on every start. Unlike the
 * three demo users it never uses the shared {@code demo} password and is not listed on the login
 * page: its password comes from {@code DEMO_ADMIN_PASSWORD}, or, when that is unset, a random one
 * is generated and logged once (the same pattern Spring Security uses for its default user).
 *
 * <p>Runs after {@code SeedRunner} (order 1), which truncates {@code app_user} on a demo reset.
 */
@Component
@Order(2)
public class AdminAccountInitializer implements ApplicationRunner {

    public static final String ADMIN_USERNAME = "admin";
    public static final String ADMIN_ROLE = "admin";

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final String configuredPassword;

    public AdminAccountInitializer(
            JdbcTemplate jdbcTemplate, @Value("${demo.auth.admin-password:}") String configuredPassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = configuredPassword;
        if (password == null || password.isBlank()) {
            byte[] random = new byte[12];
            new SecureRandom().nextBytes(random);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            log.warn("DEMO_ADMIN_PASSWORD is not set. Generated admin password for this run: {}", password);
        }
        String hash = new BCryptPasswordEncoder().encode(password);
        jdbcTemplate.update(
                """
                INSERT INTO app_user (username, password_hash, display_name, role)
                VALUES (?, ?, 'Platform Admin', ?)
                ON CONFLICT (username) DO UPDATE
                    SET password_hash = EXCLUDED.password_hash, role = EXCLUDED.role
                """,
                ADMIN_USERNAME, hash, ADMIN_ROLE);
    }
}
