package com.dmnrules.ai.decision;

import java.util.List;

public record ModelStructure(List<Input> inputs, List<DecisionNode> decisions) {
    public record Input(String name, String typeRef) {
    }

    public record DecisionNode(String name, String kind, List<String> requires) {
    }
}
