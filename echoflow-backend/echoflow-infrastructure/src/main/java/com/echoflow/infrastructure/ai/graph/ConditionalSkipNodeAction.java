package com.echoflow.infrastructure.ai.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.application.execution.TaskPlannerPort;
import com.echoflow.domain.execution.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A graph node that skips one or more steps by firing listener callbacks without
 * invoking {@link com.echoflow.application.execution.StepExecutorPort}.
 *
 * <p>For each step, this produces the same SSE event sequence as degradation-based
 * skipping: {@code StepStarted → StepSkipped}. The domain model transitions each
 * step through {@code PENDING → RUNNING → SKIPPED} via the listener callbacks.</p>
 *
 * <p>When used as the single skip node in a parallel conditional graph, it
 * aggregates all RESEARCH steps to skip in one node, ensuring the routeMap
 * only contains nodes whose outgoing edges all point to the same convergence
 * point (required by {@code ConditionalParallelNode}).</p>
 *
 * <p>Returns an empty map — no output contribution to OverAllState.</p>
 */
class ConditionalSkipNodeAction implements AsyncNodeAction {

    private static final Logger log = LoggerFactory.getLogger(ConditionalSkipNodeAction.class);

    private final List<TaskPlannerPort.PlannedStep> stepsToSkip;
    private final StepProgressListener listener;
    private final String skipReason;

    ConditionalSkipNodeAction(List<TaskPlannerPort.PlannedStep> stepsToSkip,
                              StepProgressListener listener, String skipReason) {
        this.stepsToSkip = stepsToSkip;
        this.listener = listener;
        this.skipReason = skipReason;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        for (var step : stepsToSkip) {
            log.info("Conditionally skipping step '{}' (type={}): {}", step.name(), step.type(), skipReason);
            listener.onStepStarting(step.name(), step.type());
            listener.onStepSkipped(step.name(), skipReason);
        }
        return CompletableFuture.completedFuture(Map.of());
    }
}
