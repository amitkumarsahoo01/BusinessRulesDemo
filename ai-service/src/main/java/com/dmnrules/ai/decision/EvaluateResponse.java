package com.dmnrules.ai.decision;

import java.util.List;
import java.util.Map;

public record EvaluateResponse(
        String executionId, String modelVersion, Map<String, Object> outputs, String status,
        List<ExecutionDetail.TraceStep> trace) {
}
