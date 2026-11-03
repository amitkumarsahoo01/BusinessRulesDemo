package com.dmnrules.decision.trace;

import java.util.List;
import java.util.Map;

public record TraceStepRecord(
        int seq,
        String nodeType,
        String nodeName,
        List<Integer> matchedRules,
        Map<String, Object> inputs,
        Object result,
        List<String> messages,
        Long durationMs) {
}
