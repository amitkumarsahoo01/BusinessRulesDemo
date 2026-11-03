package com.dmnrules.ai.api;

public record StartConversationRequest(String pinnedModelKey, String pinnedVersion, String pinnedExecutionId) {
}
