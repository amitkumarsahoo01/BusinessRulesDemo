package com.dmnrules.decision.audit;

import java.time.Instant;

public record ExecutionSearchFilter(
        String modelKey, String version, String caller, String status, Instant from, Instant to,
        int page, int size) {
}
