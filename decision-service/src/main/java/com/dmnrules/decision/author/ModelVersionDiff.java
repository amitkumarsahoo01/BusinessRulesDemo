package com.dmnrules.decision.author;

import java.util.List;

public record ModelVersionDiff(String modelKey, String fromVersion, String toVersion, List<DecisionDiffEntry> decisions) {
}
