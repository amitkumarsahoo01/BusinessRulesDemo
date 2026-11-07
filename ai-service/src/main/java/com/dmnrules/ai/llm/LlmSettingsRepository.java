package com.dmnrules.ai.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class LlmSettingsRepository {

    private final JdbcTemplate jdbcTemplate;

    public LlmSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record HistoryEntry(String provider, String model, String baseUrl, String prevProvider, String prevModel,
            String changedBy, Instant changedAt) {
    }

    /** Empty when no admin has saved settings yet (or the stored provider is no longer supported). */
    public Optional<LlmSettings> load() {
        return jdbcTemplate.query("SELECT * FROM ai_llm_settings WHERE id = 1", (rs, n) -> {
                    String model = rs.getString("model");
                    String baseUrl = rs.getString("base_url");
                    String updatedBy = rs.getString("updated_by");
                    Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
                    return LlmProvider.fromId(rs.getString("provider"))
                            .map(p -> new LlmSettings(p, model, baseUrl, updatedBy, updatedAt));
                })
                .stream().flatMap(Optional::stream).findFirst();
    }

    @Transactional
    public void save(LlmSettings settings, LlmSettings previous) {
        jdbcTemplate.update(
                """
                INSERT INTO ai_llm_settings (id, provider, model, base_url, updated_by, updated_at)
                VALUES (1, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET provider = EXCLUDED.provider, model = EXCLUDED.model,
                    base_url = EXCLUDED.base_url, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """,
                settings.provider().id(), settings.model(), settings.baseUrl(), settings.updatedBy(),
                Timestamp.from(settings.updatedAt()));
        jdbcTemplate.update(
                """
                INSERT INTO ai_llm_settings_audit
                    (provider, model, base_url, prev_provider, prev_model, changed_by, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                settings.provider().id(), settings.model(), settings.baseUrl(),
                previous == null ? null : previous.provider().id(), previous == null ? null : previous.model(),
                settings.updatedBy(), Timestamp.from(settings.updatedAt()));
    }

    public List<HistoryEntry> history(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM ai_llm_settings_audit ORDER BY changed_at DESC, id DESC LIMIT ?",
                (rs, n) -> new HistoryEntry(rs.getString("provider"), rs.getString("model"), rs.getString("base_url"),
                        rs.getString("prev_provider"), rs.getString("prev_model"), rs.getString("changed_by"),
                        rs.getTimestamp("changed_at").toInstant()),
                limit);
    }
}
