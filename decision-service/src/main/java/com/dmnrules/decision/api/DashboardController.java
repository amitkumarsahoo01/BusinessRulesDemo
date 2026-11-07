package com.dmnrules.decision.api;

import com.dmnrules.decision.dashboard.DashboardService;
import com.dmnrules.decision.dashboard.ExecutionStatsDto;
import com.dmnrules.decision.dashboard.RuleSummaryDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only aggregates for the dashboard page. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/executions")
    public ExecutionStatsDto executions(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String modelKey) {
        return dashboardService.executionStats(days, modelKey);
    }

    @GetMapping("/rules")
    public List<RuleSummaryDto> rules(@RequestParam(defaultValue = "30") int days) {
        return dashboardService.ruleSummaries(days);
    }
}
