package com.dmnrules.decision.author;

import com.dmnrules.decision.trace.TraceCapturingListener;
import com.dmnrules.decision.trace.TraceStepRecord;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles and evaluates raw DMN XML on the fly, outside the published-only {@code ModelRegistry}.
 * Used by validate/test-run (and {@code simulate} against a draft, once drafts support it) where
 * the content isn't published yet and shouldn't be cached. Captures a trace the same way the main
 * evaluation path does (M2's {@code TraceCapturingListener}) so the test bench can highlight
 * matched rows, not just show pass/fail.
 */
@Component
public class AdHocEvaluator {

    public AdHocEvaluationResult evaluate(String dmnXml, Map<String, Object> inputs) {
        Resource resource = ResourceFactory.newByteArrayResource(dmnXml.getBytes(StandardCharsets.UTF_8));
        resource.setResourceType(ResourceType.DMN);
        DMNRuntime runtime = DMNRuntimeBuilder.fromDefaults().buildConfiguration()
                .fromResources(List.of(resource))
                .getOrElseThrow(e -> new IllegalStateException("DMN model failed to compile", e));
        DMNModel model = runtime.getModels().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No DMN model found in resource"));

        TraceCapturingListener listener = new TraceCapturingListener();
        runtime.addListener(listener);

        DMNContext context = runtime.newContext();
        inputs.forEach(context::set);

        TraceCapturingListener.begin();
        List<TraceStepRecord> trace;
        DMNResult result;
        try {
            result = runtime.evaluateAll(model, context);
        } finally {
            trace = TraceCapturingListener.end();
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        for (DMNDecisionResult decisionResult : result.getDecisionResults()) {
            outputs.put(decisionResult.getDecisionName(), decisionResult.getResult());
        }
        String status = result.hasErrors() ? "FAILED" : "SUCCEEDED";
        return new AdHocEvaluationResult(outputs, status, trace);
    }
}
