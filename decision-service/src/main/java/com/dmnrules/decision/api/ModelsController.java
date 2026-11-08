package com.dmnrules.decision.api;

import com.dmnrules.decision.audit.RuleChangeAuditRepository;
import com.dmnrules.decision.audit.RuleChangeAuditRow;
import com.dmnrules.decision.author.CreateTestCaseRequest;
import com.dmnrules.decision.author.DecisionDiffEntry;
import com.dmnrules.decision.author.DecisionSummaryDto;
import com.dmnrules.decision.author.DataTypeImportService;
import com.dmnrules.decision.author.DecisionTableDto;
import com.dmnrules.decision.author.DmnImportService;
import com.dmnrules.decision.author.DmnValidationService;
import com.dmnrules.decision.author.ImportResultDto;
import com.dmnrules.decision.author.ModelGraphDto;
import com.dmnrules.decision.author.DmnXmlEditor;
import com.dmnrules.decision.author.ModelLifecycleService;
import com.dmnrules.decision.author.ModelStructureDto;
import com.dmnrules.decision.author.ModelSummaryDto;
import com.dmnrules.decision.author.TestCaseRepository;
import com.dmnrules.decision.author.TestCaseRow;
import com.dmnrules.decision.author.TestRunResult;
import com.dmnrules.decision.author.UpdateDraftRequest;
import com.dmnrules.decision.author.ValidationMessageDto;
import com.dmnrules.decision.author.ValidationResult;
import com.dmnrules.decision.model.ModelRow;
import com.dmnrules.decision.model.ModelVersionRepository;
import com.dmnrules.decision.model.ModelVersionRow;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/models")
public class ModelsController {

    private final ModelVersionRepository versionRepository;
    private final RuleChangeAuditRepository auditRepository;
    private final TestCaseRepository testCaseRepository;
    private final ModelLifecycleService lifecycleService;
    private final DmnXmlEditor xmlEditor;
    private final DmnValidationService validationService;
    private final DmnImportService importService;
    private final DataTypeImportService dataTypeImportService;

    public ModelsController(
            ModelVersionRepository versionRepository, RuleChangeAuditRepository auditRepository,
            TestCaseRepository testCaseRepository, ModelLifecycleService lifecycleService, DmnXmlEditor xmlEditor,
            DmnValidationService validationService, DmnImportService importService,
            DataTypeImportService dataTypeImportService) {
        this.dataTypeImportService = dataTypeImportService;
        this.versionRepository = versionRepository;
        this.auditRepository = auditRepository;
        this.testCaseRepository = testCaseRepository;
        this.lifecycleService = lifecycleService;
        this.xmlEditor = xmlEditor;
        this.validationService = validationService;
        this.importService = importService;
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

    /** Resolves a model + optional version string to its version row (latest published if omitted). */
    @GetMapping("/{modelKey}/resolve")
    public ModelVersionRow resolve(@PathVariable String modelKey, @RequestParam(required = false) String version) {
        if (version != null) {
            return versionRepository.findByKeyAndVersion(modelKey, version)
                    .orElseThrow(() -> new com.dmnrules.decision.model.ModelNotFoundException(
                            "Model '" + modelKey + "' has no version '" + version + "'"));
        }
        return versionRepository.findLatestPublished(modelKey)
                .orElseThrow(() -> new com.dmnrules.decision.model.ModelNotFoundException(
                        "Model '" + modelKey + "' has no published version"));
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

    @GetMapping("/{modelKey}/drafts/{id}/structure")
    public ModelStructureDto structure(@PathVariable String modelKey, @PathVariable UUID id) {
        return xmlEditor.readStructure(getVersion(modelKey, id).dmnXml());
    }

    @GetMapping("/{modelKey}/drafts/{id}/graph")
    public ModelGraphDto graph(@PathVariable String modelKey, @PathVariable UUID id) {
        return xmlEditor.readGraph(getVersion(modelKey, id).dmnXml());
    }

    /** Uploads a .dmn file as a new model (or a new draft of an existing one). Never publishes. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResultDto importDmn(
            @RequestParam("file") MultipartFile file, @RequestParam(value = "modelKey", required = false) String modelKey,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String actor) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty.");
        }
        String xml = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (xml.startsWith("﻿")) {
            xml = xml.substring(1);
        }
        return importService.importModel(xml, modelKey, file.getOriginalFilename(), actor);
    }

    /**
     * Converts a JSON Schema to DMN data types. With {@code apply: false} it only previews (types,
     * name conflicts, warnings); with {@code apply: true} it writes them into the draft (never into
     * a published version). Read-only roles are refused by the auth filter like every other POST here.
     */
    @PostMapping("/{modelKey}/data-types/import")
    public DataTypeImportService.ImportResult importDataTypes(
            @PathVariable String modelKey, @RequestBody DataTypeImportService.ImportRequest request,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String actor) {
        return dataTypeImportService.importSchema(modelKey, request, actor);
    }

    @GetMapping("/{modelKey}/drafts/{id}/analysis")
    public List<ValidationMessageDto> analysis(@PathVariable String modelKey, @PathVariable UUID id) {
        return validationService.validate(getVersion(modelKey, id).dmnXml());
    }

    @GetMapping("/{modelKey}/drafts/{id}/decisions")
    public List<DecisionSummaryDto> listDecisions(@PathVariable String modelKey, @PathVariable UUID id) {
        return xmlEditor.listDecisions(getVersion(modelKey, id).dmnXml());
    }

    @GetMapping("/{modelKey}/drafts/{id}/decisions/{decisionName}")
    public DecisionTableDto readDecisionTable(
            @PathVariable String modelKey, @PathVariable UUID id, @PathVariable String decisionName) {
        return xmlEditor.readDecisionTable(getVersion(modelKey, id).dmnXml(), decisionName);
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
