package com.dmnrules.decision.author;

public record ModelSummaryDto(
        String modelKey, String name, String namespace, String owner,
        String latestPublishedVersion, String inProgressVersion, String inProgressStatus) {
}
