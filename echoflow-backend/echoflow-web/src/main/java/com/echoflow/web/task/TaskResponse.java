package com.echoflow.web.task;

import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskStatus;

import java.time.Instant;

/**
 * Response body for task endpoints.
 */
public record TaskResponse(
        String id,
        String description,
        TaskStatus status,
        Instant createdAt,
        Instant completedAt
) {
    public static TaskResponse from(com.echoflow.application.task.TaskResult result) {
        return new TaskResponse(
                result.id().toString(),
                result.description(),
                result.status(),
                result.createdAt(),
                result.completedAt()
        );
    }
}
