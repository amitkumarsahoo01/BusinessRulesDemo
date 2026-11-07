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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin console's access rule, end to end against a real decision-service (which issues and
 * verifies the tokens): only a signed-in admin gets in; analysts get 403; no token, a garbage token
 * or a forged {@code X-User: admin} header get 401. Also checks that saving settings switches the
 * live provider, is audited, and never exposes an API key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminLlmControllerTest {

    static final Network network = Network.newNetwork();
    static final String ADMIN_PASSWORD = "admin-test-password";

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
            .withEnv("DEMO_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rules-ai.decision-service-password", () -> "test-service-password");
        registry.add("rules-ai.decision-service-base-url", AdminLlmControllerTest::decisionServiceUrl);
        registry.add("spring.ai.anthropic.api-key", () -> "placeholder-not-a-real-key-unused-in-this-test");
        registry.add("rules-ai.providers.openai.api-key", () -> "");
    }

    @BeforeAll
    static void startContainers() {
        postgres.start();
        decisionService.start();
    }

    static String decisionServiceUrl() {
        return "http://" + decisionService.getHost() + ":" + decisionService.getMappedPort(8080);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private static String login(String username, String password) {
        Map<?, ?> response = RestClient.create(decisionServiceUrl()).post().uri("/api/auth/login")
                .body(Map.of("username", username, "password", password))
                .retrieve().body(Map.class);
        return (String) response.get("token");
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, HttpHeaders headers, Object body) {
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    void adminSeesSettingsWithoutAnyKeyValues() {
        ResponseEntity<Map> response = call(HttpMethod.GET, "/api/ai/admin/llm",
                bearer(login("admin", ADMIN_PASSWORD)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody().get("current")).containsKey("provider");
        List<Map<String, Object>> providers = (List<Map<String, Object>>) response.getBody().get("providers");
        assertThat(providers).extracting(p -> p.get("id")).containsExactly("anthropic", "openai", "ollama");
        assertThat(providers).filteredOn(p -> p.get("id").equals("openai"))
                .singleElement().satisfies(p -> assertThat(p.get("apiKeyConfigured")).isEqualTo(false));
        assertThat(response.getBody().toString()).doesNotContain("placeholder-not-a-real-key");
    }

    @Test
    void nonAdminsAndForgedIdentitiesAreRejected() {
        String analystToken = login("analyst", "demo");
        assertThat(call(HttpMethod.GET, "/api/ai/admin/llm", bearer(analystToken), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(call(HttpMethod.GET, "/api/ai/admin/llm", new HttpHeaders(), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/api/ai/admin/llm", bearer("forged.token"), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders forged = new HttpHeaders();
        forged.set("X-User", "admin");
        Map<String, Object> change = Map.of("provider", "ollama", "model", "llama3.1");
        assertThat(call(HttpMethod.PUT, "/api/ai/admin/llm", forged, change).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.PUT, "/api/ai/admin/llm", bearer(analystToken), change).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanSwitchProviderAndTheChangeIsAudited() {
        HttpHeaders admin = bearer(login("admin", ADMIN_PASSWORD));

        ResponseEntity<Map> saved = call(HttpMethod.PUT, "/api/ai/admin/llm", admin,
                Map.of("provider", "ollama", "model", "qwen2.5", "baseUrl", "http://ollama:11434"));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> current = (Map<String, Object>) saved.getBody().get("current");
        assertThat(current).containsEntry("provider", "ollama").containsEntry("model", "qwen2.5")
                .containsEntry("updatedBy", "admin");
        List<Map<String, Object>> history = (List<Map<String, Object>>) saved.getBody().get("history");
        assertThat(history.get(0)).containsEntry("provider", "ollama").containsEntry("changedBy", "admin");

        // Switching to a provider with no key on the server is refused, and nothing changes.
        ResponseEntity<Map> refused = call(HttpMethod.PUT, "/api/ai/admin/llm", admin,
                Map.of("provider", "openai", "model", "gpt-4o"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> after = (Map<String, Object>) call(HttpMethod.GET, "/api/ai/admin/llm", admin, null)
                .getBody().get("current");
        assertThat(after).containsEntry("provider", "ollama");

        // Restore the default so other test classes sharing this DB image are unaffected.
        call(HttpMethod.PUT, "/api/ai/admin/llm", admin, Map.of("provider", "anthropic", "model", "claude-sonnet-4-5-20250929"));
    }
}
