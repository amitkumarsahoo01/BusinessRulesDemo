package com.dmnrules.ai.decision;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Talks to decision-service's REST API over the docker network. The AI never evaluates a decision
 * itself (CLAUDE.md rule 1) — every answer about rule behaviour goes through here, to the real
 * engine, never the LLM's own guess.
 */
@Component
public class DecisionServiceClient {

    private final RestClient restClient;

    /**
     * Calls decision-service as the internal {@code ai-service} account (HTTP Basic). Where a call
     * acts for the signed-in user (e.g. proposeChange creating a draft), it also sends {@code X-User},
     * which decision-service honours only for service accounts.
     */
    public DecisionServiceClient(
            @Value("${rules-ai.decision-service-base-url}") String baseUrl,
            @Value("${rules-ai.decision-service-username:ai-service}") String username,
            @Value("${rules-ai.decision-service-password:}") String password) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(h -> {
                    if (password != null && !password.isBlank()) {
                        h.setBasicAuth(username, password, java.nio.charset.StandardCharsets.UTF_8);
                    }
                    h.set("X-Requested-With", "XMLHttpRequest");
                })
                .build();
    }

    public List<ModelSummary> listModels() {
        return restClient.get().uri("/api/models")
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public ModelVersion resolveVersion(String modelKey, String version) {
        String uri = version == null
                ? "/api/models/{modelKey}/resolve"
                : "/api/models/{modelKey}/resolve?version={version}";
        return version == null
                ? restClient.get().uri(uri, modelKey).retrieve().body(ModelVersion.class)
                : restClient.get().uri(uri, modelKey, version).retrieve().body(ModelVersion.class);
    }

    public ModelStructure getStructure(String modelKey, String versionId) {
        return restClient.get().uri("/api/models/{modelKey}/drafts/{id}/structure", modelKey, versionId)
                .retrieve().body(ModelStructure.class);
    }

    public DecisionTable getDecisionTable(String modelKey, String versionId, String decisionName) {
        return restClient.get()
                .uri("/api/models/{modelKey}/drafts/{id}/decisions/{name}", modelKey, versionId, decisionName)
                .retrieve().body(DecisionTable.class);
    }

    public List<ValidationMessage> analyze(String modelKey, String versionId) {
        return restClient.get().uri("/api/models/{modelKey}/drafts/{id}/analysis", modelKey, versionId)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public List<DecisionDiff> diffVersions(String modelKey, String fromVersion, String toVersion) {
        return restClient.get()
                .uri("/api/models/{modelKey}/versions/{v1}/diff/{v2}", modelKey, fromVersion, toVersion)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public ExecutionDetail getExecution(String executionId) {
        return restClient.get().uri("/api/executions/{id}", executionId).retrieve().body(ExecutionDetail.class);
    }

    public PageResult<ExecutionSummary> queryExecutions(
            String modelKey, String version, String status, String caller, int size) {
        return restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/api/executions").queryParam("size", size);
                    if (modelKey != null) builder.queryParam("modelKey", modelKey);
                    if (version != null) builder.queryParam("version", version);
                    if (status != null) builder.queryParam("status", status);
                    if (caller != null) builder.queryParam("caller", caller);
                    return builder.build();
                })
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public EvaluateResponse simulate(String modelKey, String version, Map<String, Object> inputs) {
        String uri = version == null
                ? "/api/decisions/{modelKey}/simulate?trace=true"
                : "/api/decisions/{modelKey}/simulate?trace=true&version={version}";
        var spec = version == null
                ? restClient.post().uri(uri, modelKey)
                : restClient.post().uri(uri, modelKey, version);
        return spec.body(inputs).retrieve().body(EvaluateResponse.class);
    }

    public ModelVersion proposeChange(String modelKey, String decisionName, String hitPolicy,
            List<DecisionTable.Rule> rules, String changeNote, String actor) {
        Map<String, Object> guidedTableEdit = Map.of(
                "decisionName", decisionName, "hitPolicy", hitPolicy, "rules", rules);
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("dmnXml", null);
        request.put("guidedTableEdit", guidedTableEdit);
        request.put("changeNote", changeNote);
        return restClient.post().uri("/api/models/{modelKey}/drafts", modelKey)
                .header("X-User", actor)
                .body(request)
                .retrieve().body(ModelVersion.class);
    }
}
