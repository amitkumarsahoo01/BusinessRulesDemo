package com.dmnrules.ai.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public HealthStatus health() {
        return new HealthStatus("UP", "ai-service");
    }
}
