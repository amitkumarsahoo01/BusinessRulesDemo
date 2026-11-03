package com.dmnrules.ai.decision;

import java.util.List;
import java.util.Map;

public record ExecutionDetail(
        String id, String modelKey, String version, String correlationId, String caller, String status,
        int durationMs, String rowHash, String executedAt,
        Map<String, Object> inputs, Map<String, Object> outputs, List<TraceStep> trace) {

    public record TraceStep(
            int seq, String nodeType, String nodeName, List<Integer> matchedRules,
            Map<String, Object> inputs, Object result, List<String> messages, Long durationMs) {
    }
}
