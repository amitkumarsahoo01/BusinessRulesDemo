package com.dmnrules.decision.model;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ModelVersionRepository {

    private static final RowMapper<ModelVersionRow> ROW_MAPPER = (rs, rowNum) -> new ModelVersionRow(
            UUID.fromString(rs.getString("id")),
            rs.getString("model_key"),
            rs.getString("version"),
            rs.getString("status"),
            rs.getString("dmn_xml"),
            rs.getString("xml_sha256"),
            rs.getString("change_note"),
            rs.getString("authored_by"),
            rs.getString("approved_by"),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("published_at")));

    private final JdbcTemplate jdbcTemplate;

    public ModelVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    public List<ModelVersionRow> findByStatus(String status) {
        return jdbcTemplate.query(
                "SELECT * FROM dmn_model_version WHERE status = ? ORDER BY model_key, version",
                ROW_MAPPER, status);
    }

    public Optional<ModelVersionRow> findByKeyAndVersion(String modelKey, String version) {
        List<ModelVersionRow> rows = jdbcTemplate.query(
                "SELECT * FROM dmn_model_version WHERE model_key = ? AND version = ?",
                ROW_MAPPER, modelKey, version);
        return rows.stream().findFirst();
    }

    public long countModels() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM dmn_model", Long.class);
        return count == null ? 0 : count;
    }

    public void insertModel(String modelKey, String name, String namespace, String owner) {
        jdbcTemplate.update(
                "INSERT INTO dmn_model (model_key, name, namespace, owner) VALUES (?, ?, ?, ?)",
                modelKey, name, namespace, owner);
    }

    public void insertVersion(ModelVersionRow row) {
        jdbcTemplate.update(
                """
                INSERT INTO dmn_model_version
                    (id, model_key, version, status, dmn_xml, xml_sha256, change_note, authored_by, approved_by, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(), row.modelKey(), row.version(), row.status(), row.dmnXml(), row.xmlSha256(),
                row.changeNote(), row.authoredBy(), row.approvedBy(),
                row.publishedAt() == null ? null : Timestamp.from(row.publishedAt().toInstant()));
    }

    public Optional<ModelVersionRow> findById(UUID id) {
        return jdbcTemplate.query("SELECT * FROM dmn_model_version WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    public List<ModelVersionRow> findAllByModelKey(String modelKey) {
        return jdbcTemplate.query(
                "SELECT * FROM dmn_model_version WHERE model_key = ? ORDER BY created_at",
                ROW_MAPPER, modelKey);
    }

    /** The one mutable, in-progress version for a model (DRAFT/VALIDATED/TESTED/APPROVED), if any. */
    public Optional<ModelVersionRow> findCurrentInProgress(String modelKey) {
        return jdbcTemplate.query(
                        """
                        SELECT * FROM dmn_model_version
                        WHERE model_key = ? AND status IN ('DRAFT', 'VALIDATED', 'TESTED', 'APPROVED')
                        ORDER BY created_at DESC LIMIT 1
                        """,
                        ROW_MAPPER, modelKey)
                .stream().findFirst();
    }

    public Optional<ModelVersionRow> findLatestPublished(String modelKey) {
        return jdbcTemplate.query(
                        """
                        SELECT * FROM dmn_model_version
                        WHERE model_key = ? AND status = 'PUBLISHED'
                        ORDER BY published_at DESC LIMIT 1
                        """,
                        ROW_MAPPER, modelKey)
                .stream().findFirst();
    }

    public List<ModelRow> findAllModels() {
        return jdbcTemplate.query(
                "SELECT model_key, name, namespace, owner FROM dmn_model ORDER BY model_key",
                (rs, n) -> new ModelRow(
                        rs.getString("model_key"), rs.getString("name"),
                        rs.getString("namespace"), rs.getString("owner")));
    }

    public void updateDraftContent(UUID id, String dmnXml, String xmlSha256, String changeNote) {
        jdbcTemplate.update(
                "UPDATE dmn_model_version SET dmn_xml = ?, xml_sha256 = ?, change_note = ?, status = 'DRAFT' WHERE id = ?",
                dmnXml, xmlSha256, changeNote, id);
    }

    public void updateStatus(UUID id, String status) {
        jdbcTemplate.update("UPDATE dmn_model_version SET status = ? WHERE id = ?", status, id);
    }

    public void approve(UUID id, String approvedBy) {
        jdbcTemplate.update(
                "UPDATE dmn_model_version SET status = 'APPROVED', approved_by = ? WHERE id = ?",
                approvedBy, id);
    }

    public void publish(UUID id, OffsetDateTime publishedAt) {
        jdbcTemplate.update(
                "UPDATE dmn_model_version SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                Timestamp.from(publishedAt.toInstant()), id);
    }
}
