package com.echoflow.application.task;

/**
 * Command to submit a new task.
 */
public record SubmitTaskCommand(String description) {

    public SubmitTaskCommand {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Task description must not be blank");
        }
    }
}
