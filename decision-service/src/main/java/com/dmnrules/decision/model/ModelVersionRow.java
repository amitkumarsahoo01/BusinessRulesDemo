package com.dmnrules.decision.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ModelVersionRow(
        UUID id,
        String modelKey,
        String version,
        String status,
        String dmnXml,
        String xmlSha256,
        String changeNote,
        String authoredBy,
        String approvedBy,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt) {
}
