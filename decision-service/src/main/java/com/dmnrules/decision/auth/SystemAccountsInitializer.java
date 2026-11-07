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
 * Ensures the non-demo accounts exist with their configured passwords on every start. None of them
 * use the shared {@code demo} password; each password comes from an env var:
 * <ul>
 *   <li>{@code admin} (role {@code admin}) — admin console. {@code DEMO_ADMIN_PASSWORD}; random and
 *       logged once when unset.</li>
 *   <li>{@code api-client} (role {@code api-client}) — integrations calling the REST API with Basic
 *       auth: evaluate/simulate and read-only calls. {@code API_CLIENT_PASSWORD}; random and logged
 *       once when unset.</li>
 *   <li>{@code ai-service} (role {@code service}) — ai-service's own credential for calling this
 *       API, allowed to act on behalf of the signed-in user. {@code AI_SERVICE_PASSWORD}, which must
 *       match ai-service's; when unset the account gets an unusable random password (not logged).</li>
 * </ul>
 * Runs after {@code SeedRunner} (order 1), which truncates {@code app_user} on a demo reset.
 */
@Component
@Order(2)
public class SystemAccountsInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemAccountsInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final String adminPassword;
    private final String apiClientPassword;
    private final String servicePassword;

    public SystemAccountsInitializer(
            JdbcTemplate jdbcTemplate,
            @Value("${demo.auth.admin-password:}") String adminPassword,
            @Value("${demo.auth.api-client-password:}") String apiClientPassword,
            @Value("${demo.auth.service-password:}") String servicePassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminPassword = adminPassword;
        this.apiClientPassword = apiClientPassword;
        this.servicePassword = servicePassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        upsert(Roles.ADMIN_USERNAME, "Platform Admin", Roles.ADMIN, orGenerated(adminPassword, "DEMO_ADMIN_PASSWORD", Roles.ADMIN_USERNAME, true));
        upsert(Roles.API_CLIENT_USERNAME, "API client", Roles.API_CLIENT,
                orGenerated(apiClientPassword, "API_CLIENT_PASSWORD", Roles.API_CLIENT_USERNAME, true));
        if (isBlank(servicePassword)) {
            log.warn("AI_SERVICE_PASSWORD is not set: ai-service will not be able to call this API "
                    + "(chat tools and document seeding will fail with 401). Set the same value on both services.");
        }
        upsert(Roles.SERVICE_USERNAME, "ai-service (internal)", Roles.SERVICE,
                orGenerated(servicePassword, "AI_SERVICE_PASSWORD", Roles.SERVICE_USERNAME, false));
    }

    private String orGenerated(String configured, String envName, String username, boolean logIt) {
        if (!isBlank(configured)) {
            return configured;
        }
        byte[] random = new byte[18];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        if (logIt) {
            log.warn("{} is not set. Generated password for '{}' for this run: {}", envName, username, generated);
        }
        return generated;
    }

    private void upsert(String username, String displayName, String role, String password) {
        jdbcTemplate.update(
                """
                INSERT INTO app_user (username, password_hash, display_name, role)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (username) DO UPDATE
                    SET password_hash = EXCLUDED.password_hash, role = EXCLUDED.role,
                        display_name = EXCLUDED.display_name
                """,
                username, new BCryptPasswordEncoder().encode(password), displayName, role);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
