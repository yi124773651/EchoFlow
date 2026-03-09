package com.echoflow.application.execution;

import com.echoflow.domain.execution.StepType;

import java.util.List;

/**
 * Context passed to a step executor containing all information
 * needed to execute a single step.
 *
 * @param taskDescription  the user's original task description
 * @param stepName         the name of the current step
 * @param stepType         the type of the current step
 * @param previousOutputs  outputs from previously completed steps (ordered)
 */
public record StepExecutionContext(
        String taskDescription,
        String stepName,
        StepType stepType,
        List<String> previousOutputs
) {
    public StepExecutionContext {
        if (taskDescription == null || taskDescription.isBlank()) {
            throw new IllegalArgumentException("Task description must not be blank");
        }
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("Step name must not be blank");
        }
        if (stepType == null) {
            throw new IllegalArgumentException("Step type must not be null");
        }
        previousOutputs = previousOutputs == null ? List.of() : List.copyOf(previousOutputs);
    }
}
