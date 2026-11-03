package com.dmnrules.ai.retrieval;

import com.dmnrules.ai.decision.DecisionServiceClient;
import com.dmnrules.ai.decision.DecisionTable;
import com.dmnrules.ai.decision.ModelStructure;
import com.dmnrules.ai.decision.ModelSummary;
import com.dmnrules.ai.decision.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embeds policy documents and rule-row business-reason annotations into pgvector on first start
 * (docs/Architecture.md "Retrieval for documentation"). Pulls rule annotations live from
 * decision-service's API rather than duplicating the seed DMN content here, so this stays correct
 * if the seed models ever change. Retries briefly since decision-service's own seeding can still
 * be in progress when this service starts (docker-compose only guarantees container start order,
 * not application readiness).
 */
@Component
public class DocumentSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentSeedRunner.class);
    private static final String[] POLICY_DOCS = {
            "seed/policy/loan-eligibility-policy.md", "seed/policy/insurance-premium-policy.md"
    };

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final DecisionServiceClient decisionServiceClient;
    private final ResourceLoader resourceLoader;
    private final boolean enabled;

    public DocumentSeedRunner(
            VectorStore vectorStore, JdbcTemplate jdbcTemplate, DecisionServiceClient decisionServiceClient,
            ResourceLoader resourceLoader, @Value("${demo.seed.enabled:true}") boolean enabled) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.decisionServiceClient = decisionServiceClient;
        this.resourceLoader = resourceLoader;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Demo seeding disabled (demo.seed.enabled=false)");
            return;
        }
        Long existing = jdbcTemplate.queryForObject("SELECT count(*) FROM rule_doc_embedding", Long.class);
        if (existing != null && existing > 0) {
            log.info("Retrieval documents already embedded ({} rows), skipping", existing);
            return;
        }

        List<Document> documents = new ArrayList<>(loadPolicyDocs());
        documents.addAll(loadRuleAnnotations());

        if (documents.isEmpty()) {
            log.warn("No documents to embed (decision-service may not be ready yet); skipping retrieval seeding");
            return;
        }
        vectorStore.add(documents);
        log.info("Embedded {} retrieval documents into pgvector", documents.size());
    }

    private List<Document> loadPolicyDocs() {
        List<Document> docs = new ArrayList<>();
        for (String path : POLICY_DOCS) {
            try {
                String text = resourceLoader.getResource("classpath:" + path).getContentAsString(StandardCharsets.UTF_8);
                docs.add(new Document(text, Map.of("source", path, "type", "policy")));
            } catch (IOException e) {
                log.warn("Could not load policy document {}: {}", path, e.getMessage());
            }
        }
        return docs;
    }

    private List<Document> loadRuleAnnotations() {
        List<ModelSummary> models = fetchModelsWithRetry();
        List<Document> docs = new ArrayList<>();
        for (ModelSummary model : models) {
            if (model.latestPublishedVersion() == null) {
                continue;
            }
            try {
                ModelVersion version = decisionServiceClient.resolveVersion(model.modelKey(), model.latestPublishedVersion());
                ModelStructure structure = decisionServiceClient.getStructure(model.modelKey(), version.id());
                for (ModelStructure.DecisionNode decision : structure.decisions()) {
                    if (!"DECISION_TABLE".equals(decision.kind())) {
                        continue;
                    }
                    DecisionTable table = decisionServiceClient.getDecisionTable(model.modelKey(), version.id(), decision.name());
                    for (DecisionTable.Rule rule : table.rules()) {
                        if (rule.description() == null || rule.description().isBlank()) {
                            continue;
                        }
                        String text = "%s / %s: %s -> %s. Reason: %s".formatted(
                                model.name(), decision.name(), String.join(", ", rule.inputEntries()),
                                rule.outputEntry(), rule.description());
                        docs.add(new Document(text, Map.of(
                                "source", model.modelKey() + "/" + decision.name() + "#" + rule.id(),
                                "type", "annotation", "modelKey", model.modelKey())));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Could not load rule annotations for {}: {}", model.modelKey(), e.getMessage());
            }
        }
        return docs;
    }

    private List<ModelSummary> fetchModelsWithRetry() {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 15; attempt++) {
            try {
                return decisionServiceClient.listModels();
            } catch (RuntimeException e) {
                lastError = e;
                sleepQuietly(2000);
            }
        }
        log.warn("decision-service was not reachable after retries: {}", lastError == null ? "unknown" : lastError.getMessage());
        return List.of();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
