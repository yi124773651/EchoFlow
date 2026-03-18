package com.echoflow.domain.execution;

/**
 * Lifecycle status of an {@link Execution}.
 *
 * <pre>
 *   PLANNING ──→ RUNNING ──→ COMPLETED
 *                   │
 *                   ├──→ WAITING_APPROVAL ──→ RUNNING
 *                   │
 *                   └──→ FAILED
 * </pre>
 */
public enum ExecutionStatus {
    PLANNING,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED
}
