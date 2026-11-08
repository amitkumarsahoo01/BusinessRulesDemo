package com.dmnrules.ai.llm.cost;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPricingTest {

    private static LlmPrice price(String provider, String prefix, String in, String out) {
        return new LlmPrice(provider, prefix, new BigDecimal(in), new BigDecimal(out), "seed", null);
    }

    private final List<LlmPrice> prices = List.of(
            price("openai", "gpt-4.1", "2.00", "8.00"),
            price("openai", "gpt-4.1-mini", "0.40", "1.60"),
            price("anthropic", "claude-sonnet-4-5", "3.00", "15.00"),
            price("gemini", "gemini-2.5-flash", "0.30", "2.50"),
            price("gemini", "gemini-2.5-pro", "1.25", "10.00"));

    @Test
    void longestPrefixWins() {
        assertThat(LlmPricing.resolve(prices, "openai", "gpt-4.1-mini").inputPerMtok()).isEqualByComparingTo("0.40");
        assertThat(LlmPricing.resolve(prices, "openai", "gpt-4.1").inputPerMtok()).isEqualByComparingTo("2.00");
        // Dated snapshot ids match their family's row.
        assertThat(LlmPricing.resolve(prices, "anthropic", "claude-sonnet-4-5-20250929").fallback()).isFalse();
    }

    @Test
    void unknownModelIsChargedAtTheProvidersHighestRates() {
        LlmPricing.Resolved r = LlmPricing.resolve(prices, "gemini", "gemini-9-ultra");
        assertThat(r.fallback()).isTrue();
        assertThat(r.inputPerMtok()).isEqualByComparingTo("1.25");
        assertThat(r.outputPerMtok()).isEqualByComparingTo("10.00");
    }

    @Test
    void ollamaIsFree() {
        assertThat(LlmPricing.resolve(prices, "ollama", "llama3.1").cost(1_000_000, 1_000_000)).isEqualByComparingTo("0");
    }

    @Test
    void costIsPerMillionTokens() {
        // 12,000 in at $3 + 800 out at $15 = 0.036 + 0.012
        assertThat(LlmPricing.resolve(prices, "anthropic", "claude-sonnet-4-5").cost(12_000, 800))
                .isEqualByComparingTo("0.048");
    }
}
