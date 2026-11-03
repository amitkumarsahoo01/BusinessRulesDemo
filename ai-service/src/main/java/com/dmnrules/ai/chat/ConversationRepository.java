package com.dmnrules.ai.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID create(String createdBy, String pinnedModelKey, String pinnedVersion, UUID pinnedExecutionId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO ai_conversation (id, created_by, pinned_model_key, pinned_version, pinned_execution_id)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, createdBy, pinnedModelKey, pinnedVersion, pinnedExecutionId);
        return id;
    }

    public Optional<ConversationRow> findById(UUID id) {
        List<ConversationRow> rows = jdbcTemplate.query(
                "SELECT * FROM ai_conversation WHERE id = ?",
                (rs, n) -> new ConversationRow(
                        UUID.fromString(rs.getString("id")), rs.getString("created_by"),
                        rs.getString("pinned_model_key"), rs.getString("pinned_version"),
                        rs.getString("pinned_execution_id") == null ? null : UUID.fromString(rs.getString("pinned_execution_id")),
                        rs.getTimestamp("created_at").toInstant()),
                id);
        return rows.stream().findFirst();
    }
}
