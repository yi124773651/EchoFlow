package com.echoflow.application.execution;

import com.echoflow.domain.execution.ApprovalDecision;
import com.echoflow.domain.execution.ExecutionId;
import com.echoflow.domain.execution.LogType;
import com.echoflow.domain.execution.StepType;

import java.util.List;

/**
 * Port for graph-based step orchestration.
 *
 * <p>Replaces the manual while-loop in {@link ExecuteTaskUseCase} with a
 * StateGraph-driven linear chain. The {@link StepProgressListener} callback
 * preserves the same event timing as the original loop — domain model updates
 * and SSE event publishing happen during execution, not after.</p>
 *
 * <p>Implementation lives in Infrastructure (StateGraph/OverAllState).
 * Application and Domain layers have zero graph-framework imports.</p>
 */
public interface GraphOrchestrationPort {

    /**
     * Execute planned steps sequentially via graph engine.
     *
     * <p>For each step, the listener is called before and after execution,
     * allowing the caller to perform domain model updates and event publishing
     * at the same timing as the original while-loop.</p>
     *
     * @param executionId     unique execution identifier, used as checkpoint thread ID
     * @param taskDescription the user's original task description
     * @param steps           ordered list of planned steps
     * @param listener        callback for step lifecycle events
     * @throws Exception if a step fails fatally (non-degradation)
     */
    void executeSteps(ExecutionId executionId,
                      String taskDescription,
                      List<TaskPlannerPort.PlannedStep> steps,
                      StepProgressListener listener);

    /**
     * Release persisted checkpoints for an execution (cleanup after completion/failure).
     */
    default void releaseCheckpoints(ExecutionId executionId) {}

    /**
     * Callback interface for step lifecycle events during graph execution.
     * Implemented by the use case to perform domain model updates and
     * event publishing.
     */
    interface StepProgressListener {

        /** Called just before a step begins execution. */
        void onStepStarting(String stepName, StepType stepType);

        /** Called when a step completes successfully with output. */
        void onStepCompleted(String stepName, String output);

        /** Called when a step is skipped due to degradation (StepExecutionException). */
        void onStepSkipped(String stepName, String reason);

        /** Called when a step fails fatally. The graph will abort after this. */
        void onStepFailed(String stepName, String reason);

        /**
         * Called for intermediate progress during multi-iteration steps
         * (e.g., review loop feedback and revision attempts).
         *
         * <p>Default no-op for backward compatibility with existing listeners
         * that do not need intermediate progress tracking.</p>
         */
        default void onStepProgress(String stepName, LogType logType, String content) {
        }

        /**
         * Called when a step requires human approval before execution.
         * The implementation should block until a decision is made.
         *
         * <p>Default: auto-approve (backward compatible, feature off).</p>
         */
        default ApprovalDecision onStepAwaitingApproval(String stepName, StepType stepType) {
            return ApprovalDecision.APPROVED;
        }
    }
}
