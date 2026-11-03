package com.dmnrules.decision.author;

import java.util.List;

public record ModelStructureDto(List<InputSummaryDto> inputs, List<DecisionNodeDto> decisions) {
}
