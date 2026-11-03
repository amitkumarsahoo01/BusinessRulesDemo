package com.dmnrules.decision.author;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TestCaseRow(
        UUID id, String modelKey, String name, Map<String, Object> inputs,
        Map<String, Object> expectedOutputs, String createdBy, Instant createdAt) {
}
