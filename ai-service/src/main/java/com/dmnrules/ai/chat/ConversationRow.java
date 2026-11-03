package com.dmnrules.ai.chat;

import java.time.Instant;
import java.util.UUID;

public record ConversationRow(
        UUID id, String createdBy, String pinnedModelKey, String pinnedVersion, UUID pinnedExecutionId,
        Instant createdAt) {
}
