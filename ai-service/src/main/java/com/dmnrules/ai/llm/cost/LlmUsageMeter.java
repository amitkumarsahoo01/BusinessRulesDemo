package com.dmnrules.ai.llm.cost;

import com.dmnrules.ai.llm.LlmProvider;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Meters every model call through Spring AI's observation hook. The chat models emit one observation
 * per request to the provider, including each internal tool-calling round, and that is exactly what
 * the provider bills. When a provider returns no token counts, they are estimated from text length
 * (about 4 characters per token) and the row is flagged as estimated.
 */
@Component
public class LlmUsageMeter {

    private final LlmBudgetService budget;

    public LlmUsageMeter(LlmBudgetService budget) {
        this.budget = budget;
    }

    /** A registry for one chat model; its calls are recorded against {@code provider}/{@code model}. */
    public ObservationRegistry registryFor(LlmProvider provider, String model) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new Handler(provider, model));
        return registry;
    }

    record Counts(int input, int output, boolean estimated) {
    }

    static Counts counts(ChatModelObservationContext context) {
        ChatResponse response = context.getResponse();
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        if (in > 0 || out > 0) {
            return new Counts(in, out, false);
        }
        return new Counts(estimate(promptChars(context.getRequest())), estimate(responseChars(response)), true);
    }

    private static int promptChars(Prompt prompt) {
        if (prompt == null) {
            return 0;
        }
        return prompt.getInstructions().stream().map(Message::getText).mapToInt(t -> t == null ? 0 : t.length()).sum();
    }

    private static int responseChars(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return 0;
        }
        return response.getResults().stream().map(Generation::getOutput)
                .mapToInt(m -> m == null || m.getText() == null ? 0 : m.getText().length()).sum();
    }

    private static int estimate(int chars) {
        return chars == 0 ? 0 : (chars + 3) / 4;
    }

    private final class Handler implements ObservationHandler<ChatModelObservationContext> {

        private final LlmProvider provider;
        private final String model;

        Handler(LlmProvider provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ChatModelObservationContext;
        }

        @Override
        public void onStop(ChatModelObservationContext context) {
            // A failed request (bad key, timeout, refused connection) produced nothing billable.
            if (context.getError() != null && context.getResponse() == null) {
                return;
            }
            Counts c = counts(context);
            if (c.input() > 0 || c.output() > 0) {
                budget.record(provider, model, c.input(), c.output(), c.estimated());
            }
        }
    }
}
