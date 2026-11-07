package com.dmnrules.decision.dashboard;

import com.dmnrules.decision.author.InputSummaryDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One model at a glance for the dashboard: what is live, what is in flight, what it decides and
 * how much it is used. Structure comes from the live (latest published) version, or from the
 * newest version when nothing has been published yet ({@code liveVersion} is then null).
 */
public record RuleSummaryDto(
        String modelKey,
        String name,
        String owner,
        String liveVersion,
        OffsetDateTime publishedAt,
        String approvedBy,
        String changeNote,
        String inProgressVersion,
        String inProgressStatus,
        int versionCount,
        List<InputSummaryDto> inputs,
        List<DecisionCard> decisions,
        long executionsInPeriod,
        long failedInPeriod,
        Instant lastExecutedAt) {

    /** {@code hitPolicy}/{@code ruleCount} are null for literal-expression decisions. */
    public record DecisionCard(
            String name, String kind, String hitPolicy, Integer ruleCount, String outputType, List<String> requires) {
    }
}
