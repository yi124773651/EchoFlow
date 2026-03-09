package com.echoflow.domain.task;

import com.echoflow.domain.DomainException;

/**
 * Thrown when a {@link Task} state transition is not allowed.
 */
public class IllegalTaskStateException extends DomainException {

    public IllegalTaskStateException(TaskId taskId, TaskStatus current, TaskStatus target) {
        super("Task " + taskId + " cannot transition from " + current + " to " + target);
    }
}
