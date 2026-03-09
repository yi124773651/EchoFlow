package com.echoflow.domain.execution;

import java.util.UUID;

/**
 * Identity of an {@link Execution} aggregate.
 */
public record ExecutionId(UUID value) {

    public ExecutionId {
        if (value == null) {
            throw new IllegalArgumentException("ExecutionId must not be null");
        }
    }

    public static ExecutionId generate() {
        return new ExecutionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
