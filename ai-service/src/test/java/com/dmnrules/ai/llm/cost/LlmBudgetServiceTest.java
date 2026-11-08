package com.dmnrules.ai.llm.cost;

import com.dmnrules.ai.llm.InvalidLlmSettingsException;
import com.dmnrules.ai.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmBudgetServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final Instant NOV_1 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant DEC_1 = Instant.parse("2026-12-01T00:00:00Z");

    private final LlmCostRepository repository = mock(LlmCostRepository.class);
    private final LlmBudgetService service =
            new LlmBudgetService(repository, new BigDecimal("10"), 80, Clock.fixed(NOW, ZoneOffset.UTC));

    private void spent(String usd) {
        when(repository.spendBetween(NOV_1, DEC_1)).thenReturn(new BigDecimal(usd));
    }

    @Test
    void recordsEachCallPricedFromTheTable() {
        when(repository.prices()).thenReturn(List.of(
                new LlmPrice("gemini", "gemini-2.5-flash", new BigDecimal("0.30"), new BigDecimal("2.50"), "seed", NOW)));

        service.record(LlmProvider.GEMINI, "gemini-2.5-flash", 10_000, 1_000, false);

        ArgumentCaptor<LlmCostRepository.UsageRow> row = ArgumentCaptor.forClass(LlmCostRepository.UsageRow.class);
        verify(repository).insertUsage(row.capture());
        assertThat(row.getValue().provider()).isEqualTo("gemini");
        assertThat(row.getValue().costUsd()).isEqualByComparingTo("0.0055");
        assertThat(row.getValue().priceFallback()).isFalse();
        assertThat(row.getValue().at()).isEqualTo(NOW);
    }

    @Test
    void meteringFailuresNeverPropagate() {
        when(repository.prices()).thenReturn(List.of());
        doThrow(new RuntimeException("db down")).when(repository).insertUsage(any());
        service.record(LlmProvider.ANTHROPIC, "claude-sonnet-5-5", 1, 1, false);
    }

    @Test
    void allowsChatUnderBudgetAndBlocksPaidProvidersOnceItIsUsedUp() {
        when(repository.budget()).thenReturn(Optional.empty());
        spent("9.99");
        assertThat(service.blockReason(LlmProvider.ANTHROPIC)).isEmpty();

        spent("10.00");
        assertThat(service.blockReason(LlmProvider.GEMINI)).get().asString().contains("$10.00 of $10.00");
        assertThat(service.blockReason(LlmProvider.OPENAI)).isPresent();
        // Self-hosted: no per-token cost, keeps working.
        assertThat(service.blockReason(LlmProvider.OLLAMA)).isEmpty();
    }

    @Test
    void warnOnlyModeNeverBlocks() {
        when(repository.budget()).thenReturn(Optional.of(
                new LlmCostRepository.BudgetRow(new BigDecimal("5"), 80, false, "admin", NOW)));
        spent("50");
        assertThat(service.blockReason(LlmProvider.ANTHROPIC)).isEmpty();
        assertThat(service.status().state()).isEqualTo(LlmBudgetService.State.EXCEEDED);
    }

    @Test
    void statusReportsWarningAboveThreshold() {
        when(repository.budget()).thenReturn(Optional.empty());
        spent("8.50");
        when(repository.spendByModel(NOV_1, DEC_1)).thenReturn(List.of());
        LlmBudgetService.Status status = service.status();
        assertThat(status.month()).isEqualTo("2026-11");
        assertThat(status.percentUsed()).isEqualTo(85.0);
        assertThat(status.remainingUsd()).isEqualByComparingTo("1.50");
        assertThat(status.state()).isEqualTo(LlmBudgetService.State.WARNING);
    }

    @Test
    void validatesBudgetAndPriceInput() {
        assertThatThrownBy(() -> service.update(new BigDecimal("-1"), 80, true, "admin"))
                .isInstanceOf(InvalidLlmSettingsException.class);
        assertThatThrownBy(() -> service.update(BigDecimal.TEN, 0, true, "admin"))
                .isInstanceOf(InvalidLlmSettingsException.class);
        assertThatThrownBy(() -> service.upsertPrice("watsonx", "x", BigDecimal.ONE, BigDecimal.ONE, "admin"))
                .hasMessageContaining("Unknown provider");
        assertThatThrownBy(() -> service.upsertPrice("gemini", "bad name;", BigDecimal.ONE, BigDecimal.ONE, "admin"))
                .isInstanceOf(InvalidLlmSettingsException.class);

        service.upsertPrice("gemini", " gemini-3-flash ", new BigDecimal("0.5"), new BigDecimal("3"), "admin");
        verify(repository).upsertPrice(eq("gemini"), eq("gemini-3-flash"), any(), any(), eq("admin"), eq(NOW));
    }
}
