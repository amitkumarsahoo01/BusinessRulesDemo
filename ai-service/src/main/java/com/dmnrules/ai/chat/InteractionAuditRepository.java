package com.dmnrules.ai.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Logs every conversation turn (CLAUDE.md: "Each conversation, prompt, tool call and token count is logged"). */
@Repository
public class InteractionAuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InteractionAuditRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(UUID conversationId, String role, String content, Object toolCalls, Integer tokenCount) {
        String toolCallsJson;
        try {
            toolCallsJson = toolCalls == null ? null : objectMapper.writeValueAsString(toolCalls);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool call log", e);
        }
        jdbcTemplate.update(
                "INSERT INTO ai_interaction_audit (conversation_id, role, content, tool_calls, token_count) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?)",
                conversationId.toString(), role, content, toolCallsJson, tokenCount);
    }

    public List<InteractionRow> findByConversation(UUID conversationId) {
        return jdbcTemplate.query(
                "SELECT * FROM ai_interaction_audit WHERE conversation_id = ? ORDER BY at",
                (rs, n) -> new InteractionRow(
                        rs.getLong("id"), rs.getString("conversation_id"), rs.getString("role"),
                        rs.getString("content"), rs.getString("tool_calls"), rs.getTimestamp("at").toInstant()),
                conversationId.toString());
    }
}
