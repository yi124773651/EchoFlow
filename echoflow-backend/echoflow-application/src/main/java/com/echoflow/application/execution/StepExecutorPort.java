package com.echoflow.application.execution;

/**
 * Port for executing a single step within a task pipeline.
 * Implementation lives in Infrastructure and may call LLM, external APIs, etc.
 */
public interface StepExecutorPort {

    /**
     * Execute a step based on the provided context.
     *
     * @param context execution context including task description, step info, and previous outputs
     * @return the step output
     * @throws StepExecutionException if execution fails after retries
     */
    StepOutput execute(StepExecutionContext context);
}
