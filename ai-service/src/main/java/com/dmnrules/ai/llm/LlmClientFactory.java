package com.dmnrules.ai.llm;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link ChatClient} for a given provider/model/base URL. API keys are read from env vars
 * only (CLAUDE.md rule 8) and never leave this class: the admin API reports whether a key is
 * configured, not its value.
 */
@Component
public class LlmClientFactory {

    private final String anthropicApiKey;
    private final String openAiApiKey;
    private final String ollamaDefaultBaseUrl;

    public LlmClientFactory(
            @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey,
            @Value("${rules-ai.providers.openai.api-key:}") String openAiApiKey,
            @Value("${rules-ai.providers.ollama.base-url:http://localhost:11434}") String ollamaDefaultBaseUrl) {
        this.anthropicApiKey = anthropicApiKey;
        this.openAiApiKey = openAiApiKey;
        this.ollamaDefaultBaseUrl = ollamaDefaultBaseUrl;
    }

    public boolean isKeyConfigured(LlmProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> notBlank(anthropicApiKey);
            case OPENAI -> notBlank(openAiApiKey);
            case OLLAMA -> true;
        };
    }

    /** What an empty base URL means for this provider (shown as a placeholder in the console). */
    public String defaultBaseUrl(LlmProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> "https://api.anthropic.com";
            case OPENAI -> "https://api.openai.com";
            case OLLAMA -> ollamaDefaultBaseUrl;
        };
    }

    public ChatClient create(LlmSettings settings) {
        return ChatClient.create(chatModel(settings));
    }

    ChatModel chatModel(LlmSettings settings) {
        String baseUrl = notBlank(settings.baseUrl()) ? settings.baseUrl() : defaultBaseUrl(settings.provider());
        return switch (settings.provider()) {
            case ANTHROPIC -> AnthropicChatModel.builder()
                    .anthropicApi(AnthropicApi.builder().baseUrl(baseUrl).apiKey(keyOrPlaceholder(anthropicApiKey)).build())
                    .defaultOptions(AnthropicChatOptions.builder().model(settings.model()).maxTokens(4096).build())
                    .build();
            case OPENAI -> OpenAiChatModel.builder()
                    .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(keyOrPlaceholder(openAiApiKey)).build())
                    .defaultOptions(OpenAiChatOptions.builder().model(settings.model()).build())
                    .build();
            case OLLAMA -> OllamaChatModel.builder()
                    .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
                    .defaultOptions(OllamaChatOptions.builder().model(settings.model()).build())
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
