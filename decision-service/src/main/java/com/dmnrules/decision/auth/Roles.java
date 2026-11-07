package com.dmnrules.decision.auth;

/** Role and system-account names. Demo roles: rule-author, rule-approver, auditor. */
public final class Roles {

    public static final String ADMIN = "admin";
    public static final String AUDITOR = "auditor";
    /** Integrations: may evaluate/simulate and read, nothing else. */
    public static final String API_CLIENT = "api-client";
    /** Internal callers (ai-service): may act on behalf of a user via {@code X-User}. */
    public static final String SERVICE = "service";

    public static final String ADMIN_USERNAME = "admin";
    public static final String API_CLIENT_USERNAME = "api-client";
    public static final String SERVICE_USERNAME = "ai-service";

    private Roles() {
    }

    /** Roles that may only evaluate/simulate decisions and read; they cannot change rules. */
    public static boolean isReadOnly(String role) {
        return API_CLIENT.equals(role) || AUDITOR.equals(role);
    }

    /** API-only accounts cannot sign in to the web app. */
    public static boolean isApiOnly(String role) {
        return API_CLIENT.equals(role) || SERVICE.equals(role);
    }
}
