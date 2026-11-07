package com.dmnrules.decision.auth;

/** {@code token} is a signed session token; send it back as {@code Authorization: Bearer <token>}. */
public record LoginResponse(String username, String displayName, String role, String token) {
}
