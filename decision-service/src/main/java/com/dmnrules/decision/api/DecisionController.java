package com.dmnrules.decision.api;

import com.dmnrules.decision.api.dto.EvaluateResponseDto;
import com.dmnrules.decision.evaluate.EvaluationResult;
import com.dmnrules.decision.evaluate.EvaluationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

    private final EvaluationService evaluationService;

    public DecisionController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/{modelKey}/evaluate")
    public EvaluateResponseDto evaluate(
            @PathVariable String modelKey,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "false") boolean trace,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String caller,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody Map<String, Object> inputs) {
        EvaluationResult result = evaluationService.evaluate(
                modelKey, Optional.ofNullable(version), inputs, caller, correlationId, true);
        return toDto(result, trace);
    }

    @PostMapping("/{modelKey}/simulate")
    public EvaluateResponseDto simulate(
            @PathVariable String modelKey,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "true") boolean trace,
            @RequestHeader(value = "X-User", defaultValue = "anonymous") String caller,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody Map<String, Object> inputs) {
        EvaluationResult result = evaluationService.evaluate(
                modelKey, Optional.ofNullable(version), inputs, caller, correlationId, false);
        return toDto(result, trace);
    }

    private EvaluateResponseDto toDto(EvaluationResult result, boolean includeTrace) {
        return new EvaluateResponseDto(
                result.executionId(), result.version(), result.outputs(), result.status(),
                includeTrace ? result.trace() : null);
    }
}
