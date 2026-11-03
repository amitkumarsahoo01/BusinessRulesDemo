package com.dmnrules.decision.seed;

import com.dmnrules.decision.audit.RowHashService;
import com.dmnrules.decision.evaluate.EvaluationService;
import com.dmnrules.decision.model.ModelRegistry;
import com.dmnrules.decision.model.ModelVersionRepository;
import com.dmnrules.decision.model.ModelVersionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Seeds demo users, the loan-eligibility and insurance-premium models, and ~300 historical
 * executions so the demo works the moment it starts (docs/BUILD_PLAN.md "Seed content"). The
 * pgvector policy-document embeddings called for in the full seed spec are deferred to M5, which
 * is where ai-service's embedding model lives (see docs/DECISIONS.md).
 *
 * <p>{@code demo.seed.enabled} (from {@code RESET_DEMO_ON_START}, default true) controls two
 * different things depending on its value: when true, every restart truncates and reseeds from
 * scratch (BUILD_PLAN M6: "RESET_DEMO_ON_START=true reseeds data" — a public demo should always
 * come back clean after a restart, however a visitor left it). When false, seeding only runs once
 * on a genuinely empty database (first-run bootstrap) and never touches data again, so an operator
 * who wants to preserve manual changes across restarts can turn reset off without losing the
 * initial seed either. Truncating audit tables here is a deliberate, demo-only exception to
 * CLAUDE.md rule 2 (INSERT/SELECT-only on audit tables) — see docs/DECISIONS.md.
 */
@Component
@Order(1)
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);
    private static final String[] CALLERS = {"analyst", "approver", "auditor"};

    private final JdbcTemplate jdbcTemplate;
    private final ModelVersionRepository modelVersionRepository;
    private final ModelRegistry modelRegistry;
    private final EvaluationService evaluationService;
    private final RowHashService rowHashService;
    private final boolean enabled;

    public SeedRunner(
            JdbcTemplate jdbcTemplate,
            ModelVersionRepository modelVersionRepository,
            ModelRegistry modelRegistry,
            EvaluationService evaluationService,
            RowHashService rowHashService,
            @Value("${demo.seed.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.modelVersionRepository = modelVersionRepository;
        this.modelRegistry = modelRegistry;
        this.evaluationService = evaluationService;
        this.rowHashService = rowHashService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean hasData = modelVersionRepository.countModels() > 0;
        if (enabled && hasData) {
            log.info("RESET_DEMO_ON_START=true: clearing existing demo data before reseeding");
            resetDemoData();
        } else if (!enabled && hasData) {
            log.info("Seed data already present and RESET_DEMO_ON_START=false, leaving it alone");
            return;
        } else if (!enabled) {
            log.info("Database is empty; seeding once even though RESET_DEMO_ON_START=false (first-run bootstrap)");
        }

        log.info("Seeding demo users, models and executions...");
        seedUsers();
        seedLoanEligibility();
        seedInsurancePremium();
        modelRegistry.reload();
        generateExecutions();
        log.info("Demo seeding complete");
    }

    private void resetDemoData() {
        jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    decision_trace_step, decision_execution,
                    model_test_case, rule_change_audit,
                    dmn_model_version, dmn_model,
                    ai_interaction_audit, app_user
                RESTART IDENTITY CASCADE
                """);
    }

    private void seedUsers() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("demo");
        insertUser("analyst", hash, "Alex Analyst", "rule-author");
        insertUser("approver", hash, "Priya Approver", "rule-approver");
        insertUser("auditor", hash, "Jordan Auditor", "auditor");
    }

    private void insertUser(String username, String passwordHash, String displayName, String role) {
        jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash, display_name, role) VALUES (?, ?, ?, ?)",
                username, passwordHash, displayName, role);
    }

    private void seedLoanEligibility() {
        modelVersionRepository.insertModel(
                "loan-eligibility", "Loan Eligibility", "https://dmn-rules-demo/loan-eligibility", "analyst");
        insertVersion("loan-eligibility", "1.0.0", "seed/dmn/loan-eligibility-1.0.0.dmn", "PUBLISHED",
                "analyst", "approver", Instant.now().minus(30, ChronoUnit.DAYS),
                "Initial published version.");
        insertVersion("loan-eligibility", "1.1.0", "seed/dmn/loan-eligibility-1.1.0.dmn", "PUBLISHED",
                "analyst", "approver", Instant.now().minus(10, ChronoUnit.DAYS),
                "Tightened credit score thresholds (700->720, 620->640).");
        insertVersion("loan-eligibility", "1.2.0", "seed/dmn/loan-eligibility-1.2.0.dmn", "DRAFT",
                "analyst", null, null, "Draft for the next iteration.");
    }

    private void seedInsurancePremium() {
        modelVersionRepository.insertModel(
                "insurance-premium", "Insurance Premium", "https://dmn-rules-demo/insurance-premium", "analyst");
        insertVersion("insurance-premium", "1.0.0", "seed/dmn/insurance-premium-1.0.0.dmn", "PUBLISHED",
                "analyst", "approver", Instant.now().minus(20, ChronoUnit.DAYS),
                "Initial published version.");
    }

    private void insertVersion(String modelKey, String version, String classpath, String status,
            String authoredBy, String approvedBy, Instant publishedAt, String changeNote) {
        String xml = readClasspathResource(classpath);
        String sha256 = rowHashService.sha256Hex(xml);
        OffsetDateTime publishedAtOffset = publishedAt == null ? null : publishedAt.atOffset(ZoneOffset.UTC);
        modelVersionRepository.insertVersion(new ModelVersionRow(
                UUID.randomUUID(), modelKey, version, status, xml, sha256, changeNote,
                authoredBy, approvedBy, null, publishedAtOffset));
    }

    private String readClasspathResource(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Seed resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void generateExecutions() {
        Random random = new Random(42);
        Instant now = Instant.now();
        Instant tenDaysAgo = now.minus(10, ChronoUnit.DAYS);

        for (int i = 0; i < 300; i++) {
            Instant executedAt = now.minusSeconds(random.nextInt(30 * 24 * 3600));
            String version = executedAt.isBefore(tenDaysAgo) ? "1.0.0" : "1.1.0";

            Map<String, Object> applicant = Map.of(
                    "age", 18 + random.nextInt(50),
                    "creditScore", 550 + random.nextInt(250),
                    "income", 20000 + random.nextInt(150000),
                    "employmentYears", random.nextInt(15));
            Map<String, Object> loan = Map.of(
                    "amount", 2000 + random.nextInt(48000),
                    "termMonths", 12 + random.nextInt(48));
            Map<String, Object> inputs = Map.of("Applicant", applicant, "Loan", loan);
            String caller = CALLERS[random.nextInt(CALLERS.length)];

            try {
                evaluationService.evaluate(
                        "loan-eligibility", Optional.of(version), inputs, caller, null, true, executedAt);
            } catch (RuntimeException e) {
                log.warn("Seed execution for loan-eligibility failed, skipping", e);
            }
        }

        for (int i = 0; i < 20; i++) {
            Instant executedAt = now.minusSeconds(random.nextInt(20 * 24 * 3600));
            Map<String, Object> driver = Map.of(
                    "age", 18 + random.nextInt(50),
                    "yearsLicensed", random.nextInt(30));
            Map<String, Object> vehicle = Map.of("value", 5000 + random.nextInt(80000));
            Map<String, Object> inputs = Map.of("Driver", driver, "Vehicle", vehicle);
            String caller = CALLERS[random.nextInt(CALLERS.length)];

            try {
                evaluationService.evaluate(
                        "insurance-premium", Optional.empty(), inputs, caller, null, true, executedAt);
            } catch (RuntimeException e) {
                log.warn("Seed execution for insurance-premium failed, skipping", e);
            }
        }
    }
}
