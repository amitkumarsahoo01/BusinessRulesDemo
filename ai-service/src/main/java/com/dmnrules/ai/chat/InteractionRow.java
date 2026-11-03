package com.dmnrules.ai.chat;

import java.time.Instant;

/** role: USER | ASSISTANT */
public record InteractionRow(long id, String conversationId, String role, String content, String toolCallsJson, Instant at) {
}
