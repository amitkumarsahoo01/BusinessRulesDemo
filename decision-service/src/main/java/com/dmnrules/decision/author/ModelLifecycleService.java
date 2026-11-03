package com.dmnrules.decision.author;

import com.dmnrules.decision.audit.RowHashService;
import com.dmnrules.decision.audit.RuleChangeAuditRepository;
import com.dmnrules.decision.model.ModelNotFoundException;
import com.dmnrules.decision.model.ModelRegistry;
import com.dmnrules.decision.model.ModelVersionRepository;
import com.dmnrules.decision.model.ModelVersionRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the model lifecycle: DRAFT -> VALIDATED -> TESTED -> APPROVED -> PUBLISHED, plus
 * rollback (docs/Architecture.md "Model lifecycle", BUILD_PLAN M3). A "draft" is simply a
 * dmn_model_version row progressing its status column in place until it's published, at which
 * point it becomes immutable (CLAUDE.md rule 3) and the next edit creates a fresh row.
 */
@Service
public class ModelLifecycleService {

    private static final String MODEL_PUBLISHED_CHANNEL = "model_published";

    private final ModelVersionRepository versionRepository;
    private final RuleChangeAuditRepository auditRepository;
    private final TestCaseRepository testCaseRepository;
    private final DmnXmlEditor xmlEditor;
    private final DmnDiffService diffService;
    private final DmnValidationService validationService;
    private final AdHocEvaluator adHocEvaluator;
    private final ModelRegistry modelRegistry;
    private final RowHashService rowHashService;
    private final JdbcTemplate jdbcTemplate;

    public ModelLifecycleService(
            ModelVersionRepository versionRepository, RuleChangeAuditRepository auditRepository,
            TestCaseRepository testCaseRepository, DmnXmlEditor xmlEditor, DmnDiffService diffService,
            DmnValidationService validationService, AdHocEvaluator adHocEvaluator,
            ModelRegistry modelRegistry, RowHashService rowHashService, JdbcTemplate jdbcTemplate) {
        this.versionRepository = versionRepository;
        this.auditRepository = auditRepository;
        this.testCaseRepository = testCaseRepository;
        this.xmlEditor = xmlEditor;
        this.diffService = diffService;
        this.validationService = validationService;
        this.adHocEvaluator = adHocEvaluator;
        this.modelRegistry = modelRegistry;
        this.rowHashService = rowHashService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ModelVersionRow createOrUpdateDraft(String modelKey, UpdateDraftRequest request, String actor) {
        ModelVersionRow current = versionRepository.findCurrentInProgress(modelKey).orElse(null);

        if (current != null) {
            String newXml = applyEdit(current.dmnXml(), request);
            versionRepository.updateDraftContent(
                    current.id(), newXml, rowHashService.sha256Hex(newXml), request.changeNote());
            recordDiffAudit(modelKey, current.id(), "EDIT", actor, current.dmnXml(), newXml);
            return versionRepository.findById(current.id()).orElseThrow();
        }

        ModelVersionRow published = versionRepository.findLatestPublished(modelKey)
                .orElseThrow(() -> new ModelNotFoundException(
                        "Model '" + modelKey + "' has no published version to branch a draft from"));
        String baseXml = published.dmnXml();
        String newXml = applyEdit(baseXml, request);
        String nextVersion = incrementPatch(published.version());

        UUID id = UUID.randomUUID();
        versionRepository.insertVersion(new ModelVersionRow(
                id, modelKey, nextVersion, "DRAFT", newXml, rowHashService.sha256Hex(newXml),
                request.changeNote(), actor, null, null, null));
        recordDiffAudit(modelKey, id, "CREATE", actor, baseXml, newXml);
        return versionRepository.findById(id).orElseThrow();
    }

    public ValidationResult validate(String modelKey, UUID versionId, String actor) {
        ModelVersionRow row = requireVersion(modelKey, versionId);
        requireNotFinal(row);

        List<ValidationMessageDto> messages = validationService.validate(row.dmnXml());
        boolean ok = !validationService.hasErrors(messages);
        if (ok) {
            versionRepository.updateStatus(row.id(), "VALIDATED");
        }
        auditRepository.record(modelKey, row.id(), "VALIDATE", actor,
                Map.of("ok", ok, "messageCount", messages.size()));
        return new ValidationResult(ok, ok ? "VALIDATED" : row.status(), messages);
    }

    public TestRunResult runTests(String modelKey, UUID versionId, String actor) {
        ModelVersionRow row = requireVersion(modelKey, versionId);
        if (!List.of("VALIDATED", "TESTED", "APPROVED").contains(row.status())) {
            throw new InvalidLifecycleTransitionException(
                    "Draft must be validated before running tests (current status: " + row.status() + ")");
        }

        List<TestCaseRow> testCases = testCaseRepository.findByModelKey(modelKey);
        List<TestCaseResultDto> results = testCases.stream()
                .map(tc -> runOne(row, tc))
                .toList();
        boolean allPassed = !results.isEmpty() && results.stream().allMatch(TestCaseResultDto::passed);

        String newStatus = row.status();
        if (allPassed) {
            versionRepository.updateStatus(row.id(), "TESTED");
            newStatus = "TESTED";
        }
        auditRepository.record(modelKey, row.id(), "VALIDATE", actor,
                Map.of("testRun", true, "allPassed", allPassed, "caseCount", results.size()));
        return new TestRunResult(allPassed, newStatus, results);
    }

    private TestCaseResultDto runOne(ModelVersionRow row, TestCaseRow testCase) {
        AdHocEvaluationResult result = safeEvaluate(row.dmnXml(), testCase.inputs());
        boolean passed = testCase.expectedOutputs().entrySet().stream()
                .allMatch(expected -> lenientEquals(result.outputs().get(expected.getKey()), expected.getValue()));
        return new TestCaseResultDto(
                testCase.id(), testCase.name(), passed, result.outputs(), testCase.expectedOutputs(), result.trace());
    }

    private AdHocEvaluationResult safeEvaluate(String dmnXml, Map<String, Object> inputs) {
        try {
            return adHocEvaluator.evaluate(dmnXml, inputs);
        } catch (RuntimeException e) {
            return new AdHocEvaluationResult(Map.of("__error__", String.valueOf(e.getMessage())), "FAILED", List.of());
        }
    }

    @Transactional
    public ModelVersionRow approve(String modelKey, UUID versionId, String approver) {
        ModelVersionRow row = requireVersion(modelKey, versionId);
        if (!"TESTED".equals(row.status())) {
            throw new InvalidLifecycleTransitionException(
                    "Draft must pass all saved tests before approval (current status: " + row.status() + ")");
        }
        if (approver.equals(row.authoredBy())) {
            throw new ApproverMustDifferFromAuthorException(row.authoredBy());
        }
        versionRepository.approve(row.id(), approver);
        auditRepository.record(modelKey, row.id(), "APPROVE", approver, null);
        return versionRepository.findById(row.id()).orElseThrow();
    }

    @Transactional
    public ModelVersionRow publish(String modelKey, UUID versionId, String actor) {
        ModelVersionRow row = requireVersion(modelKey, versionId);
        if (!"APPROVED".equals(row.status())) {
            throw new InvalidLifecycleTransitionException(
                    "Draft must be approved before publishing (current status: " + row.status() + ")");
        }
        versionRepository.publish(row.id(), OffsetDateTime.now(ZoneOffset.UTC));
        auditRepository.record(modelKey, row.id(), "PUBLISH", actor, null);

        ModelVersionRow published = versionRepository.findById(row.id()).orElseThrow();
        modelRegistry.reload();
        notifyPublished(modelKey, row.version());
        return published;
    }

    @Transactional
    public ModelVersionRow rollback(String modelKey, String toVersion, String actor) {
        ModelVersionRow target = versionRepository.findByKeyAndVersion(modelKey, toVersion)
                .orElseThrow(() -> new ModelNotFoundException(
                        "Model '" + modelKey + "' has no version '" + toVersion + "'"));

        String currentLatest = versionRepository.findAllByModelKey(modelKey).stream()
                .map(ModelVersionRow::version)
                .max(ModelRegistry::compareVersions)
                .orElse(target.version());
        String nextVersion = incrementPatch(currentLatest);

        UUID id = UUID.randomUUID();
        versionRepository.insertVersion(new ModelVersionRow(
                id, modelKey, nextVersion, "PUBLISHED", target.dmnXml(), target.xmlSha256(),
                "Rollback to " + toVersion, actor, actor, null, OffsetDateTime.now(ZoneOffset.UTC)));
        auditRepository.record(modelKey, id, "ROLLBACK", actor,
                Map.of("rolledBackTo", toVersion, "newVersion", nextVersion));

        ModelVersionRow rolledBack = versionRepository.findById(id).orElseThrow();
        modelRegistry.reload();
        notifyPublished(modelKey, nextVersion);
        return rolledBack;
    }

    public List<DecisionDiffEntry> diff(String modelKey, String fromVersion, String toVersion) {
        ModelVersionRow from = versionRepository.findByKeyAndVersion(modelKey, fromVersion)
                .orElseThrow(() -> new ModelNotFoundException("Model '" + modelKey + "' has no version '" + fromVersion + "'"));
        ModelVersionRow to = versionRepository.findByKeyAndVersion(modelKey, toVersion)
                .orElseThrow(() -> new ModelNotFoundException("Model '" + modelKey + "' has no version '" + toVersion + "'"));
        return diffService.diff(from.dmnXml(), to.dmnXml());
    }

    private void notifyPublished(String modelKey, String version) {
        // pg_notify(...) is a SELECT (it returns a row), so this must run as a query, not an
        // update: JdbcTemplate.update() calls PreparedStatement.executeUpdate(), which Postgres
        // rejects for any statement that returns a ResultSet ("A result was returned when none
        // was expected"). Confirmed against the real driver, not assumed.
        jdbcTemplate.queryForList("SELECT pg_notify(?, ?)", MODEL_PUBLISHED_CHANNEL, modelKey + ":" + version);
    }

    private String applyEdit(String baseXml, UpdateDraftRequest request) {
        if (request.dmnXml() != null) {
            return request.dmnXml();
        }
        if (request.guidedTableEdit() != null) {
            UpdateDraftRequest.GuidedTableEdit edit = request.guidedTableEdit();
            return xmlEditor.replaceDecisionTableRows(baseXml, edit.decisionName(), edit.hitPolicy(), edit.rules());
        }
        throw new IllegalArgumentException("Either dmnXml or guidedTableEdit must be provided");
    }

    private void recordDiffAudit(String modelKey, UUID versionId, String action, String actor,
            String beforeXml, String afterXml) {
        List<DecisionDiffEntry> changed = diffService.diff(beforeXml, afterXml).stream()
                .filter(e -> !"UNCHANGED".equals(e.changeType()))
                .toList();
        auditRepository.record(modelKey, versionId, action, actor, changed);
    }

    private ModelVersionRow requireVersion(String modelKey, UUID versionId) {
        ModelVersionRow row = versionRepository.findById(versionId)
                .orElseThrow(() -> new ModelNotFoundException("Version '" + versionId + "' not found"));
        if (!row.modelKey().equals(modelKey)) {
            throw new ModelNotFoundException("Version '" + versionId + "' does not belong to model '" + modelKey + "'");
        }
        return row;
    }

    private void requireNotFinal(ModelVersionRow row) {
        if ("PUBLISHED".equals(row.status()) || "RETIRED".equals(row.status())) {
            throw new InvalidLifecycleTransitionException(
                    "Version " + row.version() + " is " + row.status() + " and can no longer be edited");
        }
    }

    private static String incrementPatch(String version) {
        String[] parts = version.split("\\.");
        int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return major + "." + minor + "." + (patch + 1);
    }

    @SuppressWarnings("unchecked")
    private static boolean lenientEquals(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual instanceof Number && expected instanceof Number) {
            return new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString())) == 0;
        }
        if (actual instanceof Map && expected instanceof Map) {
            Map<Object, Object> actualMap = (Map<Object, Object>) actual;
            Map<Object, Object> expectedMap = (Map<Object, Object>) expected;
            if (!actualMap.keySet().equals(expectedMap.keySet())) {
                return false;
            }
            return actualMap.keySet().stream()
                    .allMatch(key -> lenientEquals(actualMap.get(key), expectedMap.get(key)));
        }
        if (actual instanceof List && expected instanceof List) {
            List<Object> actualList = (List<Object>) actual;
            List<Object> expectedList = (List<Object>) expected;
            if (actualList.size() != expectedList.size()) {
                return false;
            }
            for (int i = 0; i < actualList.size(); i++) {
                if (!lenientEquals(actualList.get(i), expectedList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return actual.equals(expected);
    }
}
