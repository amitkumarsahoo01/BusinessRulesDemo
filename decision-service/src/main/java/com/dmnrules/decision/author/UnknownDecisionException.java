package com.dmnrules.decision.author;

public class UnknownDecisionException extends RuntimeException {

    public UnknownDecisionException(String decisionName) {
        super("No decision table named '" + decisionName + "' found in this model");
    }
}
