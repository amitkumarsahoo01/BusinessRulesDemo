package com.dmnrules.ai.tools;

import com.dmnrules.ai.decision.DecisionServiceClient;
import com.dmnrules.ai.decision.DecisionTable;
import com.dmnrules.ai.decision.ExecutionDetail;
import com.dmnrules.ai.decision.ModelVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only tools the LLM calls to ground its answers in real rules and real audit data
 * (CLAUDE.md rule 1: the LLM never decides, it explains via tools). Only {@link #proposeChange}
 * writes anything, and it only ever creates a draft — never publishes (CLAUDE.md rule 6).
 */
@Component
public class RuleTools {

    private final DecisionServiceClient client;
    private final ObjectMapper objectMapper;

    public RuleTools(DecisionServiceClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "List decision models, with their key, name, namespace, owner, latest "
            + "published version, and any in-progress draft status.")
    public Object listModels(ToolContext toolContext) {
        ToolCallLog.record(toolContext, "listModels", "");
        return client.listModels();
    }

    @Tool(description = "Get a model's structure: its input data (name, type) and its decisions "
            + "(name, whether it's a literal-expression formula or a decision table, and which "
            + "inputs/decisions it depends on). Use this before getDecisionTable to know what "
            + "decisions exist and how they relate (the DRG).")
    public Object getModelStructure(
            @ToolParam(description = "The model key, e.g. 'loan-eligibility'") String modelKey,
            @ToolParam(required = false, description = "A specific version, e.g. '1.1.0'. Omit for the latest published version.")
                    String version,
            ToolContext toolContext) {
        ToolCallLog.record(toolContext, "getModelStructure", modelKey + (version == null ? "" : "@" + version));
        ModelVersion resolved = client.resolveVersion(modelKey, blankToNull(version));
        return client.getStructure(modelKey, resolved.id());
    }

    @Tool(description = "Get one decision table's full content: hit policy, input/output columns, "
            + "and every row with its FEEL conditions, output, row id, and the business-reason "
            + "annotation. Row numbers in citations are 1-indexed (row 1 is the first rule).")
    public Object getDecisionTable(
            @ToolParam(description = "The model key, e.g. 'loan-eligibility'") String modelKey,
            @ToolParam(description = "The decision name exactly as returned by getModelStructure, e.g. 'Eligibility'")
                    String decisionName,
            @ToolParam(required = false, description = "A specific version. Omit for the latest published version.")
                    String version,
            ToolContext toolContext) {
        ToolCallLog.record(toolContext, "getDecisionTable", modelKey + "/" + decisionName);
        ModelVersion resolved = client.resolveVersion(modelKey, blankToNull(version));
        DecisionTable table = client.getDecisionTable(modelKey, resolved.id(), decisionName);
        return Map.of("version", resolved.version(), "table", table);
    }

    @Tool(description = "Engine-computed quality findings for a model version: schema/FEEL compile "
            + "errors, and decision-table analysis (gaps, overlapping rows, unreachable/subsumed "
            + "rows). These are computed by the DMN validator, not guessed.")
    public Object analyzeModel(
            @ToolParam(description = "The model key") String modelKey,
            @ToolParam(required = false, description = "A specific version. Omit for the latest published version.")
                    String version,
            ToolContext toolContext) {
        ToolCallLog.record(toolContext, "analyzeModel", modelKey + (version == null ? "" : "@" + version));
        ModelVersion resolved = client.resolveVersion(modelKey, blankToNull(version));
        return Map.of("version", resolved.version(), "messages", client.analyze(modelKey, resolved.id()));
    }

    @Tool(description = "Structural diff between two published versions of a model: which decisions "
            + "and which rows were added, removed, or changed, with their before/after FEEL text.")
    public Object diffVersions(
            @ToolParam(description = "The model key") String modelKey,
            @ToolParam(description = "The earlier version, e.g. '1.0.0'") String fromVersion,
            @ToolParam(description = "The later version, e.g. '1.1.0'") String toVersion,
            ToolContext toolContext) {
        ToolCallLog.record(toolContext, "diffVersions", modelKey + " " + fromVersion + "->" + toVersion);
        return client.diffVersions(modelKey, fromVersion, toVersion);
    }

    @Tool(description = "Get one past execution by its ID: the exact inputs, outputs, status, and "
            + "the ordered trace (which decisions and decision-table rows fired, 1-indexed). This "
            + "is how you explain why a specific real decision came out the way it did.")
    public Object getExecution(
            @ToolParam(description = "The execution UUID") String executionId, ToolContext toolContext) {
        ToolCallLog.record(toolContext, "getExecution", executionId);
        return client.getExecution(executionId);
    }

    @Tool(description = "Search past executions by model, version, status, or caller, most recent "
            + "first. Returns up to 'limit' executions (and the total count that matched) so you "
            + "can describe patterns, e.g. how many were DECLINED.")
    public Object queryExecutions(
            @ToolParam(required = false, description = "Filter by model key") String modelKey,
            @ToolParam(required = false, description = "Filter by exact version, e.g. '1.0.0'") String version,
            @ToolParam(required = false, description = "Filter by status: SUCCEEDED or FAILED") String status,
            @ToolParam(required = false, description = "Filter by caller username") String caller,
            @ToolParam(required = false, description = "Max executions to return, default 20, max 200") Integer limit,
            ToolContext toolContext) {
        ToolCallLog.record(toolContext, "queryExecutions", String.valueOf(modelKey));
        int size = limit == null ? 20 : Math.min(limit, 200);
        return client.queryExecutions(
                blankToNull(modelKey), blankToNull(version), blankToNull(status), blankToNull(caller), size);
    }

    @Tool(description = "Estimate how many past executions would flip to a different outcome if "
            + "re-run against a different version. Samples recent executions that actually ran on "
            + "'fromVersion', re-simulates each one's exact original inputs against 'toVersion' "
            + "through the real engine, and reports how many outputs differ. Use this for "
            + "'how many past cases would flip between vX and vY' questions instead of calling "
            + "simulate yourself in a loop.")
    public Object estimateImpact(
            @ToolParam(description = "The model key") String modelKey,
            @ToolParam(description = "The version the sampled executions actually ran on, e.g. '1.0.0'") String fromVersion,
            @ToolParam(description = "The version to re-simulate them against, e.g. '1.1.0'") String toVersion,
            @ToolParam(required = false, description = "How many recent executions to sample, default 15, max 50")
                    Integer sampleSize,
            ToolContext toolContext) {
        ToolCallLog.record(toolContext, "estimateImpact", modelKey + " " + fromVersion + "->" + toVersion);
        int n = sampleSize == null ? 15 : Math.min(sampleSize, 50);
        var page = client.queryExecutions(modelKey, fromVersion, null, null, n);

        int flipped = 0;
        List<Map<String, Object>> examples = new java.util.ArrayList<>();
        for (var summary : page.items()) {
            ExecutionDetail original = client.getExecution(summary.id());
            var resimulated = client.simulate(modelKey, toVersion, original.inputs());
            boolean changed = !java.util.Objects.equals(original.outputs(), resimulated.outputs());
            if (changed) {
                flipped++;
                if (examples.size() < 5) {
                    examples.add(Map.of(
                            "executionId", summary.id(),
                            "before", original.outputs(),
                            "after", resimulated.outputs()));
                }
            }
        }

        return Map.of(
                "modelKey", modelKey, "fromVersion", fromVersion, "toVersion", toVersion,
                "sampled", page.items().size(), "totalMatchingFromVersion", page.total(),
                "flipped", flipped, "examples", examples);
    }

    @Tool(description = "Dry-run a model with hypothetical inputs through the real DMN engine — "
            + "never written to the audit log. Use this to answer 'what if' questions, e.g. 'would "
            + "they be approved with a credit score of 700?'. inputsJson must be a JSON object "
            + "matching the model's input data structure, e.g. for loan-eligibility: "
            + "{\"Applicant\":{\"age\":34,\"creditScore\":700,\"income\":72000,\"employmentYears\":5},"
            + "\"Loan\":{\"amount\":25000,\"termMonths\":48}} — call getModelStructure first if unsure "
            + "of the input shape for a model you haven't seen.")
    public Object simulate(
            @ToolParam(description = "The model key") String modelKey,
            @ToolParam(description = "JSON object of inputs matching the model's input data structure") String inputsJson,
            @ToolParam(required = false, description = "A specific version. Omit for the latest published version.")
                    String version,
            ToolContext toolContext) {
        ToolCallLog.record(toolContext, "simulate", modelKey);
        Map<String, Object> inputs = parseJsonObject(inputsJson);
        return client.simulate(modelKey, blankToNull(version), inputs);
    }

    @Tool(description = "Propose a change to a decision table as a NEW DRAFT ONLY — it is never "
            + "published and never affects live decisions until a human validates, tests, "
            + "approves and publishes it through the normal lifecycle. Use this when the user asks "
            + "you to draft or change a rule, e.g. 'add a rule: applicants under 21 need a "
            + "co-signer'. rulesJson must be a JSON array of the table's COMPLETE new row set "
            + "(not just the changed rows) — call getDecisionTable first to get the current rows "
            + "and edit them, each row shaped as "
            + "{\"id\":null or existing row id,\"description\":\"business reason\","
            + "\"inputEntries\":[\"FEEL unary test per input column\"],\"outputEntry\":\"FEEL literal, e.g. \\\"APPROVED\\\"\"}.")
    public Object proposeChange(
            @ToolParam(description = "The model key") String modelKey,
            @ToolParam(description = "The decision table name to change") String decisionName,
            @ToolParam(description = "Hit policy: UNIQUE, FIRST, PRIORITY, ANY, COLLECT, RULE_ORDER, or OUTPUT_ORDER")
                    String hitPolicy,
            @ToolParam(description = "JSON array of the complete new row set, see tool description for row shape")
                    String rulesJson,
            @ToolParam(description = "One sentence explaining the proposed change") String changeNote,
            ToolContext toolContext) {
        ToolCallLog.record(toolContext, "proposeChange", modelKey + "/" + decisionName);
        List<DecisionTable.Rule> rules = parseRules(rulesJson);
        String actor = actorFrom(toolContext);
        ModelVersion draft = client.proposeChange(modelKey, decisionName, hitPolicy, rules, changeNote, actor);
        return Map.of(
                "status", "DRAFT_CREATED",
                "note", "This is a draft only. It has not been validated, tested, approved, or published.",
                "draft", draft);
    }

    private String actorFrom(ToolContext toolContext) {
        Object username = toolContext == null ? null : toolContext.getContext().get("username");
        return username == null ? "ai-service" : username.toString();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("inputsJson must be valid JSON: " + e.getMessage(), e);
        }
    }

    private List<DecisionTable.Rule> parseRules(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, DecisionTable.Rule.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("rulesJson must be a valid JSON array of rows: " + e.getMessage(), e);
        }
    }
}
