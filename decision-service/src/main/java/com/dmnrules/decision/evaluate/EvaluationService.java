package com.dmnrules.decision.evaluate;

import com.dmnrules.decision.audit.ExecutionRepository;
import com.dmnrules.decision.model.CompiledModel;
import com.dmnrules.decision.model.ModelRegistry;
import com.dmnrules.decision.trace.TraceCapturingListener;
import com.dmnrules.decision.trace.TraceStepRecord;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Evaluates DMN models through the engine and, for real decisions, records the audit trail.
 * {@code simulate} calls go through the same evaluation path with {@code audited=false} so the
 * engine output is real but nothing is written (CLAUDE.md rule 6 / docs/Architecture.md "simulate").
 */
@Service
public class EvaluationService {

    private final ModelRegistry registry;
    private final ExecutionRepository executionRepository;

    public EvaluationService(ModelRegistry registry, ExecutionRepository executionRepository) {
        this.registry = registry;
        this.executionRepository = executionRepository;
    }

    public EvaluationResult evaluate(String modelKey, Optional<String> version, Map<String, Object> inputs,
            String caller, String correlationId, boolean audited) {
        return evaluate(modelKey, version, inputs, caller, correlationId, audited, Instant.now());
    }

    public EvaluationResult evaluate(String modelKey, Optional<String> version, Map<String, Object> inputs,
            String caller, String correlationId, boolean audited, Instant executedAt) {
        CompiledModel compiled = registry.resolve(modelKey, version);

        DMNContext context = compiled.runtime().newContext();
        inputs.forEach(context::set);

        TraceCapturingListener.begin();
        List<TraceStepRecord> trace;
        DMNResult result;
        try {
            result = compiled.runtime().evaluateAll(compiled.model(), context);
        } finally {
            trace = TraceCapturingListener.end();
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        for (DMNDecisionResult decisionResult : result.getDecisionResults()) {
            outputs.put(decisionResult.getDecisionName(), decisionResult.getResult());
        }
        String status = result.hasErrors() ? "FAILED" : "SUCCEEDED";

        UUID executionId = null;
        if (audited) {
            long durationMs = trace.stream()
                    .filter(s -> "DECISION".equals(s.nodeType()))
                    .mapToLong(s -> s.durationMs() == null ? 0 : s.durationMs())
                    .sum();
            executionId = executionRepository.insert(
                    compiled.modelVersionId(), compiled.modelKey(), compiled.version(), correlationId, caller,
                    inputs, outputs, status, (int) durationMs, trace, executedAt);
        }

        return new EvaluationResult(executionId, compiled.modelKey(), compiled.version(), outputs, status, trace);
    }
}
