package com.dmnrules.ai.llm;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmRouterTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    private final LlmSettingsRepository repository = mock(LlmSettingsRepository.class);

    private LlmRouter router(String anthropicKey, String openAiKey) {
        return new LlmRouter(new LlmClientFactory(anthropicKey, openAiKey, "http://ollama:11434"), repository,
                "anthropic", "", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startsFromEnvDefaultsWhenNothingIsStored() {
        when(repository.load()).thenReturn(Optional.empty());
        LlmRouter router = router("key", "");

        assertThat(router.current().provider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(router.current().model()).isEqualTo(LlmProvider.ANTHROPIC.defaultModel());
        assertThat(router.chatClient()).isNotNull();
    }

    @Test
    void storedSettingsWinOverEnvDefaults() {
        when(repository.load()).thenReturn(Optional.of(
                new LlmSettings(LlmProvider.OLLAMA, "qwen2.5", "http://gpu-box:11434", "admin", NOW)));
        assertThat(router("key", "").current().provider()).isEqualTo(LlmProvider.OLLAMA);
    }

    @Test
    void envDefaultsFallBackToAnthropicForUnknownProvider() {
        assertThat(LlmRouter.envDefaults("watsonx", "").provider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(LlmRouter.envDefaults("OpenAI", "gpt-4o").model()).isEqualTo("gpt-4o");
    }

    @Test
    void updateSwapsTheActiveClientAndAuditsTheChange() {
        when(repository.load()).thenReturn(Optional.empty());
        LlmRouter router = router("key", "");
        var before = router.chatClient();

        router.update(LlmProvider.OLLAMA, " llama3.1 ", "http://ollama:11434/", "admin");

        assertThat(router.current().provider()).isEqualTo(LlmProvider.OLLAMA);
        assertThat(router.current().model()).isEqualTo("llama3.1");
        assertThat(router.current().baseUrl()).isEqualTo("http://ollama:11434");
        assertThat(router.current().updatedBy()).isEqualTo("admin");
        assertThat(router.chatClient()).isNotSameAs(before);

        ArgumentCaptor<LlmSettings> previous = ArgumentCaptor.forClass(LlmSettings.class);
        verify(repository).save(any(LlmSettings.class), previous.capture());
        assertThat(previous.getValue().provider()).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void rejectsProviderWhoseKeyIsNotConfiguredAndKeepsCurrentOne() {
        when(repository.load()).thenReturn(Optional.empty());
        LlmRouter router = router("key", "");

        assertThatThrownBy(() -> router.update(LlmProvider.OPENAI, "gpt-4o", null, "admin"))
                .isInstanceOf(InvalidLlmSettingsException.class)
                .hasMessageContaining("not configured");
        assertThat(router.current().provider()).isEqualTo(LlmProvider.ANTHROPIC);
        verify(repository, never()).save(any(), any());
    }

    @Test
    void rejectsBadModelNamesAndBaseUrls() {
        when(repository.load()).thenReturn(Optional.empty());
        LlmRouter router = router("key", "key");

        assertThatThrownBy(() -> router.validate(LlmProvider.OPENAI, "", null))
                .isInstanceOf(InvalidLlmSettingsException.class);
        assertThatThrownBy(() -> router.validate(LlmProvider.OPENAI, "gpt 4o; drop table", null))
                .isInstanceOf(InvalidLlmSettingsException.class);
        assertThatThrownBy(() -> router.validate(LlmProvider.OLLAMA, "llama3.1", "file:///etc/passwd"))
                .isInstanceOf(InvalidLlmSettingsException.class);
        assertThatThrownBy(() -> router.validate(LlmProvider.ANTHROPIC, "claude-sonnet-5-5", "http://proxy"))
                .isInstanceOf(InvalidLlmSettingsException.class)
                .hasMessageContaining("does not take a custom base URL");
        assertThat(router.validate(LlmProvider.OPENAI, "gpt-4o", "https://my-gateway.example.com/").baseUrl())
                .isEqualTo("https://my-gateway.example.com");
    }

    @Test
    void connectionTestReportsFailureInsteadOfThrowing() {
        when(repository.load()).thenReturn(Optional.empty());
        LlmRouter router = router("key", "");

        // Nothing listens on port 9 (discard) of localhost: connection refused, reported as a result.
        LlmRouter.TestResult result = router.test(LlmProvider.OLLAMA, "llama3.1", "http://127.0.0.1:9");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(router.current().provider()).isEqualTo(LlmProvider.ANTHROPIC);
    }
}
