package com.dmnrules.decision.author;

import java.util.List;

public record ItemDefinitionDto(
        String name, String typeRef, boolean collection, String allowedValues, List<ItemDefinitionDto> components) {
}
