package com.dmnrules.ai.api;

import com.dmnrules.ai.admin.AdminAuthorizer;
import com.dmnrules.ai.admin.AdminInterceptor;
import com.dmnrules.ai.llm.cost.LlmBudgetService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Admin console: what the LLMs have cost this month, the monthly budget, and the per-model prices
 * used to compute cost. Admin-only through {@link AdminInterceptor} like the rest of /api/ai/admin.
 */
@RestController
@RequestMapping("/api/ai/admin/llm/budget")
public class AdminBudgetController {

    private final LlmBudgetService budget;

    public AdminBudgetController(LlmBudgetService budget) {
        this.budget = budget;
    }

    public record BudgetRequest(BigDecimal monthlyLimitUsd, Integer warnPercent, Boolean hardStop) {
    }

    public record PriceRequest(String provider, String modelPrefix, BigDecimal inputPerMtok, BigDecimal outputPerMtok) {
    }

    @GetMapping
    public LlmBudgetService.Status get() {
        return budget.status();
    }

    @PutMapping
    public LlmBudgetService.Status update(@RequestBody BudgetRequest request,
            @RequestAttribute(AdminInterceptor.ADMIN_ATTRIBUTE) AdminAuthorizer.VerifiedUser admin) {
        budget.update(request.monthlyLimitUsd(), request.warnPercent(), request.hardStop(), admin.username());
        return budget.status();
    }

    @PutMapping("/prices")
    public LlmBudgetService.Status upsertPrice(@RequestBody PriceRequest request,
            @RequestAttribute(AdminInterceptor.ADMIN_ATTRIBUTE) AdminAuthorizer.VerifiedUser admin) {
        budget.upsertPrice(request.provider(), request.modelPrefix(), request.inputPerMtok(), request.outputPerMtok(),
                admin.username());
        return budget.status();
    }

    @DeleteMapping("/prices")
    public LlmBudgetService.Status deletePrice(@RequestParam String provider, @RequestParam String modelPrefix) {
        budget.deletePrice(provider, modelPrefix);
        return budget.status();
    }
}
