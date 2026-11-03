package com.dmnrules.decision.trace;

import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.ast.DecisionNode;
import org.kie.dmn.api.core.event.AfterEvaluateDecisionEvent;
import org.kie.dmn.api.core.event.AfterEvaluateDecisionTableEvent;
import org.kie.dmn.api.core.event.BeforeEvaluateDecisionEvent;
import org.kie.dmn.api.core.event.DMNRuntimeEventListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single instance is registered once per compiled model's {@code DMNRuntime} and never removed.
 * Per-evaluation state lives in thread locals because evaluation is synchronous on the calling
 * (request) thread, so concurrent requests on the same runtime never see each other's events.
 */
public class TraceCapturingListener implements DMNRuntimeEventListener {

    private static final ThreadLocal<List<TraceStepRecord>> STEPS = new ThreadLocal<>();
    private static final ThreadLocal<int[]> SEQ = new ThreadLocal<>();
    private static final ThreadLocal<Deque<Long>> DECISION_START = new ThreadLocal<>();

    public static void begin() {
        STEPS.set(new ArrayList<>());
        SEQ.set(new int[] {0});
        DECISION_START.set(new ArrayDeque<>());
    }

    public static List<TraceStepRecord> end() {
        List<TraceStepRecord> steps = STEPS.get();
        STEPS.remove();
        SEQ.remove();
        DECISION_START.remove();
        return steps == null ? List.of() : steps;
    }

    @Override
    public void beforeEvaluateDecision(BeforeEvaluateDecisionEvent event) {
        Deque<Long> starts = DECISION_START.get();
        if (starts != null) {
            starts.push(System.nanoTime());
        }
    }

    @Override
    public void afterEvaluateDecision(AfterEvaluateDecisionEvent event) {
        List<TraceStepRecord> steps = STEPS.get();
        if (steps == null) {
            return;
        }
        Deque<Long> starts = DECISION_START.get();
        Long start = starts != null && !starts.isEmpty() ? starts.pop() : null;
        Long durationMs = start == null ? null : (System.nanoTime() - start) / 1_000_000;

        DecisionNode decision = event.getDecision();
        DMNDecisionResult decisionResult = event.getResult().getDecisionResultByName(decision.getName());
        Object result = decisionResult == null ? null : decisionResult.getResult();

        Map<String, Object> inputsSnapshot = new LinkedHashMap<>(event.getResult().getContext().getAll());

        List<String> messages = event.getResult().getMessages().stream()
                .filter(m -> decision.getId().equals(m.getSourceId()))
                .map(DMNMessage::getMessage)
                .toList();

        steps.add(new TraceStepRecord(
                nextSeq(), "DECISION", decision.getName(), null, inputsSnapshot, result, messages, durationMs));
    }

    @Override
    public void afterEvaluateDecisionTable(AfterEvaluateDecisionTableEvent event) {
        List<TraceStepRecord> steps = STEPS.get();
        if (steps == null) {
            return;
        }
        steps.add(new TraceStepRecord(
                nextSeq(), "DECISION_TABLE", event.getDecisionName(),
                List.copyOf(event.getSelected()), null, null, null, null));
    }

    private static int nextSeq() {
        int[] seq = SEQ.get();
        return seq[0]++;
    }
}
