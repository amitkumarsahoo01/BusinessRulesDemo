package com.dmnrules.decision.author;

import com.dmnrules.decision.audit.RowHashService;
import com.dmnrules.decision.audit.RuleChangeAuditRepository;
import com.dmnrules.decision.model.ModelRow;
import com.dmnrules.decision.model.ModelVersionRepository;
import com.dmnrules.decision.model.ModelVersionRow;
import org.kie.dmn.model.api.Decision;
import org.kie.dmn.model.api.Definitions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns an uploaded {@code .dmn} file into a DRAFT model version. Never publishes: the import
 * enters the normal validate -> test -> approve -> publish lifecycle like any other edit
 * (CLAUDE.md rule 3: published versions are immutable, edits create a new draft).
 */
@Service
public class DmnImportService {

    private static final Pattern MODEL_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");

    private final DmnXmlEditor xmlEditor;
    private final DmnValidationService validationService;
    private final ModelVersionRepository versionRepository;
    private final RuleChangeAuditRepository auditRepository;
    private final RowHashService rowHashService;

    public DmnImportService(
            DmnXmlEditor xmlEditor, DmnValidationService validationService, ModelVersionRepository versionRepository,
            RuleChangeAuditRepository auditRepository, RowHashService rowHashService) {
        this.xmlEditor = xmlEditor;
        this.validationService = validationService;
        this.versionRepository = versionRepository;
        this.auditRepository = auditRepository;
        this.rowHashService = rowHashService;
    }

    @Transactional
    public ImportResultDto importModel(String dmnXml, String requestedModelKey, String fileName, String actor) {
        requireWellFormedWithoutDoctype(dmnXml);
        Definitions definitions = parseOrReject(dmnXml);
        if (definitions.getDrgElement().stream().noneMatch(Decision.class::isInstance)) {
            throw new IllegalArgumentException("The DMN file contains no decisions, so there is nothing to evaluate.");
        }

        String modelKey = resolveModelKey(requestedModelKey, definitions);
        ModelRow existing = versionRepository.findAllModels().stream()
                .filter(m -> m.modelKey().equals(modelKey)).findFirst().orElse(null);

        String version;
        if (existing == null) {
            String name = blankToNull(definitions.getName()) != null ? definitions.getName() : modelKey;
            String namespace = blankToNull(definitions.getNamespace()) != null
                    ? definitions.getNamespace() : "https://dmn-rules-demo/" + modelKey;
            versionRepository.insertModel(modelKey, name, namespace, actor);
            version = "1.0.0";
        } else {
            if (versionRepository.findCurrentInProgress(modelKey).isPresent()) {
                throw new InvalidLifecycleTransitionException(
                        "Model '" + modelKey + "' already has a draft in progress; publish or finish it before importing again.");
            }
            List<ModelVersionRow> all = versionRepository.findAllByModelKey(modelKey);
            version = ModelLifecycleService.incrementPatch(all.get(all.size() - 1).version());
        }

        UUID id = UUID.randomUUID();
        String note = "Imported from " + (fileName == null || fileName.isBlank() ? "uploaded file" : fileName);
        versionRepository.insertVersion(new ModelVersionRow(
                id, modelKey, version, "DRAFT", dmnXml, rowHashService.sha256Hex(dmnXml),
                note, actor, null, null, null));
        auditRepository.record(modelKey, id, "CREATE", actor,
                Map.of("imported", true, "fileName", fileName == null ? "" : fileName, "newModel", existing == null));

        List<ValidationMessageDto> messages = validationService.validate(dmnXml);
        return new ImportResultDto(versionRepository.findById(id).orElseThrow(), existing == null, messages);
    }

    private Definitions parseOrReject(String dmnXml) {
        try {
            Definitions definitions = xmlEditor.parse(dmnXml);
            if (definitions == null) {
                throw new IllegalArgumentException("The file is not a DMN model.");
            }
            return definitions;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The file is not a valid DMN model: " + rootMessage(e), e);
        }
    }

    /** Rejects DOCTYPE/entities up front (XXE and entity-expansion), before any DMN-aware parser sees the text. */
    private static void requireWellFormedWithoutDoctype(String xml) {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "The file is not well-formed XML (DOCTYPE declarations are not allowed): " + rootMessage(e), e);
        }
        String root = doc.getDocumentElement().getLocalName();
        if (!"definitions".equals(root)) {
            throw new IllegalArgumentException(
                    "The file is not a DMN model (root element is '" + root + "', expected 'definitions').");
        }
    }

    private static String resolveModelKey(String requested, Definitions definitions) {
        if (requested != null && !requested.isBlank()) {
            String key = requested.trim();
            if (!MODEL_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "Model key must be 2-63 characters of lowercase letters, digits and hyphens.");
            }
            return key;
        }
        String source = blankToNull(definitions.getName()) != null ? definitions.getName() : definitions.getId();
        String slug = source == null ? "" : source.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > 63) {
            slug = slug.substring(0, 63).replaceAll("-+$", "");
        }
        if (!MODEL_KEY.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "Could not derive a model key from the DMN name; provide one (lowercase letters, digits, hyphens).");
        }
        return slug;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = root.getMessage();
        return m == null ? root.getClass().getSimpleName() : m;
    }
}
