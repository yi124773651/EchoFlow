package com.echoflow.domain.execution;

import com.echoflow.domain.task.TaskId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Execution aggregate root — represents one run of an agent pipeline for a Task.
 *
 * <p>Owns an ordered list of {@link ExecutionStep}s. Steps are executed
 * sequentially; the Execution tracks overall progress.</p>
 */
public class Execution {

    private final ExecutionId id;
    private final TaskId taskId;
    private ExecutionStatus status;
    private final List<ExecutionStep> steps;
    private final Instant startedAt;
    private Instant completedAt;

    private Execution(ExecutionId id, TaskId taskId, ExecutionStatus status, Instant startedAt) {
        this.id = id;
        this.taskId = taskId;
        this.status = status;
        this.startedAt = startedAt;
        this.steps = new ArrayList<>();
    }

    /**
     * Factory: start planning a new execution for the given task.
     */
    public static Execution create(ExecutionId id, TaskId taskId, Instant now) {
        if (taskId == null) throw new IllegalArgumentException("TaskId must not be null");
        return new Execution(id, taskId, ExecutionStatus.PLANNING, now);
    }

    /**
     * Add a step during the PLANNING phase.
     */
    public ExecutionStep addStep(StepId stepId, String name, StepType type, Instant now) {
        if (status != ExecutionStatus.PLANNING) {
            throw new IllegalExecutionStateException(
                    "Cannot add steps to execution " + id + " in status " + status);
        }
        int order = steps.size() + 1;
        var step = ExecutionStep.create(stepId, order, name, type, now);
        steps.add(step);
        return step;
    }

    /**
     * Transition from PLANNING to RUNNING. Requires at least one step.
     */
    public void startRunning() {
        if (status != ExecutionStatus.PLANNING) {
            throw new IllegalExecutionStateException(
                    "Execution " + id + " cannot start running from status " + status);
        }
        if (steps.isEmpty()) {
            throw new IllegalExecutionStateException(
                    "Execution " + id + " must have at least one step before running");
        }
        this.status = ExecutionStatus.RUNNING;
    }

    /**
     * Start the next pending step. Returns the step that was started.
     */
    public ExecutionStep startNextStep() {
        requireRunning();
        var next = steps.stream()
                .filter(s -> s.status() == StepStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new IllegalExecutionStateException(
                        "No pending steps in execution " + id));
        next.markRunning();
        return next;
    }

    /**
     * Complete the currently running step with output.
     */
    public void completeStep(StepId stepId, String output) {
        requireRunningOrWaiting();
        findStep(stepId).markCompleted(output);
    }

    /**
     * Fail the currently running step.
     */
    public void failStep(StepId stepId, String reason) {
        requireRunningOrWaiting();
        findStep(stepId).markFailed(reason);
    }

    /**
     * Skip a step (degradation or human rejection).
     */
    public void skipStep(StepId stepId, String reason) {
        requireRunningOrWaiting();
        findStep(stepId).markSkipped(reason);
    }

    /**
     * Append a log entry to a running or waiting-approval step.
     */
    public void appendStepLog(StepId stepId, StepLog log) {
        requireRunningOrWaiting();
        findStep(stepId).appendLog(log);
    }

    /**
     * Mark the entire execution as completed.
     */
    public void markCompleted(Instant now) {
        requireRunning();
        boolean allDone = steps.stream().allMatch(s ->
                s.status() == StepStatus.COMPLETED || s.status() == StepStatus.SKIPPED);
        if (!allDone) {
            throw new IllegalExecutionStateException(
                    "Cannot complete execution " + id + ": not all steps are done");
        }
        this.status = ExecutionStatus.COMPLETED;
        this.completedAt = now;
    }

    /**
     * Mark the entire execution as failed.
     */
    public void markFailed(Instant now) {
        requireRunningOrWaiting();
        this.status = ExecutionStatus.FAILED;
        this.completedAt = now;
    }

    /**
     * Pause execution and the given step to wait for human approval.
     */
    public void markWaitingApproval() {
        requireRunning();
        this.status = ExecutionStatus.WAITING_APPROVAL;
    }

    /**
     * Mark a specific step as waiting for approval.
     */
    public void markStepWaitingApproval(StepId stepId) {
        findStep(stepId).markWaitingApproval();
    }

    /**
     * Resume execution after human approval.
     */
    public void resumeRunning() {
        if (status != ExecutionStatus.WAITING_APPROVAL) {
            throw new IllegalExecutionStateException(
                    "Execution " + id + " cannot resume from status " + status);
        }
        this.status = ExecutionStatus.RUNNING;
    }

    /**
     * Resume a specific step after human approval.
     */
    public void resumeStepFromApproval(StepId stepId) {
        findStep(stepId).resumeFromApproval();
    }

    /**
     * Start a specific step by name. Used for parallel execution where
     * multiple steps may start concurrently.
     */
    public ExecutionStep startStepByName(String name) {
        requireRunning();
        var step = steps.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalExecutionStateException(
                        "Step '" + name + "' not found in execution " + id));
        step.markRunning();
        return step;
    }

    /**
     * Check if there are remaining pending steps.
     */
    public boolean hasPendingSteps() {
        return steps.stream().anyMatch(s -> s.status() == StepStatus.PENDING);
    }

    private void requireRunning() {
        if (status != ExecutionStatus.RUNNING) {
            throw new IllegalExecutionStateException(
                    "Execution " + id + " is not running (status: " + status + ")");
        }
    }

    private void requireRunningOrWaiting() {
        if (status != ExecutionStatus.RUNNING && status != ExecutionStatus.WAITING_APPROVAL) {
            throw new IllegalExecutionStateException(
                    "Execution " + id + " is not running or waiting approval (status: " + status + ")");
        }
    }

    private ExecutionStep findStep(StepId stepId) {
        return steps.stream()
                .filter(s -> s.id().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new IllegalExecutionStateException(
                        "Step " + stepId + " not found in execution " + id));
    }

    // --- Reconstitution ---

    public static Execution reconstitute(ExecutionId id, TaskId taskId, ExecutionStatus status,
                                         Instant startedAt, Instant completedAt,
                                         List<ExecutionStep> steps) {
        var execution = new Execution(id, taskId, status, startedAt);
        execution.completedAt = completedAt;
        execution.steps.addAll(steps);
        return execution;
    }

    // --- Accessors ---

    public ExecutionId id() { return id; }
    public TaskId taskId() { return taskId; }
    public ExecutionStatus status() { return status; }
    public List<ExecutionStep> steps() { return Collections.unmodifiableList(steps); }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
}
