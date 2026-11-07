package com.dmnrules.ai.api;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers conversation creation, pinned context, and history retrieval without calling the LLM
 * (CI/sandboxes without a real ANTHROPIC_API_KEY can still run this). The full chat round trip
 * with a real model response is in ChatEndToEndTest, gated on that key being present.
 *
 * <p>Needs a real decision-service alongside Postgres: {@code ai_interaction_audit} is created by
 * decision-service's own Flyway migration (it's listed under BUILD_PLAN's M2 schema, not M5's),
 * and in the real deployment decision-service is always present whenever ai-service is — there is
 * no standalone-ai-service topology to test against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConversationControllerTest {

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
        registry.add("spring.ai.anthropic.api-key", () -> "placeholder-not-a-real-key-unused-in-this-test");
    }

    @BeforeAll
    static void startContainers() {
        postgres.start();
        decisionService.start();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<StartConversationResponse> startConversation(StartConversationRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("analyst", "demo");
        return restTemplate.exchange("/api/ai/conversations", HttpMethod.POST,
                new HttpEntity<>(request, headers), StartConversationResponse.class);
    }

    @Test
    void startConversation_withNoPinnedContext_succeeds() {
        ResponseEntity<StartConversationResponse> response = startConversation(new StartConversationRequest(null, null, null));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().conversationId()).isNotBlank();
        assertThat(UUID.fromString(response.getBody().conversationId())).isNotNull();
    }

    @Test
    void startConversation_withPinnedExecution_succeeds() {
        String executionId = UUID.randomUUID().toString();
        ResponseEntity<StartConversationResponse> response = startConversation(
                new StartConversationRequest("loan-eligibility", "1.1.0", executionId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void history_forNewConversation_isEmpty() {
        String conversationId = startConversation(new StartConversationRequest(null, null, null))
                .getBody().conversationId();

        ResponseEntity<String> response = restTemplate.withBasicAuth("analyst", "demo").getForEntity(
                "/api/ai/conversations/" + conversationId + "/messages", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void history_forUnknownConversation_returns404ProblemJson() {
        ResponseEntity<String> response = restTemplate.withBasicAuth("analyst", "demo").getForEntity(
                "/api/ai/conversations/" + UUID.randomUUID() + "/messages", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void chatEndpointsRejectMissingCredentialsAndForgedXUser() {
        HttpHeaders forged = new HttpHeaders();
        forged.set("X-User", "analyst");
        ResponseEntity<String> withForgedHeader = restTemplate.exchange("/api/ai/conversations", HttpMethod.POST,
                new HttpEntity<>(new StartConversationRequest(null, null, null), forged), String.class);
        assertThat(withForgedHeader.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(restTemplate.withBasicAuth("analyst", "wrong")
                .getForEntity("/api/ai/conversations/" + UUID.randomUUID() + "/messages", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
