package com.echoflow.domain.task;

/**
 * Lifecycle status of a {@link Task}.
 *
 * <pre>
 *   SUBMITTED ──→ EXECUTING ──→ COMPLETED
 *                     │
 *                     └──→ FAILED
 * </pre>
 */
public enum TaskStatus {
    SUBMITTED,
    EXECUTING,
    COMPLETED,
    FAILED
}
