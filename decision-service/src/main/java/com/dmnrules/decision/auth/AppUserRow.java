package com.dmnrules.decision.auth;

public record AppUserRow(String username, String passwordHash, String displayName, String role) {
}
