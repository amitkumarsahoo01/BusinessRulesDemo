package com.dmnrules.decision.author;

public class InvalidLifecycleTransitionException extends RuntimeException {

    public InvalidLifecycleTransitionException(String message) {
        super(message);
    }
}
