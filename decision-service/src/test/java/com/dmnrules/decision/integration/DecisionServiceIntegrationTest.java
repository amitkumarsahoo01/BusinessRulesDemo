package com.dmnrules.decision.integration;

import com.dmnrules.decision.api.dto.EvaluateResponseDto;
import com.dmnrules.decision.audit.ExecutionDetail;
import com.dmnrules.decision.auth.LoginRequest;
import com.dmnrules.decision.auth.LoginResponse;
import com.dmnrules.decision.model.ModelRegistry;
import com.dmnrules.decision.model.ModelVersionRepository;
import com.dmnrules.decision.model.ModelVersionRow;
import com.dmnrules.decision.trace.TraceStepRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@org.springframework.context.annotation.Import(DemoUserBasicAuth.class)
class DecisionServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("dmn_rules")
            .withUsername("dmn_rules")
            .withPassword("dmn_rules");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ModelVersionRepository modelVersionRepository;

    @Autowired
    private ModelRegistry modelRegistry;

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    private static Map<String, Object> loanEligibilityInputs() {
        Map<String, Object> applicant = Map.of(
                "age", 34, "creditScore", 640, "income", 72000, "employmentYears", 5);
        Map<String, Object> loan = Map.of("amount", 25000, "termMonths", 48);
        return Map.of("Applicant", applicant, "Loan", loan);
    }

    @Test
    void evaluateLoanEligibility_returnsOutputsAndExecutionId_withTraceShowingMatchedRows() {
        ResponseEntity<EvaluateResponseDto> response = restTemplate.postForEntity(
                "/api/decisions/loan-eligibility/evaluate?trace=true",
                loanEligibilityInputs(), EvaluateResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        EvaluateResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.executionId()).isNotNull();
        assertThat(body.status()).isEqualTo("SUCCEEDED");
        assertThat(body.outputs()).containsEntry("Eligibility", "APPROVED");
        assertThat(body.outputs()).containsEntry("Risk Category", "MEDIUM");

        List<TraceStepRecord> trace = body.trace();
        assertThat(trace).isNotEmpty();
        assertThat(trace)
                .filteredOn(step -> "DECISION_TABLE".equals(step.nodeType()) && "Eligibility".equals(step.nodeName()))
                .first()
                .satisfies(step -> assertThat(step.matchedRules()).isNotEmpty());

        ResponseEntity<ExecutionDetail> detail = restTemplate.getForEntity(
                "/api/executions/" + body.executionId(), ExecutionDetail.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).isNotNull();
        assertThat(detail.getBody().status()).isEqualTo("SUCCEEDED");
        assertThat(detail.getBody().trace()).isNotEmpty();
    }

    @Test
    void evaluateWithoutVersion_resolvesLatestPublishedVersion() {
        // Uses insurance-premium (single published version, untouched by the notify test below) so
        // this assertion doesn't depend on test execution order within the shared Spring context.
        Map<String, Object> driver = Map.of("age", 30, "yearsLicensed", 10);
        Map<String, Object> vehicle = Map.of("value", 15000);
        ResponseEntity<EvaluateResponseDto> response = restTemplate.postForEntity(
                "/api/decisions/insurance-premium/evaluate",
                Map.of("Driver", driver, "Vehicle", vehicle), EvaluateResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().modelVersion()).isEqualTo("1.0.0");
    }

    @Test
    void evaluateUnknownModel_returns404ProblemJson() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/decisions/does-not-exist/evaluate", Map.of(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void simulateDoesNotWriteAuditRecord() {
        long before = countExecutions();

        ResponseEntity<EvaluateResponseDto> response = restTemplate.postForEntity(
                "/api/decisions/loan-eligibility/simulate", loanEligibilityInputs(), EvaluateResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().executionId()).isNull();
        assertThat(countExecutions()).isEqualTo(before);
    }

    @Test
    void searchExecutions_filtersByModelKeyAndPages() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/executions?modelKey=loan-eligibility&size=5", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("items");
        assertThat((List<?>) response.getBody().get("items")).hasSizeLessThanOrEqualTo(5);
        assertThat(((Number) response.getBody().get("total"))).matches(n -> n.longValue() > 0);
    }

    @Test
    void publishingNewVersionAndNotifying_hotSwapsIntoRegistryWithoutRestart() {
        String modelKey = "loan-eligibility";
        String newVersion = "1.1.1";
        String xml = modelVersionRepository.findByKeyAndVersion(modelKey, "1.1.0").orElseThrow().dmnXml();

        modelVersionRepository.insertVersion(new ModelVersionRow(
                UUID.randomUUID(), modelKey, newVersion, "PUBLISHED", xml,
                "0".repeat(64), "notify test", "analyst", "approver", null,
                java.time.OffsetDateTime.now()));

        assertThat(response(modelKey, newVersion).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        jdbcTemplate.execute("SELECT pg_notify('model_published', '" + modelKey + ":" + newVersion + "')");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(response(modelKey, newVersion).getStatusCode()).isEqualTo(HttpStatus.OK));
    }

    @Test
    void login_withCorrectPassword_returnsUserAndRole() {
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest("analyst", "demo"), LoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().username()).isEqualTo("analyst");
        assertThat(response.getBody().role()).isEqualTo("rule-author");
    }

    @Test
    void login_withWrongPassword_returns401ProblemJson() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest("analyst", "wrong"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    private ResponseEntity<EvaluateResponseDto> response(String modelKey, String version) {
        return restTemplate.postForEntity(
                "/api/decisions/" + modelKey + "/evaluate?version=" + version,
                loanEligibilityInputs(), EvaluateResponseDto.class);
    }

    private long countExecutions() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM decision_execution", Long.class);
        return count == null ? 0 : count;
    }
}
