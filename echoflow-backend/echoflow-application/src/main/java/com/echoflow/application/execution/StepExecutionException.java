package com.echoflow.application.execution;

import com.echoflow.domain.DomainException;

/**
 * Thrown when a step execution fails after retries or produces invalid output.
 */
public class StepExecutionException extends DomainException {

    public StepExecutionException(String message) {
        super(message);
    }

    public StepExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
