package com.dmnrules.decision.author;

import com.dmnrules.decision.model.ModelNotFoundException;
import com.dmnrules.decision.model.ModelVersionRepository;
import com.dmnrules.decision.model.ModelVersionRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Imports a JSON Schema as DMN data types. A preview never writes; applying goes through the
 * normal draft path ({@link ModelLifecycleService#createOrUpdateDraft}), so a published version is
 * never changed (CLAUDE.md rule 3): the in-progress draft is updated, or a new draft is branched
 * from the latest published version, and the change is audited like any other edit.
 */
@Service
public class DataTypeImportService {

    static final int MAX_SCHEMA_CHARS = 512 * 1024;

    public record ImportRequest(JsonNode schema, String rootName, boolean replaceExisting, boolean createInputs,
            boolean apply, String changeNote) {
    }

    public record ImportResult(String baseVersion, List<ItemDefinitionDto> types, List<String> inputs,
            List<String> skippedInputs, List<String> conflicts, List<String> warnings, boolean applied,
            ModelVersionRow version, List<ValidationMessageDto> messages) {
    }

    private final ModelVersionRepository versionRepository;
    private final ModelLifecycleService lifecycleService;
    private final DmnXmlEditor xmlEditor;
    private final DmnValidationService validationService;
    private final ObjectMapper objectMapper;

    public DataTypeImportService(ModelVersionRepository versionRepository, ModelLifecycleService lifecycleService,
            DmnXmlEditor xmlEditor, DmnValidationService validationService, ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.lifecycleService = lifecycleService;
        this.xmlEditor = xmlEditor;
        this.validationService = validationService;
        this.objectMapper = objectMapper;
    }

    public ImportResult importSchema(String modelKey, ImportRequest request, String actor) {
        JsonNode schema = readSchema(request.schema());
        ModelVersionRow base = versionRepository.findCurrentInProgress(modelKey)
                .or(() -> versionRepository.findLatestPublished(modelKey))
                .orElseThrow(() -> new ModelNotFoundException("Model '" + modelKey + "' not found"));

        JsonSchemaConverter.Result converted = JsonSchemaConverter.convert(schema,
                new JsonSchemaConverter.Options(request.rootName(), request.createInputs()));

        Set<String> existingTypes = lower(xmlEditor.dataTypeNames(base.dmnXml()));
        List<String> conflicts = converted.types().stream().map(ItemDefinitionDto::name)
                .filter(n -> existingTypes.contains(n.toLowerCase(Locale.ROOT))).toList();
        Set<String> existingElements = lower(xmlEditor.drgElementNames(base.dmnXml()));
        List<String> inputs = converted.inputs().stream().map(JsonSchemaConverter.InputSpec::name)
                .filter(n -> !existingElements.contains(n.toLowerCase(Locale.ROOT))).toList();
        List<String> skippedInputs = converted.inputs().stream().map(JsonSchemaConverter.InputSpec::name)
                .filter(n -> existingElements.contains(n.toLowerCase(Locale.ROOT))).toList();

        if (!request.apply()) {
            return new ImportResult(base.version(), converted.types(), inputs, skippedInputs, conflicts,
                    converted.warnings(), false, null, List.of());
        }
        if (!conflicts.isEmpty() && !request.replaceExisting()) {
            throw new IllegalArgumentException("These data types already exist in the model: "
                    + String.join(", ", conflicts) + ". Tick \"Replace existing types\" or rename them in the schema.");
        }
        String newXml = xmlEditor.addDataTypes(base.dmnXml(), converted.types(), converted.inputs(),
                request.replaceExisting());
        String note = request.changeNote() != null && !request.changeNote().isBlank() ? request.changeNote().trim()
                : "Imported data types from JSON Schema: " + converted.types().stream()
                        .map(ItemDefinitionDto::name).collect(Collectors.joining(", "));
        ModelVersionRow saved = lifecycleService.createOrUpdateDraft(modelKey, new UpdateDraftRequest(newXml, null, note),
                actor);
        return new ImportResult(base.version(), converted.types(), inputs, skippedInputs, conflicts,
                converted.warnings(), true, saved, validationService.validate(newXml));
    }

    /** Accepts the schema as a JSON object or as a string holding JSON (e.g. pasted text). */
    private JsonNode readSchema(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            throw new IllegalArgumentException("Paste or upload a JSON Schema.");
        }
        if (raw.isTextual()) {
            String text = raw.asText();
            if (text.length() > MAX_SCHEMA_CHARS) {
                throw new IllegalArgumentException("The schema is larger than 512 KB.");
            }
            try {
                return objectMapper.readTree(text);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("The schema is not valid JSON: " + e.getOriginalMessage());
            }
        }
        if (raw.toString().length() > MAX_SCHEMA_CHARS) {
            throw new IllegalArgumentException("The schema is larger than 512 KB.");
        }
        return raw;
    }

    private static Set<String> lower(List<String> names) {
        return names.stream().filter(n -> n != null).map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
