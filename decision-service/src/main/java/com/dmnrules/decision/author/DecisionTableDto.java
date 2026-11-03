package com.dmnrules.decision.author;

import java.util.List;

public record DecisionTableDto(
        String decisionName, String hitPolicy, List<DecisionTableColumnDto> inputs,
        DecisionTableColumnDto output, List<DecisionRuleDto> rules) {
}
