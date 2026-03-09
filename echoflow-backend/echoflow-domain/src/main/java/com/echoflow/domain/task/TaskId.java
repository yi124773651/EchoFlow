package com.echoflow.domain.task;

import java.util.UUID;

/**
 * Identity of a {@link Task} aggregate.
 */
public record TaskId(UUID value) {

    public TaskId {
        if (value == null) {
            throw new IllegalArgumentException("TaskId must not be null");
        }
    }

    public static TaskId generate() {
        return new TaskId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
