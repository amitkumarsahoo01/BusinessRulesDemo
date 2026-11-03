package com.dmnrules.ai.decision;

import java.util.List;

public record DecisionTable(
        String decisionName, String hitPolicy, List<Column> inputs, Column output, List<Rule> rules) {

    public record Column(String label, String expression, String typeRef) {
    }

    public record Rule(String id, String description, List<String> inputEntries, String outputEntry) {
    }
}
