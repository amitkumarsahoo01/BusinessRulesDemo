package com.dmnrules.ai.llm.cost;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Prices, the append-only usage ledger and the budget row. */
@Repository
public class LlmCostRepository {

    private final JdbcTemplate jdbc;

    public LlmCostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UsageRow(String provider, String model, int inputTokens, int outputTokens, BigDecimal costUsd,
            boolean estimated, boolean priceFallback, Instant at) {
    }

    public record ModelSpend(String provider, String model, long calls, long inputTokens, long outputTokens,
            BigDecimal costUsd, long estimatedCalls, long fallbackPricedCalls) {
    }

    public record DailySpend(LocalDate day, BigDecimal costUsd) {
    }

    public record BudgetRow(BigDecimal monthlyLimitUsd, int warnPercent, boolean hardStop, String updatedBy,
            Instant updatedAt) {
    }

    // ---- prices ----

    public List<LlmPrice> prices() {
        return jdbc.query("SELECT * FROM ai_llm_price ORDER BY provider, model_prefix",
                (rs, n) -> new LlmPrice(rs.getString("provider"), rs.getString("model_prefix"),
                        rs.getBigDecimal("input_per_mtok"), rs.getBigDecimal("output_per_mtok"),
                        rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant()));
    }

    public void upsertPrice(String provider, String modelPrefix, BigDecimal in, BigDecimal out, String by, Instant at) {
        jdbc.update("""
                INSERT INTO ai_llm_price (provider, model_prefix, input_per_mtok, output_per_mtok, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (provider, model_prefix) DO UPDATE SET input_per_mtok = EXCLUDED.input_per_mtok,
                    output_per_mtok = EXCLUDED.output_per_mtok, updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """, provider, modelPrefix, in, out, by, Timestamp.from(at));
    }

    public boolean deletePrice(String provider, String modelPrefix) {
        return jdbc.update("DELETE FROM ai_llm_price WHERE provider = ? AND model_prefix = ?", provider, modelPrefix) > 0;
    }

    // ---- usage ledger (insert/select only) ----

    public void insertUsage(UsageRow row) {
        jdbc.update("""
                INSERT INTO ai_llm_usage (provider, model, input_tokens, output_tokens, cost_usd, estimated, price_fallback, at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, row.provider(), row.model(), row.inputTokens(), row.outputTokens(), row.costUsd(), row.estimated(),
                row.priceFallback(), Timestamp.from(row.at()));
    }

    public BigDecimal spendBetween(Instant from, Instant to) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cost_usd), 0) FROM ai_llm_usage WHERE at >= ? AND at < ?",
                BigDecimal.class, Timestamp.from(from), Timestamp.from(to));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    public List<ModelSpend> spendByModel(Instant from, Instant to) {
        return jdbc.query("""
                SELECT provider, model, COUNT(*) AS calls, SUM(input_tokens) AS input_tokens,
                       SUM(output_tokens) AS output_tokens, SUM(cost_usd) AS cost_usd,
                       COUNT(*) FILTER (WHERE estimated) AS estimated_calls,
                       COUNT(*) FILTER (WHERE price_fallback) AS fallback_calls
                FROM ai_llm_usage WHERE at >= ? AND at < ?
                GROUP BY provider, model ORDER BY SUM(cost_usd) DESC, provider, model
                """,
                (rs, n) -> new ModelSpend(rs.getString("provider"), rs.getString("model"), rs.getLong("calls"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getBigDecimal("cost_usd"),
                        rs.getLong("estimated_calls"), rs.getLong("fallback_calls")),
                Timestamp.from(from), Timestamp.from(to));
    }

    public List<DailySpend> dailySpend(Instant from, Instant to) {
        return jdbc.query("""
                SELECT (at AT TIME ZONE 'UTC')::date AS day, SUM(cost_usd) AS cost_usd
                FROM ai_llm_usage WHERE at >= ? AND at < ?
                GROUP BY 1 ORDER BY 1
                """,
                (rs, n) -> new DailySpend(rs.getObject("day", LocalDate.class), rs.getBigDecimal("cost_usd")),
                Timestamp.from(from), Timestamp.from(to));
    }

    // ---- budget ----

    public Optional<BudgetRow> budget() {
        return jdbc.query("SELECT * FROM ai_llm_budget WHERE id = 1",
                (rs, n) -> new BudgetRow(rs.getBigDecimal("monthly_limit_usd"), rs.getInt("warn_percent"),
                        rs.getBoolean("hard_stop"), rs.getString("updated_by"),
                        rs.getTimestamp("updated_at").toInstant()))
                .stream().findFirst();
    }

    public void saveBudget(BudgetRow row) {
        jdbc.update("""
                INSERT INTO ai_llm_budget (id, monthly_limit_usd, warn_percent, hard_stop, updated_by, updated_at)
                VALUES (1, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET monthly_limit_usd = EXCLUDED.monthly_limit_usd,
                    warn_percent = EXCLUDED.warn_percent, hard_stop = EXCLUDED.hard_stop,
                    updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """, row.monthlyLimitUsd(), row.warnPercent(), row.hardStop(), row.updatedBy(),
                Timestamp.from(row.updatedAt()));
    }

    static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
