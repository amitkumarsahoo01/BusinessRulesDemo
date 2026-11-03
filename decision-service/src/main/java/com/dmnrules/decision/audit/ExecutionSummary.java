package com.dmnrules.decision.audit;

import java.time.Instant;
import java.util.UUID;

public record ExecutionSummary(
        UUID id, String modelKey, String version, String caller, String status,
        int durationMs, Instant executedAt) {
}
