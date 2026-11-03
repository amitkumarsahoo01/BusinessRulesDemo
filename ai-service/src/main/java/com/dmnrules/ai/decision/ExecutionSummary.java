package com.dmnrules.ai.decision;

public record ExecutionSummary(
        String id, String modelKey, String version, String caller, String status, int durationMs, String executedAt) {
}
