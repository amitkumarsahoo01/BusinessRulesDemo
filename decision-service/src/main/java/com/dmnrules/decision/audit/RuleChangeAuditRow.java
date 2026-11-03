package com.dmnrules.decision.audit;

import java.time.Instant;
import java.util.UUID;

public record RuleChangeAuditRow(
        long id, String modelKey, UUID versionId, String action, String actor, String diffJson, Instant at) {
}
