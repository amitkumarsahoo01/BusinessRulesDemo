package com.dmnrules.decision.author;

import java.util.List;

/** One row of a decision table. {@code id} is preserved across edits so diffs can match rows. */
public record DecisionRuleDto(String id, String description, List<String> inputEntries, String outputEntry) {
}
