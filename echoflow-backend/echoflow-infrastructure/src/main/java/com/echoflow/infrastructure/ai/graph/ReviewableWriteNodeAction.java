package com.echoflow.infrastructure.ai.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.application.execution.StepExecutorPort;
import com.echoflow.application.execution.TaskPlannerPort;
import com.echoflow.domain.execution.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * WRITE step node action that defers {@code onStepCompleted} to the review gate.
 *
 * <p>Unlike {@link StepNodeAction}, this action:
 * <ul>
 *   <li>Calls {@code onStepStarting} but does NOT call {@code onStepCompleted}</li>
 *   <li>Stores output in the {@code writeOutput} state key (REPLACE, not APPEND)</li>
 *   <li>Initializes {@code writeAttempts} to 1</li>
 * </ul>
 *
 * <p>The review gate node is responsible for calling {@code onStepCompleted}
 * when the output is approved (or max attempts reached).</p>
 *
 * <p>On degradation ({@link StepExecutionException}), sets {@code reviewDecision}
 * to "approve" so the review gate auto-skips, and marks the step as skipped.</p>
 */
class ReviewableWriteNodeAction implements AsyncNodeAction {

    private static final Logger log = LoggerFactory.getLogger(ReviewableWriteNodeAction.class);

    static final String STATE_KEY_WRITE_OUTPUT = "writeOutput";
    static final String STATE_KEY_WRITE_ATTEMPTS = "writeAttempts";
    static final String STATE_KEY_REVIEW_DECISION = "reviewDecision";

    private final String stepName;
    private final StepType stepType;
    private final StepExecutorPort stepExecutor;
    private final StepProgressListener listener;

    ReviewableWriteNodeAction(TaskPlannerPort.PlannedStep step,
                               StepExecutorPort stepExecutor,
                               StepProgressListener listener) {
        this.stepName = step.name();
        this.stepType = step.type();
        this.stepExecutor = stepExecutor;
        this.listener = listener;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        listener.onStepStarting(stepName, stepType);

        // Human approval gate (default: auto-approve, no-op)
        var decision = listener.onStepAwaitingApproval(stepName, stepType);
        if (!decision.approved()) {
            log.info("WRITE step '{}' rejected by user: {}", stepName, decision.reason());
            listener.onStepSkipped(stepName, "Rejected: " + decision.reason());

            var stateUpdate = new HashMap<String, Object>();
            stateUpdate.put(STATE_KEY_WRITE_OUTPUT, "");
            stateUpdate.put(STATE_KEY_REVIEW_DECISION, "approve");
            return CompletableFuture.completedFuture(stateUpdate);
        }

        var taskDescription = state.<String>value(StepNodeAction.STATE_KEY_TASK_DESCRIPTION).orElse("");
        var previousOutputs = readPreviousOutputs(state);
        var context = new StepExecutionContext(taskDescription, stepName, stepType, previousOutputs);

        try {
            var result = stepExecutor.execute(context);

            log.info("WRITE step '{}' produced initial draft ({} chars), entering review loop",
                    stepName, result.output().length());

            var stateUpdate = new HashMap<String, Object>();
            stateUpdate.put(STATE_KEY_WRITE_OUTPUT, result.output());
            stateUpdate.put(STATE_KEY_WRITE_ATTEMPTS, 1);
            return CompletableFuture.completedFuture(stateUpdate);
        } catch (StepExecutionException e) {
            log.warn("WRITE step '{}' degraded, skipping review: {}", stepName, e.getMessage());
            listener.onStepSkipped(stepName, e.getMessage());

            var stateUpdate = new HashMap<String, Object>();
            stateUpdate.put(STATE_KEY_WRITE_OUTPUT, "");
            stateUpdate.put(STATE_KEY_REVIEW_DECISION, "approve");
            return CompletableFuture.completedFuture(stateUpdate);
        } catch (Exception e) {
            log.error("WRITE step '{}' failed fatally: {}", stepName, e.getMessage());
            listener.onStepFailed(stepName, e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readPreviousOutputs(OverAllState state) {
        return state.<List<String>>value(StepNodeAction.STATE_KEY_OUTPUTS)
                .map(Collections::unmodifiableList)
                .orElse(List.of());
    }
}
