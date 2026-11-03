package com.dmnrules.decision.author;

import org.kie.dmn.model.api.Decision;
import org.kie.dmn.model.api.DecisionRule;
import org.kie.dmn.model.api.DecisionTable;
import org.kie.dmn.model.api.Definitions;
import org.kie.dmn.model.api.Expression;
import org.kie.dmn.model.api.LiteralExpression;
import org.kie.dmn.model.api.UnaryTests;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural diff of two model versions' decisions, at the row/expression granularity. */
@Component
public class DmnDiffService {

    private final DmnXmlEditor editor;

    public DmnDiffService(DmnXmlEditor editor) {
        this.editor = editor;
    }

    public List<DecisionDiffEntry> diff(String fromXml, String toXml) {
        Definitions from = editor.parse(fromXml);
        Definitions to = editor.parse(toXml);

        Map<String, Decision> fromDecisions = indexByName(from);
        Map<String, Decision> toDecisions = indexByName(to);

        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(fromDecisions.keySet());
        allNames.addAll(toDecisions.keySet());

        List<DecisionDiffEntry> entries = new ArrayList<>();
        for (String name : allNames) {
            Decision before = fromDecisions.get(name);
            Decision after = toDecisions.get(name);
            if (before == null) {
                entries.add(new DecisionDiffEntry(name, kindOf(after), "ADDED", null, textOf(after), List.of()));
            } else if (after == null) {
                entries.add(new DecisionDiffEntry(name, kindOf(before), "REMOVED", textOf(before), null, List.of()));
            } else {
                entries.add(diffExisting(name, before, after));
            }
        }
        return entries;
    }

    private DecisionDiffEntry diffExisting(String name, Decision before, Decision after) {
        Expression beforeExpr = before.getExpression();
        Expression afterExpr = after.getExpression();

        if (beforeExpr instanceof DecisionTable beforeTable && afterExpr instanceof DecisionTable afterTable) {
            List<RuleDiffEntry> ruleDiffs = diffRules(beforeTable, afterTable);
            String changeType = ruleDiffs.isEmpty()
                    && beforeTable.getHitPolicy() == afterTable.getHitPolicy() ? "UNCHANGED" : "CHANGED";
            return new DecisionDiffEntry(name, "DECISION_TABLE", changeType, null, null, ruleDiffs);
        }

        String beforeText = textOf(before);
        String afterText = textOf(after);
        String changeType = java.util.Objects.equals(beforeText, afterText) ? "UNCHANGED" : "CHANGED";
        return new DecisionDiffEntry(name, kindOf(after), changeType, beforeText, afterText, List.of());
    }

    private List<RuleDiffEntry> diffRules(DecisionTable before, DecisionTable after) {
        Map<String, DecisionRule> beforeById = new LinkedHashMap<>();
        for (DecisionRule rule : before.getRule()) {
            beforeById.put(rule.getId(), rule);
        }
        Map<String, DecisionRule> afterById = new LinkedHashMap<>();
        for (DecisionRule rule : after.getRule()) {
            afterById.put(rule.getId(), rule);
        }

        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(beforeById.keySet());
        allIds.addAll(afterById.keySet());

        List<RuleDiffEntry> diffs = new ArrayList<>();
        for (String id : allIds) {
            DecisionRule beforeRule = beforeById.get(id);
            DecisionRule afterRule = afterById.get(id);
            if (beforeRule == null) {
                diffs.add(new RuleDiffEntry("ADDED", id, null, ruleText(afterRule)));
            } else if (afterRule == null) {
                diffs.add(new RuleDiffEntry("REMOVED", id, ruleText(beforeRule), null));
            } else {
                String beforeText = ruleText(beforeRule);
                String afterText = ruleText(afterRule);
                if (!beforeText.equals(afterText)) {
                    diffs.add(new RuleDiffEntry("CHANGED", id, beforeText, afterText));
                }
            }
        }
        return diffs;
    }

    private String ruleText(DecisionRule rule) {
        String inputs = rule.getInputEntry().stream().map(UnaryTests::getText)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String output = rule.getOutputEntry().stream().findFirst().map(LiteralExpression::getText).orElse("");
        return inputs + " => " + output;
    }

    private Map<String, Decision> indexByName(Definitions definitions) {
        Map<String, Decision> byName = new LinkedHashMap<>();
        for (var element : definitions.getDrgElement()) {
            if (element instanceof Decision decision) {
                byName.put(decision.getName(), decision);
            }
        }
        return byName;
    }

    private String kindOf(Decision decision) {
        return decision.getExpression() instanceof DecisionTable ? "DECISION_TABLE" : "LITERAL_EXPRESSION";
    }

    private String textOf(Decision decision) {
        Expression expr = decision.getExpression();
        if (expr instanceof LiteralExpression literal) {
            return literal.getText();
        }
        return null;
    }
}
