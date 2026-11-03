package com.dmnrules.decision.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only log of who did what to a rule (CREATE|EDIT|VALIDATE|APPROVE|PUBLISH|RETIRE|ROLLBACK),
 * separate from decision_execution (CLAUDE.md rule 2 / docs/Architecture.md "rule_change_audit").
 */
@Repository
public class RuleChangeAuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RuleChangeAuditRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(String modelKey, UUID versionId, String action, String actor, Object diff) {
        String diffJson;
        try {
            diffJson = diff == null ? null : objectMapper.writeValueAsString(diff);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize rule-change diff to JSON", e);
        }
        jdbcTemplate.update(
                "INSERT INTO rule_change_audit (model_key, version_id, action, actor, diff) VALUES (?, ?, ?, ?, ?::jsonb)",
                modelKey, versionId, action, actor, diffJson);
    }

    public List<RuleChangeAuditRow> findByModelKey(String modelKey) {
        return jdbcTemplate.query(
                "SELECT * FROM rule_change_audit WHERE model_key = ? ORDER BY at DESC",
                (rs, n) -> new RuleChangeAuditRow(
                        rs.getLong("id"), rs.getString("model_key"),
                        rs.getString("version_id") == null ? null : UUID.fromString(rs.getString("version_id")),
                        rs.getString("action"), rs.getString("actor"), rs.getString("diff"),
                        rs.getTimestamp("at").toInstant()),
                modelKey);
    }
}
