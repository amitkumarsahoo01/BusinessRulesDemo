package com.dmnrules.ai.decision;

public record ModelSummary(
        String modelKey, String name, String namespace, String owner,
        String latestPublishedVersion, String inProgressVersion, String inProgressStatus) {
}
