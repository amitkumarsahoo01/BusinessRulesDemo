package com.dmnrules.decision.dashboard;

import com.dmnrules.decision.author.DecisionNodeDto;
import com.dmnrules.decision.author.DecisionTableDto;
import com.dmnrules.decision.author.DmnXmlEditor;
import com.dmnrules.decision.author.ModelStructureDto;
import com.dmnrules.decision.dashboard.ExecutionStatsDto.DailyBucket;
import com.dmnrules.decision.dashboard.ExecutionStatsDto.ModelBreakdown;
import com.dmnrules.decision.dashboard.ExecutionStatsDto.OutcomeBreakdown;
import com.dmnrules.decision.dashboard.ExecutionStatsDto.OutcomeCount;
import com.dmnrules.decision.model.ModelRow;
import com.dmnrules.decision.model.ModelVersionRepository;
import com.dmnrules.decision.model.ModelVersionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    /** Outputs with more distinct values than this are numbers-in-disguise or IDs, not outcomes. */
    static final int MAX_OUTCOME_CARDINALITY = 8;
    public static final int MAX_DAYS = 365;

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final DashboardRepository repository;
    private final ModelVersionRepository versionRepository;
    private final DmnXmlEditor xmlEditor;
    private final Clock clock;

    @Autowired
    public DashboardService(DashboardRepository repository, ModelVersionRepository versionRepository,
            DmnXmlEditor xmlEditor) {
        this(repository, versionRepository, xmlEditor, Clock.systemUTC());
    }

    DashboardService(DashboardRepository repository, ModelVersionRepository versionRepository,
            DmnXmlEditor xmlEditor, Clock clock) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.xmlEditor = xmlEditor;
        this.clock = clock;
    }

    /** [start of the first day, start of tomorrow) in UTC. */
    record Period(LocalDate firstDay, Instant from, Instant to) {
    }

    Period period(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS);
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate firstDay = today.minusDays(days - 1L);
        return new Period(firstDay, firstDay.atStartOfDay(ZoneOffset.UTC).toInstant(),
                today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    public ExecutionStatsDto executionStats(int days, String modelKey) {
        Period p = period(days);
        DashboardRepository.Totals totals = repository.totals(p.from(), p.to(), modelKey);
        Instant previousFrom = p.from().minus(Duration.ofDays(days));
        long previousTotal = repository.totals(previousFrom, p.from(), modelKey).total();

        List<DailyBucket> daily = fillDays(p.firstDay(), days, repository.dailyByModel(p.from(), p.to(), modelKey));

        List<ModelBreakdown> byModel = repository.activityByModel(p.from(), p.to(), modelKey).stream()
                .map(a -> new ModelBreakdown(a.modelKey(), a.total(), a.failed(), a.avgDurationMs()))
                .toList();

        return new ExecutionStatsDto(p.from(), p.to(), days, totals.total(), totals.total() - totals.failed(),
                totals.failed(), totals.avgDurationMs(), totals.p95DurationMs(), previousTotal, daily, byModel,
                outcomes(repository.stringOutcomes(p.from(), p.to(), modelKey)));
    }

    static List<DailyBucket> fillDays(LocalDate firstDay, int days, List<DashboardRepository.DayModelCount> rows) {
        Map<LocalDate, List<DashboardRepository.DayModelCount>> byDay = rows.stream()
                .collect(Collectors.groupingBy(DashboardRepository.DayModelCount::day));
        List<DailyBucket> buckets = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate day = firstDay.plusDays(i);
            List<DashboardRepository.DayModelCount> dayRows = byDay.getOrDefault(day, List.of());
            Map<String, Long> perModel = new TreeMap<>();
            long total = 0;
            long failed = 0;
            for (DashboardRepository.DayModelCount row : dayRows) {
                perModel.put(row.modelKey(), row.total());
                total += row.total();
                failed += row.failed();
            }
            buckets.add(new DailyBucket(day, total, failed, perModel));
        }
        return buckets;
    }

    static List<OutcomeBreakdown> outcomes(List<DashboardRepository.OutcomeRow> rows) {
        Map<String, List<DashboardRepository.OutcomeRow>> grouped = rows.stream().collect(Collectors.groupingBy(
                r -> r.modelKey() + "\u0000" + r.output(), LinkedHashMap::new, Collectors.toList()));
        return grouped.values().stream()
                .filter(values -> values.size() <= MAX_OUTCOME_CARDINALITY)
                .map(values -> new OutcomeBreakdown(values.get(0).modelKey(), values.get(0).output(),
                        values.stream()
                                .sorted(Comparator.comparingLong(DashboardRepository.OutcomeRow::count).reversed())
                                .map(v -> new OutcomeCount(v.value(), v.count()))
                                .toList()))
                .toList();
    }

    public List<RuleSummaryDto> ruleSummaries(int days) {
        Period p = period(days);
        Map<String, DashboardRepository.ModelActivity> activity = repository.activityByModel(p.from(), p.to(), null)
                .stream().collect(Collectors.toMap(DashboardRepository.ModelActivity::modelKey, Function.identity()));
        Map<String, Instant> lastExecuted = repository.lastExecutionPerModel().stream()
                .collect(Collectors.toMap(DashboardRepository.ModelActivity::modelKey,
                        DashboardRepository.ModelActivity::lastExecutedAt));

        return versionRepository.findAllModels().stream()
                .map(model -> summarize(model, activity.get(model.modelKey()), lastExecuted.get(model.modelKey())))
                .toList();
    }

    private RuleSummaryDto summarize(ModelRow model, DashboardRepository.ModelActivity activity, Instant lastExecutedAt) {
        List<ModelVersionRow> versions = versionRepository.findAllByModelKey(model.modelKey());
        ModelVersionRow live = versionRepository.findLatestPublished(model.modelKey()).orElse(null);
        ModelVersionRow inProgress = versionRepository.findCurrentInProgress(model.modelKey()).orElse(null);
        ModelVersionRow shown = live != null ? live
                : versions.stream().max(Comparator.comparing(ModelVersionRow::createdAt)).orElse(null);

        ModelStructureDto structure = new ModelStructureDto(List.of(), List.of());
        List<RuleSummaryDto.DecisionCard> decisions = List.of();
        if (shown != null) {
            try {
                structure = xmlEditor.readStructure(shown.dmnXml());
                decisions = structure.decisions().stream().map(d -> decisionCard(shown.dmnXml(), d)).toList();
            } catch (RuntimeException e) {
                log.warn("Could not read structure of {} {} for the dashboard", model.modelKey(), shown.version(), e);
            }
        }

        return new RuleSummaryDto(
                model.modelKey(), model.name(), model.owner(),
                live == null ? null : live.version(),
                live == null ? null : live.publishedAt(),
                live == null ? null : live.approvedBy(),
                shown == null ? null : shown.changeNote(),
                inProgress == null ? null : inProgress.version(),
                inProgress == null ? null : inProgress.status(),
                versions.size(), structure.inputs(), decisions,
                activity == null ? 0 : activity.total(),
                activity == null ? 0 : activity.failed(),
                lastExecutedAt);
    }

    private RuleSummaryDto.DecisionCard decisionCard(String dmnXml, DecisionNodeDto decision) {
        if (!"DECISION_TABLE".equals(decision.kind())) {
            return new RuleSummaryDto.DecisionCard(decision.name(), decision.kind(), null, null, null, decision.requires());
        }
        try {
            DecisionTableDto table = xmlEditor.readDecisionTable(dmnXml, decision.name());
            return new RuleSummaryDto.DecisionCard(decision.name(), decision.kind(), table.hitPolicy(),
                    table.rules().size(), table.output() == null ? null : table.output().typeRef(),
                    decision.requires());
        } catch (RuntimeException e) {
            return new RuleSummaryDto.DecisionCard(decision.name(), decision.kind(), null, null, null, decision.requires());
        }
    }
}
