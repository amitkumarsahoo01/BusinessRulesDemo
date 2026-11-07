package com.dmnrules.ai.llm;

import java.time.Instant;

/**
 * Which LLM answers chat questions. {@code baseUrl} is null for "use the provider's default".
 * {@code updatedBy}/{@code updatedAt} are null for the env-var defaults used before any admin change.
 */
public record LlmSettings(LlmProvider provider, String model, String baseUrl, String updatedBy, Instant updatedAt) {

    public LlmSettings withAudit(String by, Instant at) {
        return new LlmSettings(provider, model, baseUrl, by, at);
    }
}
