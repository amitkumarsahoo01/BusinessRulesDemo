package com.dmnrules.ai.chat;

import java.time.Instant;

public record ChatMessageDto(String role, String content, String toolCallsJson, Instant at) {
}
