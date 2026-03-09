package com.echoflow.domain.execution;

import com.echoflow.domain.DomainException;

/**
 * Thrown when an {@link Execution} or {@link ExecutionStep} state transition is not allowed.
 */
public class IllegalExecutionStateException extends DomainException {

    public IllegalExecutionStateException(String message) {
        super(message);
    }
}
