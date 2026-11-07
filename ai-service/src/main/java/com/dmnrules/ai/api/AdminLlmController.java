package com.dmnrules.ai.api;

import com.dmnrules.ai.admin.AdminAuthorizer;
import com.dmnrules.ai.admin.AdminInterceptor;
import com.dmnrules.ai.llm.InvalidLlmSettingsException;
import com.dmnrules.ai.llm.LlmClientFactory;
import com.dmnrules.ai.llm.LlmProvider;
import com.dmnrules.ai.llm.LlmRouter;
import com.dmnrules.ai.llm.LlmSettings;
import com.dmnrules.ai.llm.LlmSettingsRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Admin console: view and change which LLM answers chat questions. Access is enforced for the whole
 * {@code /api/ai/admin/**} path by {@link AdminInterceptor}. API keys are never returned or
 * accepted — only whether each provider's key is configured on the server.
 */
@RestController
@RequestMapping("/api/ai/admin/llm")
public class AdminLlmController {

    private final LlmRouter router;
    private final LlmSettingsRepository repository;

    public AdminLlmController(LlmRouter router, LlmSettingsRepository repository) {
        this.router = router;
        this.repository = repository;
    }

    public record ProviderOption(String id, String label, boolean apiKeyConfigured, boolean requiresApiKey,
            boolean supportsBaseUrl, String defaultBaseUrl, String defaultModel, List<String> suggestedModels) {
    }

    public record CurrentSettings(String provider, String model, String baseUrl, String updatedBy, Instant updatedAt) {
    }

    public record LlmAdminView(CurrentSettings current, List<ProviderOption> providers,
            List<LlmSettingsRepository.HistoryEntry> history) {
    }

    public record LlmSettingsRequest(String provider, String model, String baseUrl) {
    }

    @GetMapping
    public LlmAdminView get() {
        return view();
    }

    @PutMapping
    public LlmAdminView update(@RequestBody LlmSettingsRequest request,
            @RequestAttribute(AdminInterceptor.ADMIN_ATTRIBUTE) AdminAuthorizer.VerifiedUser admin) {
        router.update(provider(request), request.model(), request.baseUrl(), admin.username());
        return view();
    }

    @PostMapping("/test")
    public LlmRouter.TestResult test(@RequestBody LlmSettingsRequest request) {
        return router.test(provider(request), request.model(), request.baseUrl());
    }

    private static LlmProvider provider(LlmSettingsRequest request) {
        return LlmProvider.fromId(request.provider())
                .orElseThrow(() -> new InvalidLlmSettingsException("Unknown provider: " + request.provider()));
    }

    private LlmAdminView view() {
        LlmSettings s = router.current();
        LlmClientFactory factory = router.factory();
        List<ProviderOption> providers = Arrays.stream(LlmProvider.values())
                .map(p -> new ProviderOption(p.id(), p.label(), factory.isKeyConfigured(p), p.requiresApiKey(),
                        p.supportsBaseUrl(), factory.defaultBaseUrl(p), p.defaultModel(), p.suggestedModels()))
                .toList();
        return new LlmAdminView(
                new CurrentSettings(s.provider().id(), s.model(), s.baseUrl(), s.updatedBy(), s.updatedAt()),
                providers, repository.history(20));
    }
}
