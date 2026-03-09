package com.echoflow.domain.execution;

/**
 * Lifecycle status of an {@link Execution}.
 *
 * <pre>
 *   PLANNING ──→ RUNNING ──→ COMPLETED
 *                   │
 *                   └──→ FAILED
 * </pre>
 */
public enum ExecutionStatus {
    PLANNING,
    RUNNING,
    COMPLETED,
    FAILED
}
