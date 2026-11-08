package com.dmnrules.decision.author;

import org.kie.dmn.api.marshalling.DMNMarshaller;
import org.kie.dmn.backend.marshalling.v1x.DMNMarshallerFactory;
import org.kie.dmn.model.api.AuthorityRequirement;
import org.kie.dmn.model.api.BusinessKnowledgeModel;
import org.kie.dmn.model.api.DMNElementReference;
import org.kie.dmn.model.api.ItemDefinition;
import org.kie.dmn.model.api.KnowledgeSource;
import org.kie.dmn.model.api.Decision;
import org.kie.dmn.model.api.DecisionRule;
import org.kie.dmn.model.api.DecisionTable;
import org.kie.dmn.model.api.Definitions;
import org.kie.dmn.model.api.HitPolicy;
import org.kie.dmn.model.api.InputClause;
import org.kie.dmn.model.api.InputData;
import org.kie.dmn.model.api.LiteralExpression;
import org.kie.dmn.model.api.OutputClause;
import org.kie.dmn.model.api.UnaryTests;
import org.kie.dmn.model.v1_6.TDecisionRule;
import org.kie.dmn.model.v1_6.TLiteralExpression;
import org.kie.dmn.model.v1_6.TUnaryTests;
import org.springframework.stereotype.Component;

import javax.xml.namespace.QName;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and edits DMN decision tables through the real KIE DMN object model (not string/DOM
 * manipulation), so every other part of the model (item definitions, information requirements,
 * other decisions) survives an edit untouched. Backs the guided table editor's
 * "DMN XML <-> guided-table JSON" round trip (BUILD_PLAN M3).
 */
@Component
public class DmnXmlEditor {

    private final DMNMarshaller marshaller = DMNMarshallerFactory.newDefaultMarshaller();

    public Definitions parse(String dmnXml) {
        return marshaller.unmarshal(dmnXml);
    }

    public String write(Definitions definitions) {
        return marshaller.marshal(definitions);
    }

    public List<DecisionSummaryDto> listDecisions(String dmnXml) {
        Definitions definitions = parse(dmnXml);
        return definitions.getDrgElement().stream()
                .filter(Decision.class::isInstance)
                .map(Decision.class::cast)
                .map(d -> new DecisionSummaryDto(
                        d.getName(), d.getExpression() instanceof DecisionTable ? "DECISION_TABLE" : "LITERAL_EXPRESSION"))
                .toList();
    }

    /** Compact DRG summary for the AI's getModelStructure tool: inputs, decisions, and dependencies. */
    public ModelStructureDto readStructure(String dmnXml) {
        Definitions definitions = parse(dmnXml);
        Map<String, String> idToName = new HashMap<>();
        for (var element : definitions.getDrgElement()) {
            idToName.put(element.getId(), element.getName());
        }

        List<InputSummaryDto> inputs = definitions.getDrgElement().stream()
                .filter(InputData.class::isInstance)
                .map(InputData.class::cast)
                .map(i -> new InputSummaryDto(
                        i.getName(), typeRefText(i.getVariable() == null ? null : i.getVariable().getTypeRef())))
                .toList();

        List<DecisionNodeDto> decisions = definitions.getDrgElement().stream()
                .filter(Decision.class::isInstance)
                .map(Decision.class::cast)
                .map(d -> new DecisionNodeDto(
                        d.getName(),
                        d.getExpression() instanceof DecisionTable ? "DECISION_TABLE" : "LITERAL_EXPRESSION",
                        d.getInformationRequirement().stream()
                                .map(ir -> {
                                    DMNElementReference ref = ir.getRequiredInput() != null
                                            ? ir.getRequiredInput() : ir.getRequiredDecision();
                                    return ref == null ? null : idToName.get(ref.getHref().replace("#", ""));
                                })
                                .filter(Objects::nonNull)
                                .toList()))
                .toList();

        return new ModelStructureDto(inputs, decisions);
    }

    /** Whole DRG for the structure view: every element, how they depend on each other, and the data types. */
    public ModelGraphDto readGraph(String dmnXml) {
        Definitions definitions = parse(dmnXml);
        List<GraphNodeDto> nodes = new java.util.ArrayList<>();
        List<GraphEdgeDto> edges = new java.util.ArrayList<>();

        for (var element : definitions.getDrgElement()) {
            String id = element.getId();
            if (element instanceof InputData input) {
                nodes.add(new GraphNodeDto(id, input.getName(), "INPUT_DATA",
                        typeRefText(input.getVariable() == null ? null : input.getVariable().getTypeRef()),
                        null, null, null, null, input.getDescription()));
            } else if (element instanceof Decision decision) {
                nodes.add(decisionNode(decision));
                for (var ir : decision.getInformationRequirement()) {
                    addEdge(edges, ir.getRequiredInput(), id, "INFORMATION");
                    addEdge(edges, ir.getRequiredDecision(), id, "INFORMATION");
                }
                for (var kr : decision.getKnowledgeRequirement()) {
                    addEdge(edges, kr.getRequiredKnowledge(), id, "KNOWLEDGE");
                }
                for (var ar : decision.getAuthorityRequirement()) {
                    addAuthorityEdges(edges, ar, id);
                }
            } else if (element instanceof BusinessKnowledgeModel bkm) {
                nodes.add(new GraphNodeDto(id, bkm.getName(), "BKM",
                        typeRefText(bkm.getVariable() == null ? null : bkm.getVariable().getTypeRef()),
                        "FUNCTION", null, null, null, bkm.getDescription()));
                for (var kr : bkm.getKnowledgeRequirement()) {
                    addEdge(edges, kr.getRequiredKnowledge(), id, "KNOWLEDGE");
                }
                for (var ar : bkm.getAuthorityRequirement()) {
                    addAuthorityEdges(edges, ar, id);
                }
            } else if (element instanceof KnowledgeSource source) {
                nodes.add(new GraphNodeDto(id, source.getName(), "KNOWLEDGE_SOURCE", null,
                        null, null, null, null, source.getDescription()));
                for (var ar : source.getAuthorityRequirement()) {
                    addAuthorityEdges(edges, ar, id);
                }
            }
        }

        // Drop edges to elements the file references but does not define (e.g. imported models).
        java.util.Set<String> known = new java.util.HashSet<>();
        nodes.forEach(n -> known.add(n.id()));
        List<GraphEdgeDto> resolved = edges.stream()
                .filter(e -> known.contains(e.from()) && known.contains(e.to()))
                .toList();

        List<ItemDefinitionDto> itemDefinitions = definitions.getItemDefinition().stream()
                .map(this::toItemDefinitionDto)
                .toList();
        return new ModelGraphDto(definitions.getName(), definitions.getNamespace(), nodes, resolved, itemDefinitions);
    }

    private GraphNodeDto decisionNode(Decision decision) {
        var expression = decision.getExpression();
        String typeRef = typeRefText(decision.getVariable() == null ? null : decision.getVariable().getTypeRef());
        if (expression instanceof DecisionTable table) {
            return new GraphNodeDto(decision.getId(), decision.getName(), "DECISION", typeRef, "DECISION_TABLE",
                    table.getHitPolicy().value(), table.getRule().size(), null, decision.getDescription());
        }
        if (expression instanceof LiteralExpression literal) {
            return new GraphNodeDto(decision.getId(), decision.getName(), "DECISION", typeRef, "LITERAL_EXPRESSION",
                    null, null, literal.getText(), decision.getDescription());
        }
        return new GraphNodeDto(decision.getId(), decision.getName(), "DECISION", typeRef, "OTHER",
                null, null, null, decision.getDescription());
    }

    private static void addEdge(List<GraphEdgeDto> edges, DMNElementReference ref, String to, String kind) {
        if (ref != null && ref.getHref() != null) {
            String href = ref.getHref();
            edges.add(new GraphEdgeDto(href.substring(href.lastIndexOf('#') + 1), to, kind));
        }
    }

    private static void addAuthorityEdges(List<GraphEdgeDto> edges, AuthorityRequirement ar, String to) {
        addEdge(edges, ar.getRequiredAuthority(), to, "AUTHORITY");
        addEdge(edges, ar.getRequiredInput(), to, "AUTHORITY");
        addEdge(edges, ar.getRequiredDecision(), to, "AUTHORITY");
    }

    private ItemDefinitionDto toItemDefinitionDto(ItemDefinition item) {
        UnaryTests allowed = item.getAllowedValues();
        return new ItemDefinitionDto(
                item.getName(), typeRefText(item.getTypeRef()), item.isIsCollection(),
                allowed == null ? null : allowed.getText(),
                item.getItemComponent().stream().map(this::toItemDefinitionDto).toList());
    }

    public DecisionTableDto readDecisionTable(String dmnXml, String decisionName) {
        Definitions definitions = parse(dmnXml);
        DecisionTable table = findDecisionTable(definitions, decisionName);

        List<DecisionTableColumnDto> inputs = table.getInput().stream()
                .map(this::toColumnDto)
                .toList();
        DecisionTableColumnDto output = table.getOutput().stream()
                .findFirst()
                .map(o -> new DecisionTableColumnDto(o.getLabel(), o.getName(), typeRefText(o.getTypeRef())))
                .orElse(null);
        List<DecisionRuleDto> rules = table.getRule().stream()
                .map(this::toRuleDto)
                .toList();

        return new DecisionTableDto(decisionName, table.getHitPolicy().value(), inputs, output, rules);
    }

    /** Replaces a decision table's hit policy and rows in place; everything else is untouched. */
    public String replaceDecisionTableRows(String dmnXml, String decisionName, String hitPolicy,
            List<DecisionRuleDto> newRules) {
        Definitions definitions = parse(dmnXml);
        DecisionTable table = findDecisionTable(definitions, decisionName);

        table.setHitPolicy(HitPolicy.fromValue(hitPolicy));
        table.getRule().clear();
        for (DecisionRuleDto ruleDto : newRules) {
            table.getRule().add(toModelRule(ruleDto));
        }
        return write(definitions);
    }

    /** Names of the model's top-level data types. */
    public List<String> dataTypeNames(String dmnXml) {
        return parse(dmnXml).getItemDefinition().stream().map(ItemDefinition::getName).toList();
    }

    /** Names of the model's inputs, decisions, BKMs and knowledge sources. */
    public List<String> drgElementNames(String dmnXml) {
        return parse(dmnXml).getDrgElement().stream().map(e -> e.getName()).toList();
    }

    /**
     * Adds data types (and optionally input data nodes) to a model; everything else is untouched.
     * A type whose name already exists is replaced in place when {@code replaceExisting}, otherwise
     * the call is refused. Inputs whose name is already used by another element are skipped.
     */
    public String addDataTypes(String dmnXml, List<ItemDefinitionDto> types, List<JsonSchemaConverter.InputSpec> inputs,
            boolean replaceExisting) {
        Definitions definitions = parse(dmnXml);
        List<ItemDefinition> existing = definitions.getItemDefinition();
        for (ItemDefinitionDto type : types) {
            ItemDefinition item = toItemDefinition(type);
            int index = indexOfName(existing, type.name());
            if (index < 0) {
                existing.add(item);
            } else if (replaceExisting) {
                item.setId(existing.get(index).getId());
                existing.set(index, item);
            } else {
                throw new IllegalArgumentException("A data type named '" + type.name()
                        + "' already exists. Choose \"Replace existing types\" or rename the schema type.");
            }
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        definitions.getDrgElement().forEach(e -> taken.add(e.getName().toLowerCase(java.util.Locale.ROOT)));
        for (JsonSchemaConverter.InputSpec input : inputs) {
            if (!taken.add(input.name().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            org.kie.dmn.model.v1_6.TInputData node = new org.kie.dmn.model.v1_6.TInputData();
            node.setId("_" + UUID.randomUUID());
            node.setName(input.name());
            org.kie.dmn.model.v1_6.TInformationItem variable = new org.kie.dmn.model.v1_6.TInformationItem();
            variable.setId("_" + UUID.randomUUID());
            variable.setName(input.name());
            variable.setTypeRef(new QName(input.typeRef()));
            node.setVariable(variable);
            definitions.getDrgElement().add(node);
        }
        return write(definitions);
    }

    private static int indexOfName(List<ItemDefinition> items, String name) {
        for (int i = 0; i < items.size(); i++) {
            if (name.equalsIgnoreCase(items.get(i).getName())) {
                return i;
            }
        }
        return -1;
    }

    private ItemDefinition toItemDefinition(ItemDefinitionDto dto) {
        org.kie.dmn.model.v1_6.TItemDefinition item = new org.kie.dmn.model.v1_6.TItemDefinition();
        item.setId("_" + UUID.randomUUID());
        item.setName(dto.name());
        item.setIsCollection(dto.collection());
        if (dto.typeRef() != null) {
            item.setTypeRef(new QName(dto.typeRef()));
        }
        if (dto.allowedValues() != null) {
            TUnaryTests allowed = new TUnaryTests();
            allowed.setText(dto.allowedValues());
            item.setAllowedValues(allowed);
        }
        for (ItemDefinitionDto component : dto.components()) {
            item.getItemComponent().add(toItemDefinition(component));
        }
        return item;
    }

    private DecisionTableColumnDto toColumnDto(InputClause input) {
        LiteralExpression expr = input.getInputExpression();
        return new DecisionTableColumnDto(
                input.getLabel(), expr == null ? null : expr.getText(), typeRefText(expr == null ? null : expr.getTypeRef()));
    }

    private DecisionRuleDto toRuleDto(DecisionRule rule) {
        List<String> inputEntries = rule.getInputEntry().stream().map(UnaryTests::getText).toList();
        String outputEntry = rule.getOutputEntry().stream().findFirst().map(LiteralExpression::getText).orElse(null);
        return new DecisionRuleDto(rule.getId(), rule.getDescription(), inputEntries, outputEntry);
    }

    private DecisionRule toModelRule(DecisionRuleDto dto) {
        TDecisionRule rule = new TDecisionRule();
        rule.setId(dto.id() != null ? dto.id() : "rule_" + UUID.randomUUID());
        rule.setDescription(dto.description());
        for (String entry : dto.inputEntries()) {
            TUnaryTests unaryTests = new TUnaryTests();
            unaryTests.setText(entry);
            rule.getInputEntry().add(unaryTests);
        }
        TLiteralExpression outputEntry = new TLiteralExpression();
        outputEntry.setText(dto.outputEntry());
        rule.getOutputEntry().add(outputEntry);
        return rule;
    }

    private DecisionTable findDecisionTable(Definitions definitions, String decisionName) {
        Decision decision = findDecision(definitions, decisionName);
        if (!(decision.getExpression() instanceof DecisionTable table)) {
            throw new UnknownDecisionException(decisionName + "' is not a decision table");
        }
        return table;
    }

    private Decision findDecision(Definitions definitions, String decisionName) {
        Optional<Decision> decision = definitions.getDrgElement().stream()
                .filter(Decision.class::isInstance)
                .map(Decision.class::cast)
                .filter(d -> decisionName.equals(d.getName()))
                .findFirst();
        return decision.orElseThrow(() -> new UnknownDecisionException(decisionName));
    }

    private static String typeRefText(QName typeRef) {
        return typeRef == null ? null : typeRef.getLocalPart();
    }
}
