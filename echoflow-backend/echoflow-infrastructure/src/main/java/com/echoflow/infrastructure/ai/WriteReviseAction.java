package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutorPort;
import com.echoflow.domain.execution.LogType;
import com.echoflow.domain.execution.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Revision node that re-generates WRITE output incorporating review feedback.
 *
 * <p>Reads the current {@code writeOutput} and {@code reviewFeedback} from state,
 * builds an enriched context with the feedback appended to previous outputs,
 * and calls the step executor to produce a revised draft.</p>
 *
 * <p>Logs intermediate progress via {@code onStepProgress} callbacks:
 * <ul>
 *   <li>{@link LogType#ACTION}: "Revising draft..."</li>
 *   <li>{@link LogType#OBSERVATION}: The revised output content</li>
 * </ul>
 */
class WriteReviseAction implements AsyncNodeAction {

    private static final Logger log = LoggerFactory.getLogger(WriteReviseAction.class);

    private final String stepName;
    private final StepExecutorPort stepExecutor;
    private final StepProgressListener listener;

    WriteReviseAction(String stepName,
                       StepExecutorPort stepExecutor,
                       StepProgressListener listener) {
        this.stepName = stepName;
        this.stepExecutor = stepExecutor;
        this.listener = listener;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        var writeOutput = state.<String>value(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT).orElse("");
        var feedback = state.<String>value(WriteReviewGateAction.STATE_KEY_REVIEW_FEEDBACK).orElse("");
        var taskDescription = state.<String>value(StepNodeAction.STATE_KEY_TASK_DESCRIPTION).orElse("");
        var attempts = state.<Integer>value(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS).orElse(1);

        @SuppressWarnings("unchecked")
        var previousOutputs = state.<List<String>>value(StepNodeAction.STATE_KEY_OUTPUTS)
                .map(ArrayList::new)
                .orElseGet(ArrayList::new);

        // Append revision context: current draft + feedback for the WRITE executor
        var revisionContext = "[REVISION FEEDBACK]\n" + feedback
                + "\n\nPlease revise the following draft addressing the feedback above:\n" + writeOutput;
        previousOutputs.add(revisionContext);

        var context = new StepExecutionContext(taskDescription, stepName, StepType.WRITE, previousOutputs);

        log.info("Revising WRITE step '{}' (attempt {})", stepName, attempts + 1);
        listener.onStepProgress(stepName, LogType.ACTION, "Revising draft based on review feedback...");

        try {
            var result = stepExecutor.execute(context);

            listener.onStepProgress(stepName, LogType.OBSERVATION, result.output());

            var stateUpdate = new HashMap<String, Object>();
            stateUpdate.put(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT, result.output());
            stateUpdate.put(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, attempts + 1);
            return CompletableFuture.completedFuture(stateUpdate);
        } catch (Exception e) {
            // Revision failed — keep original output, review gate will force-approve on next pass
            log.warn("Revision failed for '{}', keeping original output: {}", stepName, e.getMessage());
            listener.onStepProgress(stepName, LogType.ERROR,
                    "Revision failed: " + e.getMessage() + ". Keeping current draft.");

            var stateUpdate = new HashMap<String, Object>();
            stateUpdate.put(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS, attempts + 1);
            return CompletableFuture.completedFuture(stateUpdate);
        }
    }
}
