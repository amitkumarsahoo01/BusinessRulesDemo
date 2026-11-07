package com.dmnrules.decision.integration;

import com.dmnrules.decision.auth.LoginRequest;
import com.dmnrules.decision.auth.LoginResponse;
import com.dmnrules.decision.dashboard.ExecutionStatsDto;
import com.dmnrules.decision.dashboard.RuleSummaryDto;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Dashboard aggregates over the real seeded executions, and the admin account / token flow. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@org.springframework.context.annotation.Import(DemoUserBasicAuth.class)
class DashboardAndAuthIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("dmn_rules")
            .withUsername("dmn_rules")
            .withPassword("dmn_rules");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("demo.auth.admin-password", () -> "s3cret-admin");
        registry.add("demo.auth.token-secret", () -> "integration-test-secret");
    }

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void executionStats_coverEverySeededExecutionAndFillEveryDay() {
        ResponseEntity<ExecutionStatsDto> response =
                restTemplate.getForEntity("/api/dashboard/executions?days=31", ExecutionStatsDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ExecutionStatsDto stats = response.getBody();
        assertThat(stats).isNotNull();
        assertThat(stats.daily()).hasSize(31);
        // The seed spreads ~300 executions over the last 30 days.
        assertThat(stats.total()).isGreaterThan(250);
        assertThat(stats.daily().stream().mapToLong(ExecutionStatsDto.DailyBucket::total).sum())
                .isEqualTo(stats.total());
        assertThat(stats.succeeded() + stats.failed()).isEqualTo(stats.total());
        assertThat(stats.byModel()).extracting(ExecutionStatsDto.ModelBreakdown::modelKey)
                .contains("loan-eligibility", "insurance-premium");
        assertThat(stats.outcomes())
                .filteredOn(o -> o.modelKey().equals("loan-eligibility") && o.output().equals("Eligibility"))
                .singleElement()
                .satisfies(o -> assertThat(o.values()).extracting(ExecutionStatsDto.OutcomeCount::value)
                        .containsAnyOf("APPROVED", "REVIEW", "DECLINED"));
    }

    @Test
    void executionStats_filterByModelAndRejectBadRange() {
        ExecutionStatsDto stats = restTemplate.getForObject(
                "/api/dashboard/executions?days=31&modelKey=insurance-premium", ExecutionStatsDto.class);
        assertThat(stats.byModel()).extracting(ExecutionStatsDto.ModelBreakdown::modelKey)
                .containsExactly("insurance-premium");

        ResponseEntity<String> bad = restTemplate.getForEntity("/api/dashboard/executions?days=0", String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ruleSummaries_describeLiveVersionDecisionsAndDraft() {
        RuleSummaryDto[] summaries = restTemplate.getForObject("/api/dashboard/rules?days=31", RuleSummaryDto[].class);

        RuleSummaryDto loan = java.util.Arrays.stream(summaries)
                .filter(s -> s.modelKey().equals("loan-eligibility")).findFirst().orElseThrow();
        assertThat(loan.liveVersion()).isEqualTo("1.1.0");
        assertThat(loan.inProgressVersion()).isEqualTo("1.2.0");
        assertThat(loan.versionCount()).isEqualTo(3);
        assertThat(loan.executionsInPeriod()).isPositive();
        assertThat(loan.inputs()).extracting(i -> i.name()).contains("Applicant", "Loan");
        assertThat(loan.decisions()).filteredOn(d -> d.name().equals("Eligibility")).singleElement()
                .satisfies(d -> {
                    assertThat(d.kind()).isEqualTo("DECISION_TABLE");
                    assertThat(d.hitPolicy()).isEqualTo("FIRST");
                    assertThat(d.ruleCount()).isPositive();
                });
    }

    @Test
    void adminCanLogInWithConfiguredPasswordAndTokenProvesIdentity() {
        LoginResponse login = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("admin", "s3cret-admin"), LoginResponse.class);
        assertThat(login.role()).isEqualTo("admin");
        assertThat(login.token()).isNotBlank();

        ResponseEntity<Map> me = getMe("Bearer " + login.token());
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("username", "admin").containsEntry("role", "admin");
    }

    @Test
    void adminDoesNotAcceptTheSharedDemoPassword() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest("admin", "demo"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meRejectsMissingForgedAndTamperedTokens() {
        assertThat(getMe(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getMe("Bearer not-a-token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // A real analyst token with its payload swapped for an admin claim must not verify.
        LoginResponse analyst = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("analyst", "demo"), LoginResponse.class);
        String signature = analyst.token().substring(analyst.token().indexOf('.'));
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"admin\",\"role\":\"admin\",\"exp\":9999999999}".getBytes());
        assertThat(getMe("Bearer " + forgedPayload + signature).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> analystMe = getMe("Bearer " + analyst.token());
        assertThat(analystMe.getBody()).containsEntry("role", "rule-author");
    }

    private ResponseEntity<Map> getMe(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return restTemplate.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
