package com.dmnrules.decision.author;

import java.util.List;

/**
 * kind: LITERAL_EXPRESSION | DECISION_TABLE
 * changeType: ADDED | REMOVED | CHANGED | UNCHANGED
 */
public record DecisionDiffEntry(
        String decisionName, String kind, String changeType, String beforeText, String afterText,
        List<RuleDiffEntry> ruleDiffs) {
}
