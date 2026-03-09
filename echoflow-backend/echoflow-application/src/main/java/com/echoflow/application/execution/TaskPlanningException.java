package com.echoflow.application.execution;

import com.echoflow.domain.DomainException;

/**
 * Thrown when AI task planning fails after retries or produces invalid output.
 */
public class TaskPlanningException extends DomainException {

    public TaskPlanningException(String message) {
        super(message);
    }

    public TaskPlanningException(String message, Throwable cause) {
        super(message, cause);
    }
}
