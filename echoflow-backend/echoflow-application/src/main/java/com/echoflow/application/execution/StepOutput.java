package com.echoflow.application.execution;

/**
 * Output produced by executing a single step.
 *
 * @param output the textual output (may be Markdown for WRITE steps)
 */
public record StepOutput(String output) {
    public StepOutput {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("Step output must not be blank");
        }
    }
}
