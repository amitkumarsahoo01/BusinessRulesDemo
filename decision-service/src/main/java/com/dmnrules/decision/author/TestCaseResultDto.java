package com.dmnrules.decision.author;

import com.dmnrules.decision.trace.TraceStepRecord;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TestCaseResultDto(
        UUID testCaseId, String name, boolean passed, Map<String, Object> actualOutputs,
        Map<String, Object> expectedOutputs, List<TraceStepRecord> trace) {
}
