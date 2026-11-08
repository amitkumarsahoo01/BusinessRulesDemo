package com.dmnrules.ai.llm.cost;

import java.math.BigDecimal;
import java.time.Instant;

/** USD per million tokens for every model of {@code provider} whose name starts with {@code modelPrefix}. */
public record LlmPrice(String provider, String modelPrefix, BigDecimal inputPerMtok, BigDecimal outputPerMtok,
        String updatedBy, Instant updatedAt) {
}
