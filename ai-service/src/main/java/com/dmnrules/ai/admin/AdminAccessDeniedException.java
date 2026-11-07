package com.dmnrules.ai.admin;

import org.springframework.http.HttpStatus;

public class AdminAccessDeniedException extends RuntimeException {

    private final HttpStatus status;

    private AdminAccessDeniedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static AdminAccessDeniedException notSignedIn() {
        return new AdminAccessDeniedException(HttpStatus.UNAUTHORIZED,
                "Sign in again to use the admin console (missing, invalid or expired session).");
    }

    public static AdminAccessDeniedException notAdmin() {
        return new AdminAccessDeniedException(HttpStatus.FORBIDDEN, "The admin console is only available to administrators.");
    }

    public HttpStatus status() {
        return status;
    }
}
