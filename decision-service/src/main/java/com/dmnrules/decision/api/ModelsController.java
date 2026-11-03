package com.dmnrules.decision.api;

import com.dmnrules.decision.audit.RuleChangeAuditRepository;
import com.dmnrules.decision.audit.RuleChangeAuditRow;
import com.dmnrules.decision.author.CreateTestCaseRequest;
import com.dmnrules.decision.author.DecisionDiffEntry;
import com.dmnrules.decision.author.ModelLifecycleService;
import com.dmnrules.decision.author.ModelSummaryDto;
import com.dmnrules.decision.author.TestCaseRepository;
import com.dmnrules.decision.author.TestCaseRow;
import com.dmnrules.decision.author.TestRunResult;
import com.dmnrules.decision.author.UpdateDraftRequest;
import com.dmnrules.decision.author.ValidationResult;
import com.dmnrules.decision.model.ModelRow;
import com.dmnrules.decision.model.ModelVersionRepository;
import com.dmnrules.decision.model.ModelVersionRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/models")
public class ModelsController {

    private final ModelVersionRepository versionRepository;
    private final RuleChangeAuditRepository auditRepository;
    private final TestCaseRepository testCaseRepository;
    private final ModelLifecycleService lifecycleService;

    public ModelsController(
            ModelVersionRepository versionRepository, RuleChangeAuditRepository auditRepository,
            TestCaseRepository testCaseRepository, ModelLifecycleService lifecycleService) {
        this.versionRepository = versionRepository;
        this.auditRepository = auditRepository;
        this.testCaseRepository = testCaseRepository;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    public List<ModelSummaryDto> listModels() {
        return versionRepository.findAllModels().stream().map(this::toSummary).toList();
    }

    private ModelSummaryDto toSummary(ModelRow model) {
        String latestPublished = versionRepository.findLatestPublished(model.modelKey())
                .map(ModelVersionRow::version).orElse(null);
        ModelVersionRow inProgress = versionRepository.findCurrentInProgress(model.modelKey()).orElse(null);
        return new ModelSummaryDto(
                model.modelKey(), model.name(), model.namespace(), model.owner(), latestPublished,
                inProgress == null ? null : inProgress.version(),
                inProgress == null ? null : inProgress.status());
    }

    @GetMapping("/{modelKey}/versions")
    public List<ModelVersionRow> versions(@PathVariable String modelKey) {
        return versionRepository.findAllByModelKey(modelKey);
    }

    @GetMapping("/{modelKey}/versions/{v1}/diff/{v2}")
    public List<DecisionDiffEntry> diff(@PathVariable String modelKey, @PathVariable String v1, @PathVariable String v2) {
        return lifecycleService.diff(modelKey, v1, v2);
    }

    @GetMapping("/{modelKey}/history")
    public List<RuleChangeAuditRow> history(@PathVariable String modelKey) {
        return auditRepository.findByModelKey(modelKey);
    }

    @PostMapping("/{modelKey}/drafts")
    public ModelVersionRow createOrUpdateDraft(
            @PathVariable String modelKey, @RequestBody UpdateDraftRequest request,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String actor) {
        return lifecycleService.createOrUpdateDraft(modelKey, request, actor);
    }

    @GetMapping("/{modelKey}/drafts/{id}")
    public ModelVersionRow getVersion(@PathVariable String modelKey, @PathVariable UUID id) {
        return versionRepository.findById(id)
                .filter(v -> v.modelKey().equals(modelKey))
                .orElseThrow(() -> new com.dmnrules.decision.model.ModelNotFoundException(
                        "Version '" + id + "' not found for model '" + modelKey + "'"));
    }

    @PostMapping("/{modelKey}/drafts/{id}/validate")
    public ValidationResult validate(
            @PathVariable String modelKey, @PathVariable UUID id,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String actor) {
        return lifecycleService.validate(modelKey, id, actor);
    }

    @PostMapping("/{modelKey}/drafts/{id}/tests/run")
    public TestRunResult runTests(
            @PathVariable String modelKey, @PathVariable UUID id,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String actor) {
        return lifecycleService.runTests(modelKey, id, actor);
    }

    @PostMapping("/{modelKey}/drafts/{id}/approve")
    public ModelVersionRow approve(
            @PathVariable String modelKey, @PathVariable UUID id,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String approver) {
        return lifecycleService.approve(modelKey, id, approver);
    }

    @PostMapping("/{modelKey}/drafts/{id}/publish")
    public ModelVersionRow publish(
            @PathVariable String modelKey, @PathVariable UUID id,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String actor) {
        return lifecycleService.publish(modelKey, id, actor);
    }

    @PostMapping("/{modelKey}/rollback")
    public ModelVersionRow rollback(
            @PathVariable String modelKey, @RequestParam String toVersion,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String actor) {
        return lifecycleService.rollback(modelKey, toVersion, actor);
    }

    @PostMapping("/{modelKey}/test-cases")
    public TestCaseRow createTestCase(
            @PathVariable String modelKey, @RequestBody CreateTestCaseRequest request,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String actor) {
        UUID id = testCaseRepository.insert(modelKey, request.name(), request.inputs(), request.expectedOutputs(), actor);
        return testCaseRepository.findByModelKey(modelKey).stream()
                .filter(tc -> tc.id().equals(id)).findFirst().orElseThrow();
    }

    @GetMapping("/{modelKey}/test-cases")
    public List<TestCaseRow> testCases(@PathVariable String modelKey) {
        return testCaseRepository.findByModelKey(modelKey);
    }
}
