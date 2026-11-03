package com.dmnrules.decision.author;

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
 * the content isn't published yet and shouldn't be cached.
 */
@Component
public class AdHocEvaluator {

    public Map<String, Object> evaluate(String dmnXml, Map<String, Object> inputs) {
        Resource resource = ResourceFactory.newByteArrayResource(dmnXml.getBytes(StandardCharsets.UTF_8));
        resource.setResourceType(ResourceType.DMN);
        DMNRuntime runtime = DMNRuntimeBuilder.fromDefaults().buildConfiguration()
                .fromResources(List.of(resource))
                .getOrElseThrow(e -> new IllegalStateException("DMN model failed to compile", e));
        DMNModel model = runtime.getModels().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No DMN model found in resource"));

        DMNContext context = runtime.newContext();
        inputs.forEach(context::set);
        DMNResult result = runtime.evaluateAll(model, context);
        if (result.hasErrors()) {
            throw new IllegalStateException("Evaluation failed: " + result.getMessages());
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        for (DMNDecisionResult decisionResult : result.getDecisionResults()) {
            outputs.put(decisionResult.getDecisionName(), decisionResult.getResult());
        }
        return outputs;
    }
}
