package com.dmnrules.ai.decision;

import java.util.List;

public record DecisionDiff(
        String decisionName, String kind, String changeType, String beforeText, String afterText,
        List<RuleDiff> ruleDiffs) {

    public record RuleDiff(String changeType, String ruleId, String before, String after) {
    }
}
