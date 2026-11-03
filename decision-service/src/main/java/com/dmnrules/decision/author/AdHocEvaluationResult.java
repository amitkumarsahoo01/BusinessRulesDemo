package com.dmnrules.decision.author;

import com.dmnrules.decision.trace.TraceStepRecord;

import java.util.List;
import java.util.Map;

public record AdHocEvaluationResult(Map<String, Object> outputs, String status, List<TraceStepRecord> trace) {
}
