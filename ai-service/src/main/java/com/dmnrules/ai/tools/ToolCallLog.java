package com.dmnrules.ai.tools;

import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

/**
 * Records which tools ran during a chat turn, for the "How I got this" transparency panel.
 * Carried as a shared mutable list inside {@link ToolContext} (set once per request via
 * {@code ChatClient.ChatClientRequestSpec#toolContext}) rather than a ThreadLocal: Spring AI's
 * streaming tool-execution loop isn't guaranteed to stay on the thread that started the request,
 * so a ThreadLocal (the pattern used for DMN trace capture) would silently drop entries here.
 */
public final class ToolCallLog {

    public static final String CONTEXT_KEY = "toolCallLog";

    private ToolCallLog() {
    }

    public record Entry(String tool, String argsSummary) {
    }

    @SuppressWarnings("unchecked")
    public static void record(ToolContext toolContext, String tool, String argsSummary) {
        if (toolContext == null) {
            return;
        }
        Object log = toolContext.getContext().get(CONTEXT_KEY);
        if (log instanceof List<?> list) {
            ((List<Entry>) list).add(new Entry(tool, argsSummary));
        }
    }
}
