package com.echoflow.domain.execution;

import java.time.Instant;

/**
 * Immutable log entry recording a thought, action, observation, or error
 * during step execution. Append-only by design.
 */
public record StepLog(LogType type, String content, Instant loggedAt) {

    public StepLog {
        if (type == null) throw new IllegalArgumentException("Log type must not be null");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Log content must not be blank");
        if (loggedAt == null) throw new IllegalArgumentException("Log timestamp must not be null");
    }
}
