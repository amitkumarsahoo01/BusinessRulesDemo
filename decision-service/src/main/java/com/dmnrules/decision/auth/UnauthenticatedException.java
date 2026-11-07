package com.dmnrules.decision.auth;

public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("Missing, invalid or expired session token. Sign in again.");
    }
}
