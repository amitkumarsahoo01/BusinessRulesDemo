package com.dmnrules.ai.llm;

import com.dmnrules.ai.llm.cost.LlmUsageMeter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.BiFunction;

/**
 * Builds a {@link ChatClient} for a given provider/model/base URL. API keys are read from env vars
 * only (CLAUDE.md rule 8) and never leave this class: the admin API reports whether a key is
 * configured, not its value. Every model it builds is metered by {@link LlmUsageMeter}, so the
 * monthly budget sees each provider call, including tool-calling rounds.
 */
@Component
public class LlmClientFactory {

    static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    /** Per-call output cap on every provider, so one runaway reply cannot blow the budget. */
    static final int MAX_OUTPUT_TOKENS = 4096;

    private final String anthropicApiKey;
    private final String openAiApiKey;
    private final String geminiApiKey;
    private final String ollamaDefaultBaseUrl;
    private final BiFunction<LlmProvider, String, ObservationRegistry> observations;

    @Autowired
    public LlmClientFactory(
            @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey,
            @Value("${rules-ai.providers.openai.api-key:}") String openAiApiKey,
            @Value("${rules-ai.providers.gemini.api-key:}") String geminiApiKey,
            @Value("${rules-ai.providers.ollama.base-url:http://localhost:11434}") String ollamaDefaultBaseUrl,
            LlmUsageMeter meter) {
        this(anthropicApiKey, openAiApiKey, geminiApiKey, ollamaDefaultBaseUrl, meter::registryFor);
    }

    LlmClientFactory(String anthropicApiKey, String openAiApiKey, String geminiApiKey, String ollamaDefaultBaseUrl,
            BiFunction<LlmProvider, String, ObservationRegistry> observations) {
        this.anthropicApiKey = anthropicApiKey;
        this.openAiApiKey = openAiApiKey;
        this.geminiApiKey = geminiApiKey;
        this.ollamaDefaultBaseUrl = ollamaDefaultBaseUrl;
        this.observations = observations;
    }

    /** For tests: a factory whose models are not metered. */
    static LlmClientFactory unmetered(String anthropicApiKey, String openAiApiKey, String geminiApiKey,
            String ollamaDefaultBaseUrl) {
        return new LlmClientFactory(anthropicApiKey, openAiApiKey, geminiApiKey, ollamaDefaultBaseUrl,
                (p, m) -> ObservationRegistry.NOOP);
    }

    public boolean isKeyConfigured(LlmProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> notBlank(anthropicApiKey);
            case OPENAI -> notBlank(openAiApiKey);
            case GEMINI -> notBlank(geminiApiKey);
            case OLLAMA -> true;
        };
    }

    /** Name of the env var holding this provider's key (shown in the console), or null if none is needed. */
    public static String apiKeyEnvVar(LlmProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> "ANTHROPIC_API_KEY";
            case OPENAI -> "OPENAI_API_KEY";
            case GEMINI -> "GEMINI_API_KEY";
            case OLLAMA -> null;
        };
    }

    /** What an empty base URL means for this provider (shown as a placeholder in the console). */
    public String defaultBaseUrl(LlmProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> "https://api.anthropic.com";
            case OPENAI -> "https://api.openai.com";
            case GEMINI -> GEMINI_BASE_URL;
            case OLLAMA -> ollamaDefaultBaseUrl;
        };
    }

    public ChatClient create(LlmSettings settings) {
        return ChatClient.create(chatModel(settings));
    }

    ChatModel chatModel(LlmSettings settings) {
        String baseUrl = notBlank(settings.baseUrl()) ? settings.baseUrl() : defaultBaseUrl(settings.provider());
        ObservationRegistry registry = observations.apply(settings.provider(), settings.model());
        return switch (settings.provider()) {
            case ANTHROPIC -> AnthropicChatModel.builder()
                    .anthropicApi(AnthropicApi.builder().baseUrl(baseUrl).apiKey(keyOrPlaceholder(anthropicApiKey)).build())
                    .defaultOptions(AnthropicChatOptions.builder().model(settings.model()).maxTokens(MAX_OUTPUT_TOKENS).build())
                    .observationRegistry(registry)
                    .build();
            // streamUsage asks for token counts on streamed replies; without it OpenAI sends none.
            case OPENAI -> OpenAiChatModel.builder()
                    .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(keyOrPlaceholder(openAiApiKey)).build())
                    .defaultOptions(OpenAiChatOptions.builder().model(settings.model())
                            .maxTokens(MAX_OUTPUT_TOKENS).streamUsage(true).build())
                    .observationRegistry(registry)
                    .build();
            // Gemini's OpenAI-compatible API lives at .../v1beta/openai/chat/completions (no /v1 segment).
            case GEMINI -> OpenAiChatModel.builder()
                    .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).completionsPath("/chat/completions")
                            .apiKey(keyOrPlaceholder(geminiApiKey)).build())
                    .defaultOptions(OpenAiChatOptions.builder().model(settings.model())
                            .maxTokens(MAX_OUTPUT_TOKENS).streamUsage(true).build())
                    .observationRegistry(registry)
                    .build();
            case OLLAMA -> OllamaChatModel.builder()
                    .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
                    .defaultOptions(OllamaChatOptions.builder().model(settings.model()).build())
                    .observationRegistry(registry)
                    .build();
        };
    }

    /**
     * The API clients reject an empty key at construction. Starting without a key must still work
     * (everything but chat is usable), so a placeholder is used and the provider returns 401 on use.
     */
    private static String keyOrPlaceholder(String key) {
        return notBlank(key) ? key : "not-configured";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
