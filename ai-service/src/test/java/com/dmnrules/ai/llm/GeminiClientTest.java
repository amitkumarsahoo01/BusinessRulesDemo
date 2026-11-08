package com.dmnrules.ai.llm;

import com.dmnrules.ai.llm.cost.LlmBudgetService;
import com.dmnrules.ai.llm.cost.LlmUsageMeter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Gemini goes through Google's OpenAI-compatible API: requests must hit {@code <base>/chat/completions}
 * (no {@code /v1}), carry the Gemini key as a bearer token, and have their token usage metered.
 * A local stub stands in for generativelanguage.googleapis.com.
 */
class GeminiClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void callsTheOpenAiCompatibleEndpointWithTheGeminiKeyAndMetersUsage() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = """
                    {"id":"r1","object":"chat.completion","created":1,"model":"gemini-2.5-flash",
                     "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}],
                     "usage":{"prompt_tokens":42,"completion_tokens":3,"total_tokens":45}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/openai";

        LlmBudgetService budget = mock(LlmBudgetService.class);
        LlmClientFactory factory = new LlmClientFactory("", "", "gemini-test-key", "http://ollama:11434",
                new LlmUsageMeter(budget)::registryFor);

        String reply = factory.create(new LlmSettings(LlmProvider.GEMINI, "gemini-2.5-flash", base, null, null))
                .prompt().user("Reply with OK").call().content();

        assertThat(reply).isEqualTo("OK");
        assertThat(path.get()).isEqualTo("/v1beta/openai/chat/completions");
        assertThat(auth.get()).isEqualTo("Bearer gemini-test-key");
        assertThat(body.get()).contains("\"model\":\"gemini-2.5-flash\"").contains("\"max_tokens\":4096");
        verify(budget).record(LlmProvider.GEMINI, "gemini-2.5-flash", 42, 3, false);
        assertThat(factory.isKeyConfigured(LlmProvider.GEMINI)).isTrue();
        assertThat(factory.defaultBaseUrl(LlmProvider.GEMINI))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
    }
}
