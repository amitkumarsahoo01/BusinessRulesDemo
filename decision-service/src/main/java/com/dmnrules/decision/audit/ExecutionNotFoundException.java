package com.dmnrules.decision.audit;

import java.util.UUID;

public class ExecutionNotFoundException extends RuntimeException {

    public ExecutionNotFoundException(UUID id) {
        super("Execution '" + id + "' was not found");
    }
}
