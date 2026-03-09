package com.echoflow.domain.execution;

import java.util.UUID;

/**
 * Identity of an {@link ExecutionStep}.
 */
public record StepId(UUID value) {

    public StepId {
        if (value == null) {
            throw new IllegalArgumentException("StepId must not be null");
        }
    }

    public static StepId generate() {
        return new StepId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
