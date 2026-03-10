package com.echoflow.domain.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single step within an {@link Execution}.
 *
 * <p>Steps are owned by the Execution aggregate and transition through:
 * {@code PENDING → RUNNING → COMPLETED / SKIPPED / FAILED}.
 * Each step accumulates append-only {@link StepLog} entries.</p>
 */
public class ExecutionStep {

    private final StepId id;
    private final int order;
    private final String name;
    private final StepType type;
    private StepStatus status;
    private String output;
    private final Instant createdAt;
    private final List<StepLog> logs;

    private ExecutionStep(StepId id, int order, String name, StepType type, Instant createdAt) {
        this.id = id;
        this.order = order;
        this.name = name;
        this.type = type;
        this.status = StepStatus.PENDING;
        this.createdAt = createdAt;
        this.logs = new ArrayList<>();
    }

    static ExecutionStep create(StepId id, int order, String name, StepType type, Instant now) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Step type must not be null");
        }
        return new ExecutionStep(id, order, name.strip(), type, now);
    }

    /**
     * Start running this step.
     */
    void markRunning() {
        requireStatus(StepStatus.PENDING, StepStatus.RUNNING);
        this.status = StepStatus.RUNNING;
    }

    /**
     * Complete this step with output.
     */
    void markCompleted(String output) {
        requireStatus(StepStatus.RUNNING, StepStatus.COMPLETED);
        this.status = StepStatus.COMPLETED;
        this.output = output;
    }

    /**
     * Mark this step as failed.
     */
    void markFailed(String reason) {
        requireStatus(StepStatus.RUNNING, StepStatus.FAILED);
        this.status = StepStatus.FAILED;
        this.output = reason;
    }

    /**
     * Skip this step (e.g. LLM degradation after retry exhaustion).
     */
    void markSkipped(String reason) {
        if (this.status != StepStatus.PENDING && this.status != StepStatus.RUNNING) {
            throw new IllegalExecutionStateException(
                    "Step " + id + " cannot transition from " + status + " to SKIPPED");
        }
        this.status = StepStatus.SKIPPED;
        this.output = reason;
    }

    /**
     * Append a log entry. Only allowed while RUNNING.
     */
    void appendLog(StepLog log) {
        if (this.status != StepStatus.RUNNING) {
            throw new IllegalExecutionStateException(
                    "Cannot append log to step " + id + " in status " + status);
        }
        this.logs.add(log);
    }

    private void requireStatus(StepStatus expected, StepStatus target) {
        if (this.status != expected) {
            throw new IllegalExecutionStateException(
                    "Step " + id + " cannot transition from " + status + " to " + target);
        }
    }

    // --- Reconstitution ---

    public static ExecutionStep reconstitute(StepId id, int order, String name, StepType type,
                                             StepStatus status, String output, Instant createdAt,
                                             List<StepLog> logs) {
        var step = new ExecutionStep(id, order, name, type, createdAt);
        step.status = status;
        step.output = output;
        step.logs.addAll(logs);
        return step;
    }

    // --- Accessors ---

    public StepId id() { return id; }
    public int order() { return order; }
    public String name() { return name; }
    public StepType type() { return type; }
    public StepStatus status() { return status; }
    public String output() { return output; }
    public Instant createdAt() { return createdAt; }
    public List<StepLog> logs() { return Collections.unmodifiableList(logs); }
}
