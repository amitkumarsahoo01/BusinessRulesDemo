package com.dmnrules.decision.api;

import com.dmnrules.decision.audit.ExecutionDetail;
import com.dmnrules.decision.audit.ExecutionNotFoundException;
import com.dmnrules.decision.audit.ExecutionRepository;
import com.dmnrules.decision.audit.ExecutionSearchFilter;
import com.dmnrules.decision.audit.ExecutionSummary;
import com.dmnrules.decision.audit.PageResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final ExecutionRepository executionRepository;

    public ExecutionController(ExecutionRepository executionRepository) {
        this.executionRepository = executionRepository;
    }

    @GetMapping
    public PageResult<ExecutionSummary> search(
            @RequestParam(required = false) String modelKey,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String caller,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return executionRepository.search(
                new ExecutionSearchFilter(modelKey, version, caller, status, from, to, page, size));
    }

    @GetMapping("/{id}")
    public ExecutionDetail get(@PathVariable UUID id) {
        return executionRepository.findById(id).orElseThrow(() -> new ExecutionNotFoundException(id));
    }
}
