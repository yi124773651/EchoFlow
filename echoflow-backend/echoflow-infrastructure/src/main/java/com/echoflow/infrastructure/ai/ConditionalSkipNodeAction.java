package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.domain.execution.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A graph node that skips a step by firing listener callbacks without
 * invoking {@link com.echoflow.application.execution.StepExecutorPort}.
 *
 * <p>This produces the same SSE event sequence as degradation-based skipping:
 * {@code StepStarted → StepSkipped}. The domain model transitions the step through
 * {@code PENDING → RUNNING → SKIPPED} via the listener callbacks.</p>
 *
 * <p>Returns an empty map — no output contribution to OverAllState.</p>
 */
class ConditionalSkipNodeAction implements AsyncNodeAction {

    private static final Logger log = LoggerFactory.getLogger(ConditionalSkipNodeAction.class);

    private final String stepName;
    private final StepType stepType;
    private final StepProgressListener listener;
    private final String skipReason;

    ConditionalSkipNodeAction(String stepName, StepType stepType,
                              StepProgressListener listener, String skipReason) {
        this.stepName = stepName;
        this.stepType = stepType;
        this.listener = listener;
        this.skipReason = skipReason;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        log.info("Conditionally skipping step '{}' (type={}): {}", stepName, stepType, skipReason);

        listener.onStepStarting(stepName, stepType);
        listener.onStepSkipped(stepName, skipReason);

        return CompletableFuture.completedFuture(Map.of());
    }
}
