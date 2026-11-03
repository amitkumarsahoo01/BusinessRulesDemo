package com.dmnrules.decision.audit;

import com.dmnrules.decision.trace.TraceStepRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ExecutionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowHashService rowHashService;
    private final ObjectMapper objectMapper;

    public ExecutionRepository(JdbcTemplate jdbcTemplate, RowHashService rowHashService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowHashService = rowHashService;
        this.objectMapper = objectMapper;
    }

    /**
     * STRICT audit policy: this runs in the request's transaction, so the evaluation fails if the
     * audit write fails (CLAUDE.md rule 2, docs/BUILD_PLAN.md demo scope: "Audit write: STRICT only").
     */
    @Transactional
    public UUID insert(UUID modelVersionId, String modelKey, String version, String correlationId, String caller,
            Map<String, Object> inputs, Map<String, Object> outputs, String status, int durationMs,
            List<TraceStepRecord> trace, Instant executedAt) {
        String inputsJson = toJson(inputs);
        String outputsJson = toJson(outputs);

        String previousHash = jdbcTemplate.query(
                        """
                        SELECT e.row_hash FROM decision_execution e
                        JOIN dmn_model_version v ON v.id = e.model_version_id
                        WHERE v.model_key = ?
                        ORDER BY e.executed_at DESC
                        LIMIT 1
                        """,
                        (rs, n) -> rs.getString("row_hash"), modelKey)
                .stream().findFirst().orElse(null);

        String rowHash = rowHashService.chainedHash(
                previousHash, modelKey, version, caller, inputsJson, outputsJson, status, executedAt);

        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO decision_execution
                    (id, model_version_id, correlation_id, caller, inputs, outputs, status, duration_ms, row_hash, executed_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """,
                id, modelVersionId, correlationId, caller, inputsJson, outputsJson, status, durationMs, rowHash,
                Timestamp.from(executedAt));

        insertTraceSteps(id, trace);
        return id;
    }

    private void insertTraceSteps(UUID executionId, List<TraceStepRecord> trace) {
        if (trace.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO decision_trace_step
                    (execution_id, seq, node_type, node_name, matched_rules, inputs, result, messages, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """,
                trace, trace.size(), (PreparedStatement ps, TraceStepRecord step) -> {
                    ps.setObject(1, executionId);
                    ps.setInt(2, step.seq());
                    ps.setString(3, step.nodeType());
                    ps.setString(4, step.nodeName());
                    ps.setArray(5, toSqlIntArray(ps, step.matchedRules()));
                    ps.setString(6, toJson(step.inputs()));
                    ps.setString(7, toJson(step.result()));
                    ps.setString(8, toJson(step.messages()));
                    if (step.durationMs() == null) {
                        ps.setNull(9, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(9, step.durationMs().intValue());
                    }
                });
    }

    private Array toSqlIntArray(PreparedStatement ps, List<Integer> values) throws SQLException {
        if (values == null) {
            return null;
        }
        return ps.getConnection().createArrayOf("integer", values.toArray());
    }

    public Optional<ExecutionDetail> findById(UUID id) {
        List<ExecutionDetail> rows = jdbcTemplate.query(
                """
                SELECT e.*, v.model_key, v.version
                FROM decision_execution e
                JOIN dmn_model_version v ON v.id = e.model_version_id
                WHERE e.id = ?
                """,
                executionDetailMapper(), id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ExecutionDetail detail = rows.get(0);
        List<TraceStepRecord> trace = jdbcTemplate.query(
                "SELECT * FROM decision_trace_step WHERE execution_id = ? ORDER BY seq",
                traceStepMapper(), id);
        return Optional.of(new ExecutionDetail(
                detail.id(), detail.modelKey(), detail.version(), detail.correlationId(), detail.caller(),
                detail.status(), detail.durationMs(), detail.rowHash(), detail.executedAt(),
                detail.inputs(), detail.outputs(), trace));
    }

    public PageResult<ExecutionSummary> search(ExecutionSearchFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (filter.modelKey() != null) {
            where.append(" AND v.model_key = ? ");
            params.add(filter.modelKey());
        }
        if (filter.version() != null) {
            where.append(" AND v.version = ? ");
            params.add(filter.version());
        }
        if (filter.caller() != null) {
            where.append(" AND e.caller = ? ");
            params.add(filter.caller());
        }
        if (filter.status() != null) {
            where.append(" AND e.status = ? ");
            params.add(filter.status());
        }
        if (filter.from() != null) {
            where.append(" AND e.executed_at >= ? ");
            params.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND e.executed_at <= ? ");
            params.add(Timestamp.from(filter.to()));
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM decision_execution e JOIN dmn_model_version v ON v.id = e.model_version_id" + where,
                Long.class, params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        int size = filter.size() <= 0 ? 20 : filter.size();
        int page = Math.max(filter.page(), 0);
        pageParams.add(size);
        pageParams.add(page * size);

        List<ExecutionSummary> items = jdbcTemplate.query(
                """
                SELECT e.id, v.model_key, v.version, e.caller, e.status, e.duration_ms, e.executed_at
                FROM decision_execution e
                JOIN dmn_model_version v ON v.id = e.model_version_id
                """ + where + " ORDER BY e.executed_at DESC LIMIT ? OFFSET ?",
                summaryMapper(), pageParams.toArray());

        return new PageResult<>(items, page, size, total == null ? 0 : total);
    }

    private RowMapper<ExecutionSummary> summaryMapper() {
        return (rs, n) -> new ExecutionSummary(
                UUID.fromString(rs.getString("id")),
                rs.getString("model_key"),
                rs.getString("version"),
                rs.getString("caller"),
                rs.getString("status"),
                rs.getInt("duration_ms"),
                rs.getTimestamp("executed_at").toInstant());
    }

    private RowMapper<ExecutionDetail> executionDetailMapper() {
        return (rs, n) -> new ExecutionDetail(
                UUID.fromString(rs.getString("id")),
                rs.getString("model_key"),
                rs.getString("version"),
                rs.getString("correlation_id"),
                rs.getString("caller"),
                rs.getString("status"),
                rs.getInt("duration_ms"),
                rs.getString("row_hash"),
                rs.getTimestamp("executed_at").toInstant(),
                fromJsonMap(rs.getString("inputs")),
                fromJsonMap(rs.getString("outputs")),
                List.of());
    }

    private RowMapper<TraceStepRecord> traceStepMapper() {
        return (rs, n) -> {
            Integer[] matched = toIntegerArray(rs.getArray("matched_rules"));
            return new TraceStepRecord(
                    rs.getInt("seq"),
                    rs.getString("node_type"),
                    rs.getString("node_name"),
                    matched == null ? null : List.of(matched),
                    fromJsonMap(rs.getString("inputs")),
                    fromJsonValue(rs.getString("result")),
                    fromJsonStringList(rs.getString("messages")),
                    rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"));
        };
    }

    private Integer[] toIntegerArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return null;
        }
        Object[] raw = (Object[]) sqlArray.getArray();
        Integer[] result = new Integer[raw.length];
        for (int i = 0; i < raw.length; i++) {
            result[i] = (Integer) raw[i];
        }
        return result;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit payload to JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse audit payload JSON", e);
        }
    }

    private Object fromJsonValue(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse audit payload JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJsonStringList(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse audit payload JSON", e);
        }
    }
}
