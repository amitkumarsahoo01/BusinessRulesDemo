package com.dmnrules.decision.integration;

import com.dmnrules.decision.api.dto.EvaluateResponseDto;
import com.dmnrules.decision.audit.ExecutionDetail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every /api call needs credentials (HTTP Basic or a login token); the verified identity, never a
 * self-declared X-User, is what gets recorded. Uses a plain, un-customized client so nothing adds
 * credentials behind the test's back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiBasicAuthIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("dmn_rules").withUsername("dmn_rules").withPassword("dmn_rules");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("demo.auth.api-client-password", () -> "client-secret");
        registry.add("demo.auth.service-password", () -> "service-secret");
    }

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @LocalServerPort
    int port;

    private TestRestTemplate client() {
        return new TestRestTemplate();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static final Map<String, Object> INPUTS = Map.of(
            "Applicant", Map.of("age", 34, "creditScore", 640, "income", 72000, "employmentYears", 5),
            "Loan", Map.of("amount", 25000, "termMonths", 48));

    private ResponseEntity<String> call(HttpMethod method, String path, HttpHeaders headers, Object body) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return client().exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders basic(String user, String password) {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(user, password);
        return h;
    }

    @Test
    void basicAuthEvaluatesAndRecordsTheAuthenticatedCaller() {
        HttpHeaders headers = basic("auditor", "demo");
        headers.set("X-User", "approver"); // a self-declared identity must be ignored
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<EvaluateResponseDto> response = client().exchange(
                url("/api/decisions/loan-eligibility/evaluate"), HttpMethod.POST,
                new HttpEntity<>(INPUTS, headers), EvaluateResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ExecutionDetail detail = client().exchange(url("/api/executions/" + response.getBody().executionId()),
                HttpMethod.GET, new HttpEntity<>(basic("auditor", "demo")), ExecutionDetail.class).getBody();
        assertThat(detail.caller()).isEqualTo("auditor");
    }

    @Test
    void missingOrWrongCredentialsAre401WithABasicChallenge() {
        ResponseEntity<String> none = call(HttpMethod.GET, "/api/models", new HttpHeaders(), null);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(none.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
        assertThat(none.getHeaders().getContentType().toString()).contains("problem+json");

        assertThat(call(HttpMethod.GET, "/api/models", basic("analyst", "wrong"), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/api/models", basic("nobody", "demo"), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders xUserOnly = new HttpHeaders();
        xUserOnly.set("X-User", "analyst");
        assertThat(call(HttpMethod.POST, "/api/decisions/loan-eligibility/evaluate", xUserOnly, INPUTS).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void webAppRequestsGetNoBrowserBasicPrompt() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Requested-With", "XMLHttpRequest");
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/models", headers, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
    }

    @Test
    void loginAndHealthStayOpen_bearerTokensStillWork() {
        assertThat(call(HttpMethod.GET, "/api/health", new HttpHeaders(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> login = client().postForObject(url("/api/auth/login"),
                Map.of("username", "approver", "password", "demo"), Map.class);
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth((String) login.get("token"));
        ResponseEntity<String> me = call(HttpMethod.GET, "/api/auth/me", bearer, null);
        assertThat(me.getBody()).contains("\"username\":\"approver\"");
        assertThat(call(HttpMethod.GET, "/api/auth/me", basic("auditor", "demo"), null).getBody())
                .contains("\"role\":\"auditor\"");
    }

    @Test
    void apiClientCanEvaluateAndReadButNotChangeRules() {
        HttpHeaders client = basic("api-client", "client-secret");
        assertThat(call(HttpMethod.POST, "/api/decisions/loan-eligibility/evaluate", client, INPUTS).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/decisions/loan-eligibility/simulate", basic("api-client", "client-secret"), INPUTS)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/executions?size=1", basic("api-client", "client-secret"), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/models/loan-eligibility/rollback?toVersion=1.0.0",
                basic("api-client", "client-secret"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void auditorIsReadOnlyOnTheServerToo() {
        assertThat(call(HttpMethod.POST, "/api/decisions/loan-eligibility/simulate", basic("auditor", "demo"), INPUTS)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/models/loan-eligibility/rollback?toVersion=1.0.0",
                basic("auditor", "demo"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void apiOnlyAccountsCannotSignInToTheWebApp() {
        ResponseEntity<String> response = client().postForEntity(url("/api/auth/login"),
                Map.of("username", "api-client", "password", "client-secret"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void onlyTheServiceAccountMayActOnBehalfOfAUser() {
        HttpHeaders service = basic("ai-service", "service-secret");
        service.set("X-User", "approver");
        ResponseEntity<String> me = call(HttpMethod.GET, "/api/auth/me", service, null);
        assertThat(me.getBody()).contains("\"username\":\"ai-service\""); // /me reports the real principal

        service = basic("ai-service", "service-secret");
        service.set("X-User", "approver");
        service.setContentType(MediaType.APPLICATION_JSON);
        EvaluateResponseDto evaluated = client().exchange(url("/api/decisions/loan-eligibility/evaluate"), HttpMethod.POST,
                new HttpEntity<>(INPUTS, service), EvaluateResponseDto.class).getBody();
        ExecutionDetail detail = client().exchange(url("/api/executions/" + evaluated.executionId()), HttpMethod.GET,
                new HttpEntity<>(basic("auditor", "demo")), ExecutionDetail.class).getBody();
        assertThat(detail.caller()).isEqualTo("approver");
    }

    @Test
    void serviceActingForAnAuditorIsLimitedByTheAuditorsRole() {
        HttpHeaders forAuditor = basic("ai-service", "service-secret");
        forAuditor.set("X-User", "auditor");
        assertThat(call(HttpMethod.POST, "/api/models/loan-eligibility/rollback?toVersion=1.0.0", forAuditor, Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders forUnknown = basic("ai-service", "service-secret");
        forUnknown.set("X-User", "someone-made-up");
        assertThat(call(HttpMethod.POST, "/api/models/loan-eligibility/rollback?toVersion=1.0.0", forUnknown, Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
