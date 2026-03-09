package com.echoflow.application.execution;

import com.echoflow.domain.execution.StepType;

import java.util.List;

/**
 * Port for AI-powered task planning.
 * The implementation lives in Infrastructure and calls an LLM.
 */
public interface TaskPlannerPort {

    /**
     * Decompose a task description into ordered execution steps.
     *
     * @param taskDescription the user's natural-language task description
     * @return ordered list of planned steps
     * @throws TaskPlanningException if planning fails after retries
     */
    List<PlannedStep> planSteps(String taskDescription);

    /**
     * A single planned step produced by the LLM.
     */
    record PlannedStep(String name, StepType type) {
        public PlannedStep {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Step name must not be blank");
            }
            if (type == null) {
                throw new IllegalArgumentException("Step type must not be null");
            }
        }
    }
}
