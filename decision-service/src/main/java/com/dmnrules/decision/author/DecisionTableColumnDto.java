package com.dmnrules.decision.author;

/** One input or output column of a decision table, as shown in the guided grid editor. */
public record DecisionTableColumnDto(String label, String expression, String typeRef) {
}
