package com.dmnrules.ai.chat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real BUILD_PLAN M5 acceptance check: the five demo questions, answered by the real model
 * against real seeded data through real tool calls. Needs ANTHROPIC_API_KEY — the sandbox this
 * was built in doesn't have one (the user supplies it; see docs/DECISIONS.md), so this is
 * {@code @Disabled} unless that env var is set, which is also true when this runs in CI unless a
 * key is configured there. Run it directly once a key is available:
 * {@code ANTHROPIC_API_KEY=sk-... ./mvnw -pl ai-service test -Dtest=ChatEndToEndTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ChatEndToEndTest {

    static final Network network = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dmn_rules")
            .withUsername("dmn_rules")
            .withPassword("dmn_rules")
            .withNetwork(network)
            .withNetworkAliases("postgres");

    @Container
    static final GenericContainer<?> decisionService = new GenericContainer<>(DockerImageName.parse("deploy-decision-service:latest"))
            .withNetwork(network)
            .withExposedPorts(8080)
            .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/dmn_rules")
            .withEnv("SPRING_DATASOURCE_USERNAME", "dmn_rules")
            .withEnv("SPRING_DATASOURCE_PASSWORD", "dmn_rules")
            .withEnv("AI_SERVICE_PASSWORD", "test-service-password")
            .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rules-ai.decision-service-password", () -> "test-service-password");
        registry.add("rules-ai.decision-service-base-url",
                () -> "http://" + decisionService.getHost() + ":" + decisionService.getMappedPort(8080));
        registry.add("spring.ai.anthropic.api-key", () -> System.getenv("ANTHROPIC_API_KEY"));
    }

    @BeforeAll
    static void startContainers() {
        postgres.start();
        decisionService.start();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate decisionServiceDb; // same Postgres instance decision-service uses

    private String ask(String content) {
        return ask(content, null, null, null);
    }

    private String ask(String content, String pinnedModelKey, String pinnedVersion, String pinnedExecutionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("analyst", "demo");
        var start = restTemplate.exchange("/api/ai/conversations", HttpMethod.POST,
                new HttpEntity<>(new com.dmnrules.ai.api.StartConversationRequest(pinnedModelKey, pinnedVersion, pinnedExecutionId), headers),
                com.dmnrules.ai.api.StartConversationResponse.class);
        String conversationId = start.getBody().conversationId();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/ai/conversations/" + conversationId + "/messages", HttpMethod.POST,
                new HttpEntity<>(new com.dmnrules.ai.api.SendMessageRequest(content), headers), String.class);
        return response.getBody();
    }

    @Test
    void q1_explainTheModelInPlainEnglish() {
        String answer = ask("Explain the loan-eligibility model in plain English.");
        assertThat(answer).isNotBlank();
        assertThat(answer.toLowerCase()).containsAnyOf("eligibility", "risk category", "debt ratio", "credit score");
    }

    @Test
    void q2_whyWasThisExecutionDeclined() {
        String declinedId = decisionServiceDb.queryForObject(
                "SELECT id FROM decision_execution WHERE status = 'SUCCEEDED' "
                        + "AND outputs->>'Eligibility' = 'DECLINED' LIMIT 1",
                String.class);
        assertThat(declinedId).as("seed data should include at least one declined execution").isNotNull();

        String answer = ask("Why was this declined?", "loan-eligibility", null, declinedId);
        assertThat(answer).isNotBlank();
        assertThat(answer.toUpperCase()).contains("HIGH"); // DECLINED only fires on HIGH risk category
    }

    @Test
    void q3_whatIfCreditScore700() {
        String answer = ask("For the loan-eligibility model, would a 34 year old applicant with "
                + "$72000 income, 5 years employment, and a $25000 loan over 48 months be approved "
                + "with a credit score of 700?");
        assertThat(answer).isNotBlank();
        assertThat(answer.toUpperCase()).containsAnyOf("APPROVED", "REVIEW", "DECLINED");
    }

    @Test
    void q4_whatChangedAndHowManyFlip() {
        String answer = ask("What changed in loan-eligibility between version 1.0.0 and 1.1.0, "
                + "and how many past cases would flip to a different outcome?");
        assertThat(answer).isNotBlank();
        assertThat(answer).contains("1.1.0");
        assertThat(answer.toLowerCase()).containsAnyOf("threshold", "credit score", "720", "640");
    }

    @Test
    void q5_gapsOrOverlapsInRiskCategory() {
        String answer = ask("Are there any gaps or overlaps in the Risk Category decision table?");
        assertThat(answer).isNotBlank();
        assertThat(answer.toLowerCase()).containsAnyOf("gap", "overlap", "no gaps", "no overlaps", "none");
    }
}
