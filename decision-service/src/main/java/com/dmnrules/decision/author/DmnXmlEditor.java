package com.dmnrules.decision.author;

import org.kie.dmn.api.marshalling.DMNMarshaller;
import org.kie.dmn.backend.marshalling.v1x.DMNMarshallerFactory;
import org.kie.dmn.model.api.Decision;
import org.kie.dmn.model.api.DecisionRule;
import org.kie.dmn.model.api.DecisionTable;
import org.kie.dmn.model.api.Definitions;
import org.kie.dmn.model.api.HitPolicy;
import org.kie.dmn.model.api.InputClause;
import org.kie.dmn.model.api.LiteralExpression;
import org.kie.dmn.model.api.OutputClause;
import org.kie.dmn.model.api.UnaryTests;
import org.kie.dmn.model.v1_6.TDecisionRule;
import org.kie.dmn.model.v1_6.TLiteralExpression;
import org.kie.dmn.model.v1_6.TUnaryTests;
import org.springframework.stereotype.Component;

import javax.xml.namespace.QName;
import java.util.List;
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
