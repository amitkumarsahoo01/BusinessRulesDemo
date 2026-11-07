package com.dmnrules.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Holds the active LLM and swaps it when an admin saves new settings — no restart, and requests
 * already streaming keep the client they started with. Before any admin change the provider comes
 * from {@code AI_PROVIDER}/{@code AI_MODEL}; after one, from {@code ai_llm_settings}.
 */
@Service
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);
    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._:/@-]{1,200}");
    // Generous: a self-hosted model's first request includes loading it into memory (~30s seen locally).
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    private record Active(LlmSettings settings, ChatClient client) {
    }

    private final LlmClientFactory factory;
    private final LlmSettingsRepository repository;
    private final Clock clock;
    private final AtomicReference<Active> active = new AtomicReference<>();

    @Autowired
    public LlmRouter(LlmClientFactory factory, LlmSettingsRepository repository,
            @Value("${rules-ai.provider:anthropic}") String envProvider,
            @Value("${rules-ai.model:}") String envModel) {
        this(factory, repository, envProvider, envModel, Clock.systemUTC());
    }

    LlmRouter(LlmClientFactory factory, LlmSettingsRepository repository, String envProvider, String envModel,
            Clock clock) {
        this.factory = factory;
        this.repository = repository;
        this.clock = clock;
        LlmSettings initial = repository.load().orElseGet(() -> envDefaults(envProvider, envModel));
        active.set(new Active(initial, factory.create(initial)));
        log.info("LLM provider: {} / {}", initial.provider().id(), initial.model());
    }

    static LlmSettings envDefaults(String envProvider, String envModel) {
        LlmProvider provider = LlmProvider.fromId(envProvider).orElseGet(() -> {
            if (envProvider != null && !envProvider.isBlank()) {
                log.warn("Unknown AI_PROVIDER '{}', falling back to anthropic", envProvider);
            }
            return LlmProvider.ANTHROPIC;
        });
        String model = envModel == null || envModel.isBlank() ? provider.defaultModel() : envModel.trim();
        return new LlmSettings(provider, model, null, null, null);
    }

    public ChatClient chatClient() {
        return active.get().client();
    }

    public LlmSettings current() {
        return active.get().settings();
    }

    public LlmClientFactory factory() {
        return factory;
    }

    /** Validates, persists (with an audit row) and activates new settings. */
    public synchronized LlmSettings update(LlmProvider provider, String model, String baseUrl, String actor) {
        LlmSettings candidate = validate(provider, model, baseUrl).withAudit(actor, clock.instant());
        ChatClient client = factory.create(candidate);
        LlmSettings previous = current();
        repository.save(candidate, previous);
        active.set(new Active(candidate, client));
        log.info("LLM provider changed by {}: {} / {} -> {} / {}", actor, previous.provider().id(), previous.model(),
                candidate.provider().id(), candidate.model());
        return candidate;
    }

    public record TestResult(boolean ok, long latencyMs, String reply, String error) {
    }

    /** Sends one tiny prompt with candidate settings, without activating them. */
    public TestResult test(LlmProvider provider, String model, String baseUrl) {
        LlmSettings candidate = validate(provider, model, baseUrl);
        long start = System.nanoTime();
        try {
            String reply = CompletableFuture
                    .supplyAsync(() -> factory.create(candidate).prompt()
                            .user("Reply with the single word OK.").call().content())
                    .get(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return new TestResult(true, elapsedMs(start), truncate(reply), null);
        } catch (TimeoutException e) {
            return new TestResult(false, elapsedMs(start), null, "No response within " + TEST_TIMEOUT.toSeconds() + "s");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new TestResult(false, elapsedMs(start), null, truncate(cause.getMessage()));
        }
    }

    LlmSettings validate(LlmProvider provider, String model, String baseUrl) {
        if (provider == null) {
            throw new InvalidLlmSettingsException("Choose a provider.");
        }
        String trimmedModel = model == null ? "" : model.trim();
        if (!MODEL_NAME.matcher(trimmedModel).matches()) {
            throw new InvalidLlmSettingsException("Model name is required and may only contain letters, digits and . _ : / @ -");
        }
        if (provider.requiresApiKey() && !factory.isKeyConfigured(provider)) {
            throw new InvalidLlmSettingsException("The API key for " + provider.label()
                    + " is not configured on the server. Set it as an environment variable and restart ai-service.");
        }
        String normalizedUrl = null;
        if (baseUrl != null && !baseUrl.isBlank()) {
            if (!provider.supportsBaseUrl()) {
                throw new InvalidLlmSettingsException(provider.label() + " does not take a custom base URL.");
            }
            normalizedUrl = baseUrl.trim().replaceAll("/+$", "");
            try {
                URI uri = URI.create(normalizedUrl);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new InvalidLlmSettingsException("Base URL must be an http(s) URL, e.g. http://ollama:11434");
            }
        }
        return new LlmSettings(provider, trimmedModel, normalizedUrl, null, null);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
