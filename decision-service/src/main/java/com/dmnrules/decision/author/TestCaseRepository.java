package com.dmnrules.decision.author;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class TestCaseRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TestCaseRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID insert(String modelKey, String name, Map<String, Object> inputs,
            Map<String, Object> expectedOutputs, String createdBy) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO model_test_case (id, model_key, name, inputs, expected_outputs, created_by)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """,
                id, modelKey, name, toJson(inputs), toJson(expectedOutputs), createdBy);
        return id;
    }

    public List<TestCaseRow> findByModelKey(String modelKey) {
        return jdbcTemplate.query(
                "SELECT * FROM model_test_case WHERE model_key = ? ORDER BY created_at",
                (rs, n) -> new TestCaseRow(
                        UUID.fromString(rs.getString("id")), rs.getString("model_key"), rs.getString("name"),
                        fromJson(rs.getString("inputs")), fromJson(rs.getString("expected_outputs")),
                        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()),
                modelKey);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize test case JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse test case JSON", e);
        }
    }
}
