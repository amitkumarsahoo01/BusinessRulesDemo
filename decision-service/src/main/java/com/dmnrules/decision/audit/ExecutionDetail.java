package com.dmnrules.decision.audit;

import com.dmnrules.decision.trace.TraceStepRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ExecutionDetail(
        UUID id, String modelKey, String version, String correlationId, String caller, String status,
        int durationMs, String rowHash, Instant executedAt,
        Map<String, Object> inputs, Map<String, Object> outputs, List<TraceStepRecord> trace) {
}
