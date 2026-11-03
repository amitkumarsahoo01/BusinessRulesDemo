package com.dmnrules.decision.audit;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

@Component
public class RowHashService {

    public String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Chains the new row to the previous one so tampering with history is detectable. */
    public String chainedHash(String previousHash, String modelKey, String version, String caller,
            String inputsJson, String outputsJson, String status, Instant executedAt) {
        String canonical = String.join("|",
                String.valueOf(previousHash), modelKey, version, caller,
                String.valueOf(inputsJson), String.valueOf(outputsJson), status, executedAt.toString());
        return sha256Hex(canonical);
    }
}
