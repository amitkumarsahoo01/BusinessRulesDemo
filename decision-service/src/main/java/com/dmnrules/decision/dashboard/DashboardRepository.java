package com.dmnrules.decision.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Read-only aggregates over the audit tables for the dashboard. Never writes. */
@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbcTemplate;

    public DashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Totals(long total, long failed, double avgDurationMs, double p95DurationMs) {
    }

    public record DayModelCount(LocalDate day, String modelKey, long total, long failed) {
    }

    public record ModelActivity(String modelKey, long total, long failed, double avgDurationMs, Instant lastExecutedAt) {
    }

    public record OutcomeRow(String modelKey, String output, String value, long count) {
    }

    private static final String FROM_JOIN =
            " FROM decision_execution e JOIN dmn_model_version v ON v.id = e.model_version_id ";

    public Totals totals(Instant from, Instant to, String modelKey) {
        List<Object> params = new ArrayList<>();
        String where = where(from, to, modelKey, params);
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE e.status <> 'SUCCEEDED') AS failed,
                       coalesce(avg(e.duration_ms), 0) AS avg_ms,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY e.duration_ms), 0) AS p95_ms
                """ + FROM_JOIN + where,
                (rs, n) -> new Totals(rs.getLong("total"), rs.getLong("failed"),
                        rs.getDouble("avg_ms"), rs.getDouble("p95_ms")),
                params.toArray());
    }

    public List<DayModelCount> dailyByModel(Instant from, Instant to, String modelKey) {
        List<Object> params = new ArrayList<>();
        String where = where(from, to, modelKey, params);
        return jdbcTemplate.query(
                """
                SELECT (e.executed_at AT TIME ZONE 'UTC')::date AS day, v.model_key,
                       count(*) AS total, count(*) FILTER (WHERE e.status <> 'SUCCEEDED') AS failed
                """ + FROM_JOIN + where + " GROUP BY 1, 2 ORDER BY 1, 2",
                (rs, n) -> new DayModelCount(rs.getObject("day", LocalDate.class), rs.getString("model_key"),
                        rs.getLong("total"), rs.getLong("failed")),
                params.toArray());
    }

    public List<ModelActivity> activityByModel(Instant from, Instant to, String modelKey) {
        List<Object> params = new ArrayList<>();
        String where = where(from, to, modelKey, params);
        return jdbcTemplate.query(
                """
                SELECT v.model_key, count(*) AS total,
                       count(*) FILTER (WHERE e.status <> 'SUCCEEDED') AS failed,
                       avg(e.duration_ms) AS avg_ms, max(e.executed_at) AS last_at
                """ + FROM_JOIN + where + " GROUP BY v.model_key ORDER BY total DESC",
                (rs, n) -> new ModelActivity(rs.getString("model_key"), rs.getLong("total"), rs.getLong("failed"),
                        rs.getDouble("avg_ms"), rs.getTimestamp("last_at").toInstant()),
                params.toArray());
    }

    /** Most recent execution per model, over all time (a model may be idle in the selected period). */
    public List<ModelActivity> lastExecutionPerModel() {
        return jdbcTemplate.query(
                "SELECT v.model_key, max(e.executed_at) AS last_at" + FROM_JOIN + " GROUP BY v.model_key",
                (rs, n) -> new ModelActivity(rs.getString("model_key"), 0, 0, 0,
                        rs.getTimestamp("last_at").toInstant()));
    }

    /** Value counts for every string-valued top-level output, so categorical outcomes can be charted. */
    public List<OutcomeRow> stringOutcomes(Instant from, Instant to, String modelKey) {
        List<Object> params = new ArrayList<>();
        String where = where(from, to, modelKey, params);
        return jdbcTemplate.query(
                """
                SELECT v.model_key, kv.key AS output, kv.value #>> '{}' AS value, count(*) AS n
                """ + FROM_JOIN + " CROSS JOIN LATERAL jsonb_each(e.outputs) kv " + where
                        + " AND jsonb_typeof(kv.value) = 'string' GROUP BY 1, 2, 3 ORDER BY 1, 2, n DESC",
                (rs, n) -> new OutcomeRow(rs.getString("model_key"), rs.getString("output"),
                        rs.getString("value"), rs.getLong("n")),
                params.toArray());
    }

    private static String where(Instant from, Instant to, String modelKey, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE e.executed_at >= ? AND e.executed_at < ? ");
        params.add(Timestamp.from(from));
        params.add(Timestamp.from(to));
        if (modelKey != null && !modelKey.isBlank()) {
            where.append(" AND v.model_key = ? ");
            params.add(modelKey);
        }
        return where.toString();
    }
}
