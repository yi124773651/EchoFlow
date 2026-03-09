package com.echoflow.domain.task;

import java.time.Instant;

/**
 * Task aggregate root — represents a user-submitted async task.
 *
 * <p>A Task captures the user's intent. Its lifecycle is:
 * {@code SUBMITTED → EXECUTING → COMPLETED / FAILED}.
 * Actual execution is handled by the Execution aggregate.</p>
 */
public class Task {

    private final TaskId id;
    private final String description;
    private TaskStatus status;
    private final Instant createdAt;
    private Instant completedAt;

    private Task(TaskId id, String description, TaskStatus status, Instant createdAt) {
        this.id = id;
        this.description = description;
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * Factory: submit a new task.
     */
    public static Task submit(TaskId id, String description, Instant now) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Task description must not be blank");
        }
        return new Task(id, description.strip(), TaskStatus.SUBMITTED, now);
    }

    /**
     * Mark this task as executing.
     */
    public void markExecuting() {
        requireStatus(TaskStatus.SUBMITTED, TaskStatus.EXECUTING);
        this.status = TaskStatus.EXECUTING;
    }

    /**
     * Mark this task as completed.
     */
    public void markCompleted(Instant now) {
        requireStatus(TaskStatus.EXECUTING, TaskStatus.COMPLETED);
        this.status = TaskStatus.COMPLETED;
        this.completedAt = now;
    }

    /**
     * Mark this task as failed.
     */
    public void markFailed(Instant now) {
        requireStatus(TaskStatus.EXECUTING, TaskStatus.FAILED);
        this.status = TaskStatus.FAILED;
        this.completedAt = now;
    }

    private void requireStatus(TaskStatus expected, TaskStatus target) {
        if (this.status != expected) {
            throw new IllegalTaskStateException(id, status, target);
        }
    }

    // --- Reconstitution (for persistence layer) ---

    public static Task reconstitute(TaskId id, String description, TaskStatus status,
                                    Instant createdAt, Instant completedAt) {
        var task = new Task(id, description, status, createdAt);
        task.completedAt = completedAt;
        return task;
    }

    // --- Accessors ---

    public TaskId id() {
        return id;
    }

    public String description() {
        return description;
    }

    public TaskStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }
}
