package com.dmnrules.ai.llm;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The chat providers the admin console can switch between. Model lists are suggestions only. */
public enum LlmProvider {

    ANTHROPIC("anthropic", "Anthropic Claude", true, false, "claude-sonnet-4-5-20250929",
            List.of("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-sonnet-4-5-20250929")),
    OPENAI("openai", "OpenAI (or compatible)", true, true, "gpt-4.1-mini",
            List.of("gpt-4.1", "gpt-4.1-mini", "gpt-4o", "gpt-4o-mini")),
    OLLAMA("ollama", "Ollama (self-hosted)", false, true, "llama3.1",
            List.of("llama3.1", "qwen2.5", "mistral-nemo"));

    private final String id;
    private final String label;
    private final boolean requiresApiKey;
    private final boolean supportsBaseUrl;
    private final String defaultModel;
    private final List<String> suggestedModels;

    LlmProvider(String id, String label, boolean requiresApiKey, boolean supportsBaseUrl, String defaultModel,
            List<String> suggestedModels) {
        this.id = id;
        this.label = label;
        this.requiresApiKey = requiresApiKey;
        this.supportsBaseUrl = supportsBaseUrl;
        this.defaultModel = defaultModel;
        this.suggestedModels = suggestedModels;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    public boolean supportsBaseUrl() {
        return supportsBaseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public List<String> suggestedModels() {
        return suggestedModels;
    }

    public static Optional<LlmProvider> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.id.equals(normalized)).findFirst();
    }
}
