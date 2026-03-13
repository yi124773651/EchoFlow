package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.echoflow.application.execution.GraphOrchestrationPort.StepProgressListener;
import com.echoflow.domain.execution.LogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Review gate node that evaluates WRITE output quality and routes accordingly.
 *
 * <p>Reads the latest {@code writeOutput} from state, calls
 * {@link LlmWriteReviewer#evaluate} for LLM-as-Judge evaluation, and decides:
 * <ul>
 *   <li><b>approve</b>: Calls {@code onStepCompleted}, appends output to
 *       {@code outputs}, sets {@code reviewDecision} to "approve"</li>
 *   <li><b>revise</b>: Calls {@code onStepProgress} with feedback,
 *       sets {@code reviewDecision} to "revise" and {@code reviewFeedback}</li>
 * </ul>
 *
 * <p>Forces approval when:
 * <ul>
 *   <li>{@code writeOutput} is blank (WRITE was skipped/degraded)</li>
 *   <li>{@code writeAttempts >= maxAttempts}</li>
 *   <li>LLM evaluation fails (graceful degradation via {@link LlmWriteReviewer})</li>
 * </ul>
 */
class WriteReviewGateAction implements AsyncNodeAction {

    private static final Logger log = LoggerFactory.getLogger(WriteReviewGateAction.class);

    static final String STATE_KEY_REVIEW_FEEDBACK = "reviewFeedback";

    private final String stepName;
    private final LlmWriteReviewer reviewer;
    private final StepProgressListener listener;
    private final int maxAttempts;

    WriteReviewGateAction(String stepName,
                           LlmWriteReviewer reviewer,
                           StepProgressListener listener,
                           int maxAttempts) {
        this.stepName = stepName;
        this.reviewer = reviewer;
        this.listener = listener;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        var writeOutput = state.<String>value(ReviewableWriteNodeAction.STATE_KEY_WRITE_OUTPUT).orElse("");
        var attempts = state.<Integer>value(ReviewableWriteNodeAction.STATE_KEY_WRITE_ATTEMPTS).orElse(1);

        // Auto-approve if output is blank (WRITE was skipped/degraded)
        if (writeOutput.isBlank()) {
            log.info("Review gate for '{}': auto-approve (blank output)", stepName);
            return CompletableFuture.completedFuture(
                    Map.of(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, "approve"));
        }

        var taskDescription = state.<String>value(StepNodeAction.STATE_KEY_TASK_DESCRIPTION).orElse("");
        var review = reviewer.evaluate(taskDescription, stepName, writeOutput);

        if (review.approved() || attempts >= maxAttempts) {
            if (!review.approved()) {
                log.info("Review gate for '{}': max attempts ({}) reached, force-approving with score {}",
                        stepName, maxAttempts, review.score());
            } else {
                log.info("Review gate for '{}': approved with score {}", stepName, review.score());
            }

            listener.onStepCompleted(stepName, writeOutput);

            var result = new HashMap<String, Object>();
            result.put(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, "approve");
            result.put(StepNodeAction.STATE_KEY_OUTPUTS, writeOutput);
            return CompletableFuture.completedFuture(result);
        }

        // Needs revision
        log.info("Review gate for '{}': score {}, requesting revision (attempt {}/{})",
                stepName, review.score(), attempts, maxAttempts);

        listener.onStepProgress(stepName, LogType.THOUGHT,
                "Review (attempt " + attempts + "/" + maxAttempts + "): score " + review.score()
                        + "/100. " + review.feedback());

        var result = new HashMap<String, Object>();
        result.put(ReviewableWriteNodeAction.STATE_KEY_REVIEW_DECISION, "revise");
        result.put(STATE_KEY_REVIEW_FEEDBACK, review.feedback());
        return CompletableFuture.completedFuture(result);
    }
}
