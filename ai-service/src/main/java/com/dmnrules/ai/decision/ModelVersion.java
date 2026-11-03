package com.dmnrules.ai.decision;

public record ModelVersion(
        String id, String modelKey, String version, String status, String dmnXml, String xmlSha256,
        String changeNote, String authoredBy, String approvedBy, String createdAt, String publishedAt) {
}
