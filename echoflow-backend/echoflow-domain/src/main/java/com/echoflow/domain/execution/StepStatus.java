package com.echoflow.domain.execution;

/**
 * Lifecycle status of an {@link ExecutionStep}.
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    SKIPPED,
    FAILED
}
