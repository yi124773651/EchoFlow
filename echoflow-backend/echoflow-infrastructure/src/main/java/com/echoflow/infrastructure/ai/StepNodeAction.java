package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.echoflow.application.execution.*;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.domain.execution.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps a single step execution as a StateGraph {@link AsyncNodeAction}.
 *
 * <p>Each node in the linear StateGraph chain corresponds to one planned step.
 * The action:
 * <ol>
 *   <li>Notifies the listener that the step is starting</li>
 *   <li>Reads previous outputs from {@link OverAllState}</li>
 *   <li>Delegates to {@link StepExecutorPort} for actual execution</li>
 *   <li>Notifies the listener of the result (completed / skipped / failed)</li>
 *   <li>Returns partial state for OverAllState merge</li>
 * </ol>
 *
 * <p>On {@link StepExecutionException} (degradation), the step is skipped and
 * the graph continues. On unexpected exceptions, the step is marked failed
 * and the exception is re-thrown to abort the graph.</p>
 */
class StepNodeAction implements AsyncNodeAction {

    private static final Logger log = LoggerFactory.getLogger(StepNodeAction.class);

    static final String STATE_KEY_TASK_DESCRIPTION = "taskDescription";
    static final String STATE_KEY_OUTPUTS = "outputs";

    private final String stepName;
    private final StepType stepType;
    private final StepExecutorPort stepExecutor;
    private final StepProgressListener listener;

    StepNodeAction(TaskPlannerPort.PlannedStep step,
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

        var taskDescription = state.<String>value(STATE_KEY_TASK_DESCRIPTION).orElse("");
        var previousOutputs = readPreviousOutputs(state);
        var context = new StepExecutionContext(taskDescription, stepName, stepType, previousOutputs);

        try {
            var result = stepExecutor.execute(context);
            listener.onStepCompleted(stepName, result.output());
            return CompletableFuture.completedFuture(Map.of(STATE_KEY_OUTPUTS, result.output()));
        } catch (StepExecutionException e) {
            log.warn("Step '{}' degraded, skipping: {}", stepName, e.getMessage());
            listener.onStepSkipped(stepName, e.getMessage());
            return CompletableFuture.completedFuture(Map.of());
        } catch (Exception e) {
            log.error("Step '{}' failed fatally: {}", stepName, e.getMessage());
            listener.onStepFailed(stepName, e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readPreviousOutputs(OverAllState state) {
        return state.<List<String>>value(STATE_KEY_OUTPUTS)
                .map(Collections::unmodifiableList)
                .orElse(List.of());
    }
}
