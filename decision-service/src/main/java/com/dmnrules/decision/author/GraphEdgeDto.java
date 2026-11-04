package com.dmnrules.decision.author;

/** Edge direction is data flow: {@code from} is required by {@code to}. kind: INFORMATION | KNOWLEDGE | AUTHORITY. */
public record GraphEdgeDto(String from, String to, String kind) {
}
