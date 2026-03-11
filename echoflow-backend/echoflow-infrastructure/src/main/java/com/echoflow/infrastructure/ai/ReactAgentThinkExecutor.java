package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.application.execution.StepOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POC-2: Executes THINK steps using Spring AI Alibaba's {@link ReactAgent}.
 *
 * <p>Replaces {@link LlmThinkExecutor} for THINK steps. Like the original,
 * it only passes {@code taskDescription} (not previous context) to avoid
 * biasing the initial analysis.</p>
 *
 * <p>Unlike {@link LlmStepExecutor} subclasses, this executor does not receive
 * a {@code ChatClient} per call — the ReactAgent encapsulates the model internally.
 * Retry logic and output validation mirror the original executor contract.</p>
 */
class ReactAgentThinkExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentThinkExecutor.class);
    static final int MAX_RETRIES = 2;
    private static final int MAX_OUTPUT_LENGTH = 10_000;

    private final ReactAgent reactAgent;

    ReactAgentThinkExecutor(ReactAgent reactAgent) {
        this.reactAgent = reactAgent;
    }

    StepOutput execute(StepExecutionContext context) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var assistantMessage = reactAgent.call(context.taskDescription());
                var content = assistantMessage.getText();
                return validate(content, context.stepName());
            } catch (GraphRunnerException e) {
                lastException = e;
                log.warn("ReactAgent THINK attempt {} failed for step '{}': {}",
                        attempt, context.stepName(), e.getMessage());
            }
        }

        throw new StepExecutionException(
                "Step execution failed after " + MAX_RETRIES + " attempts for: " + context.stepName(),
                lastException);
    }

    private StepOutput validate(String output, String stepName) {
        if (output == null || output.isBlank()) {
            throw new StepExecutionException("LLM returned empty output for step: " + stepName);
        }
        var truncated = output.length() > MAX_OUTPUT_LENGTH
                ? output.substring(0, MAX_OUTPUT_LENGTH) + "\n\n[Output truncated]"
                : output;
        return new StepOutput(truncated);
    }
}
