package com.dmnrules.decision.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Execution activity for the dashboard over the last {@code days} UTC days (today included).
 * {@code daily} has exactly one entry per day, oldest first, including days with no executions.
 */
public record ExecutionStatsDto(
        Instant from,
        Instant to,
        int days,
        long total,
        long succeeded,
        long failed,
        double avgDurationMs,
        double p95DurationMs,
        /** Total for the same-length period immediately before {@code from}, for a trend indicator. */
        long previousPeriodTotal,
        List<DailyBucket> daily,
        List<ModelBreakdown> byModel,
        List<OutcomeBreakdown> outcomes) {

    public record DailyBucket(LocalDate date, long total, long failed, Map<String, Long> byModel) {
    }

    public record ModelBreakdown(String modelKey, long total, long failed, double avgDurationMs) {
    }

    /** Distribution of one categorical (string) decision output, e.g. Eligibility: APPROVED / REVIEW / DECLINED. */
    public record OutcomeBreakdown(String modelKey, String output, List<OutcomeCount> values) {
    }

    public record OutcomeCount(String value, long count) {
    }
}
