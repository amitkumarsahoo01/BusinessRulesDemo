package com.dmnrules.decision.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public HealthStatus health() {
        return new HealthStatus("UP", "decision-service");
    }
}
