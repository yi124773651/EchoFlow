package com.echoflow.application.task;

import com.echoflow.domain.execution.*;
import com.echoflow.domain.task.TaskId;

import java.time.Instant;
import java.util.List;

/**
 * Read-only projection of a Task with its execution details.
 */
public record TaskDetailResult(
        TaskId taskId,
        String description,
        String taskStatus,
        Instant createdAt,
        Instant completedAt,
        ExecutionSnapshot execution
) {
    public record ExecutionSnapshot(
            ExecutionId executionId,
            ExecutionStatus status,
            Instant startedAt,
            Instant completedAt,
            List<StepSnapshot> steps
    ) {}

    public record StepSnapshot(
            StepId stepId,
            int order,
            String name,
            StepType type,
            StepStatus status,
            String output,
            List<LogSnapshot> logs
    ) {}

    public record LogSnapshot(
            LogType type,
            String content,
            Instant loggedAt
    ) {}
}
