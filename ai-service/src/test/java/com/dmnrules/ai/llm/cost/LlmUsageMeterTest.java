package com.dmnrules.ai.llm.cost;

import com.dmnrules.ai.llm.LlmProvider;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LlmUsageMeterTest {

    private final LlmBudgetService budget = mock(LlmBudgetService.class);
    private final LlmUsageMeter meter = new LlmUsageMeter(budget);

    private static ChatModelObservationContext context(String promptText, ChatResponse response) {
        ChatModelObservationContext ctx = ChatModelObservationContext.builder()
                .prompt(new Prompt(promptText)).provider("openai").build();
        if (response != null) {
            ctx.setResponse(response);
        }
        return ctx;
    }

    private static ChatResponse response(String text, DefaultUsage usage) {
        ChatResponseMetadata.Builder meta = ChatResponseMetadata.builder();
        if (usage != null) {
            meta.usage(usage);
        }
        return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(meta.build()).build();
    }

    private void observe(ObservationRegistry registry, ChatModelObservationContext ctx, Throwable error) {
        Observation observation = Observation.createNotStarted("gen_ai.client.operation", () -> ctx, registry).start();
        if (error != null) {
            observation.error(error);
        }
        observation.stop();
    }

    @Test
    void recordsReportedTokenCountsForEveryCall() {
        ObservationRegistry registry = meter.registryFor(LlmProvider.GEMINI, "gemini-2.5-flash");
        ChatResponse reply = response("hello", new DefaultUsage(1200, 80));

        observe(registry, context("q", reply), null);
        observe(registry, context("q2", reply), null);   // e.g. a second tool round

        verify(budget, org.mockito.Mockito.times(2)).record(LlmProvider.GEMINI, "gemini-2.5-flash", 1200, 80, false);
    }

    @Test
    void estimatesWhenTheProviderReportsNoUsage() {
        LlmUsageMeter.Counts counts = LlmUsageMeter.counts(context("x".repeat(400), response("y".repeat(40), null)));
        assertThat(counts).isEqualTo(new LlmUsageMeter.Counts(100, 10, true));
    }

    @Test
    void failedCallsAreNotCharged() {
        ObservationRegistry registry = meter.registryFor(LlmProvider.OPENAI, "gpt-4.1-mini");
        observe(registry, context("q", null), new RuntimeException("401 Unauthorized"));
        verify(budget, never()).record(any(), anyString(), anyInt(), anyInt(), anyBoolean());
    }
}
