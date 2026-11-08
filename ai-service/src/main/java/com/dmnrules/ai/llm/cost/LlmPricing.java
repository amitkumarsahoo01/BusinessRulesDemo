package com.dmnrules.ai.llm.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Turns token counts into dollars. Pure, so the rules are unit-testable without a database. */
public final class LlmPricing {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private LlmPricing() {
    }

    /** The price used for one call, and whether it had to fall back to the provider's most expensive row. */
    public record Resolved(BigDecimal inputPerMtok, BigDecimal outputPerMtok, boolean fallback) {

        public BigDecimal cost(long inputTokens, long outputTokens) {
            return inputPerMtok.multiply(BigDecimal.valueOf(inputTokens))
                    .add(outputPerMtok.multiply(BigDecimal.valueOf(outputTokens)))
                    .divide(MILLION, 6, RoundingMode.HALF_UP);
        }
    }

    static final Resolved FREE = new Resolved(BigDecimal.ZERO, BigDecimal.ZERO, false);

    /**
     * Longest matching prefix wins ({@code gpt-4.1-mini} over {@code gpt-4.1}). An unknown model of a
     * paid provider is charged at that provider's highest listed rates, so a typo or a brand-new model
     * makes the budget run out early rather than late. Self-hosted Ollama is free.
     */
    public static Resolved resolve(List<LlmPrice> prices, String provider, String model) {
        if ("ollama".equals(provider)) {
            return FREE;
        }
        String normalizedModel = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        List<LlmPrice> forProvider = prices.stream().filter(p -> p.provider().equals(provider)).toList();
        Optional<LlmPrice> match = forProvider.stream()
                .filter(p -> normalizedModel.startsWith(p.modelPrefix().toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(p -> p.modelPrefix().length()));
        if (match.isPresent()) {
            return new Resolved(match.get().inputPerMtok(), match.get().outputPerMtok(), false);
        }
        BigDecimal maxIn = forProvider.stream().map(LlmPrice::inputPerMtok).max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        BigDecimal maxOut = forProvider.stream().map(LlmPrice::outputPerMtok).max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        return new Resolved(maxIn, maxOut, true);
    }
}
