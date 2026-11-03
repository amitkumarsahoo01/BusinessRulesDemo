package com.dmnrules.ai.tools;

import com.dmnrules.ai.decision.DecisionServiceClient;
import com.dmnrules.ai.retrieval.DocumentRetrievalTools;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises RuleTools and DocumentRetrievalTools against a REAL decision-service container (the
 * image built by {@code docker compose build}) with REAL seeded data, and REAL local embeddings
 * (spring-ai-starter-model-transformers needs no API key) — everything except the LLM call
 * itself, which needs a real ANTHROPIC_API_KEY the sandbox doesn't have (see docs/DECISIONS.md).
 * A placeholder key is supplied only so the AnthropicChatModel bean can construct; nothing here
 * calls it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class RuleToolsIntegrationTest {

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
            .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
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
    private RuleTools ruleTools;

    @Autowired
    private DocumentRetrievalTools documentRetrievalTools;

    @Autowired
    private DecisionServiceClient decisionServiceClient;

    @Test
    void listModels_returnsSeededModels() {
        var models = decisionServiceClient.listModels();
        assertThat(models).extracting(com.dmnrules.ai.decision.ModelSummary::modelKey)
                .contains("loan-eligibility", "insurance-premium");
    }

    @Test
    void getModelStructure_listsDecisionsAndDependencies() {
        Object result = ruleTools.getModelStructure("loan-eligibility", null, null);
        String json = result.toString();
        assertThat(json).contains("Eligibility").contains("Risk Category").contains("Debt Ratio");
    }

    @Test
    void getDecisionTable_returnsRowsWithAnnotations() {
        Object result = ruleTools.getDecisionTable("loan-eligibility", "Eligibility", null, null);
        String json = result.toString();
        assertThat(json).contains("APPROVED").contains("DECLINED").contains("REVIEW");
    }

    @Test
    void simulate_runsTheRealEngineForAWhatIfQuestion() {
        String inputs = """
                {"Applicant":{"age":34,"creditScore":700,"income":72000,"employmentYears":5},
                 "Loan":{"amount":25000,"termMonths":48}}
                """;
        Object result = ruleTools.simulate("loan-eligibility", inputs, null, null);
        assertThat(result.toString()).containsAnyOf("APPROVED", "REVIEW", "DECLINED");
    }

    @Test
    void queryExecutions_seesTheSeededExecutionHistory() {
        var result = (com.dmnrules.ai.decision.PageResult<?>) ruleTools.queryExecutions(
                "loan-eligibility", null, null, null, 5, null);
        assertThat(result.items()).hasSize(5);
        assertThat(result.total()).isGreaterThan(200); // ~300 seeded for loan-eligibility
    }

    @Test
    void estimateImpact_resimulatesSampledExecutionsAgainstAnotherVersion() {
        Object result = ruleTools.estimateImpact("loan-eligibility", "1.0.0", "1.1.0", 5, null);
        assertThat(result.toString()).contains("sampled").contains("flipped");
    }

    @Test
    void analyzeModel_returnsEngineComputedValidationFindings() {
        Object result = ruleTools.analyzeModel("loan-eligibility", null, null);
        assertThat(result.toString()).isNotBlank();
    }

    @Test
    void diffVersions_showsTheThresholdTighteningBetweenPublishedVersions() {
        Object result = ruleTools.diffVersions("loan-eligibility", "1.0.0", "1.1.0", null);
        assertThat(result.toString()).contains("Risk Category");
    }

    @Test
    void searchDocs_findsThePolicyDocumentWithRealLocalEmbeddings() {
        Object result = documentRetrievalTools.searchDocs(
                "why do loan applicants under 21 years old always need manual review", 5, null);
        assertThat(result.toString().toLowerCase()).contains("21").contains("manual review");
    }
}
