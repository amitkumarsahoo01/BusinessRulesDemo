package com.dmnrules.decision.author;

/** changeType: ADDED | REMOVED | CHANGED */
public record RuleDiffEntry(String changeType, String ruleId, String before, String after) {
}
