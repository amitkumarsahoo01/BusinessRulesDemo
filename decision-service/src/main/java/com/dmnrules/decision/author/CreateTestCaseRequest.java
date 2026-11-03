package com.dmnrules.decision.author;

import java.util.Map;

public record CreateTestCaseRequest(String name, Map<String, Object> inputs, Map<String, Object> expectedOutputs) {
}
