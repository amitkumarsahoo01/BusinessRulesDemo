package com.dmnrules.decision.author;

import java.util.List;

public record TestRunResult(boolean allPassed, String status, List<TestCaseResultDto> results) {
}
