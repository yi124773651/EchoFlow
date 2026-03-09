package com.echoflow.application.task;

import com.echoflow.domain.task.TaskId;
import com.echoflow.domain.task.TaskStatus;

import java.time.Instant;

/**
 * Read-only projection of a Task for query responses.
 */
public record TaskResult(
        TaskId id,
        String description,
        TaskStatus status,
        Instant createdAt,
        Instant completedAt
) {}
