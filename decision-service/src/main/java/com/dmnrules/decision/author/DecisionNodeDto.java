package com.dmnrules.decision.author;

import java.util.List;

/** kind: LITERAL_EXPRESSION | DECISION_TABLE. requires: names of inputs/decisions this one depends on. */
public record DecisionNodeDto(String name, String kind, List<String> requires) {
}
