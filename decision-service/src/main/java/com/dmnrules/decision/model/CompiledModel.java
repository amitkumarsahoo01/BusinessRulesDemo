package com.dmnrules.decision.model;

import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;

import java.util.UUID;

public record CompiledModel(UUID modelVersionId, String modelKey, String version, DMNRuntime runtime, DMNModel model) {
}
