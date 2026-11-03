package com.dmnrules.decision.evaluate;

import com.dmnrules.decision.trace.TraceStepRecord;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvaluationResult(
        UUID executionId, String modelKey, String version, Map<String, Object> outputs, String status,
        List<TraceStepRecord> trace) {
}
