package com.dmnrules.decision.author;

import java.util.List;

/** Exactly one of {@code dmnXml} or {@code guidedTableEdit} should be set. */
public record UpdateDraftRequest(String dmnXml, GuidedTableEdit guidedTableEdit, String changeNote) {

    public record GuidedTableEdit(String decisionName, String hitPolicy, List<DecisionRuleDto> rules) {
    }
}
