package com.dmnrules.decision.author;

import java.util.List;

/** Full DRG (requirements graph) plus data types, for the UI's structure view. */
public record ModelGraphDto(
        String name, String namespace, List<GraphNodeDto> nodes, List<GraphEdgeDto> edges,
        List<ItemDefinitionDto> itemDefinitions) {
}
