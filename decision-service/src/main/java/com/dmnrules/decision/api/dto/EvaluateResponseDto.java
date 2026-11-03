package com.dmnrules.decision.api.dto;

import com.dmnrules.decision.trace.TraceStepRecord;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvaluateResponseDto(
        UUID executionId, String modelVersion, Map<String, Object> outputs, String status,
        List<TraceStepRecord> trace) {
}
