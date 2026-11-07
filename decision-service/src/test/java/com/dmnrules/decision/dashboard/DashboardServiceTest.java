package com.dmnrules.decision.dashboard;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardServiceTest {

    @Test
    void periodCoversWholeUtcDaysIncludingToday() {
        DashboardService service = new DashboardService(null, null, null,
                Clock.fixed(Instant.parse("2026-11-07T15:30:00Z"), ZoneOffset.UTC));

        DashboardService.Period p = service.period(7);

        assertThat(p.firstDay()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(p.from()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(p.to()).isEqualTo(Instant.parse("2026-11-08T00:00:00Z"));
        assertThatThrownBy(() -> service.period(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.period(DashboardService.MAX_DAYS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fillDaysEmitsEveryDayAndSumsModels() {
        LocalDate first = LocalDate.of(2026, 11, 1);
        List<DashboardRepository.DayModelCount> rows = List.of(
                new DashboardRepository.DayModelCount(first, "a", 3, 1),
                new DashboardRepository.DayModelCount(first, "b", 2, 0),
                new DashboardRepository.DayModelCount(first.plusDays(2), "a", 5, 0));

        List<ExecutionStatsDto.DailyBucket> days = DashboardService.fillDays(first, 4, rows);

        assertThat(days).extracting(ExecutionStatsDto.DailyBucket::date)
                .containsExactly(first, first.plusDays(1), first.plusDays(2), first.plusDays(3));
        assertThat(days.get(0).total()).isEqualTo(5);
        assertThat(days.get(0).failed()).isEqualTo(1);
        assertThat(days.get(0).byModel()).isEqualTo(Map.of("a", 3L, "b", 2L));
        assertThat(days.get(1).total()).isZero();
        assertThat(days.get(1).byModel()).isEmpty();
        assertThat(days.get(2).total()).isEqualTo(5);
    }

    @Test
    void outcomesKeepLowCardinalityOutputsSortedByCount() {
        List<DashboardRepository.OutcomeRow> rows = new ArrayList<>(List.of(
                new DashboardRepository.OutcomeRow("loan", "Eligibility", "REVIEW", 4),
                new DashboardRepository.OutcomeRow("loan", "Eligibility", "APPROVED", 10),
                new DashboardRepository.OutcomeRow("loan", "Eligibility", "DECLINED", 2)));
        for (int i = 0; i <= DashboardService.MAX_OUTCOME_CARDINALITY; i++) {
            rows.add(new DashboardRepository.OutcomeRow("loan", "Reference", "ref-" + i, 1));
        }

        List<ExecutionStatsDto.OutcomeBreakdown> outcomes = DashboardService.outcomes(rows);

        assertThat(outcomes).singleElement().satisfies(o -> {
            assertThat(o.output()).isEqualTo("Eligibility");
            assertThat(o.values()).extracting(ExecutionStatsDto.OutcomeCount::value)
                    .containsExactly("APPROVED", "REVIEW", "DECLINED");
        });
    }
}
