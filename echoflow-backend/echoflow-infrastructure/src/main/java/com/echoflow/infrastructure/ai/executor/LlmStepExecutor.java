package com.echoflow.infrastructure.ai.executor;

import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.application.execution.StepOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * Base class for LLM-powered step executors.
 *
 * <p>Encapsulates retry logic, output validation, and truncation.
 * Subclasses override {@link #callLlm} to customize prompt parameters.</p>
 *
 * <p>The {@link ChatClient} is passed per-call (not held as a field) to support
 * multi-model routing — different step types can use different models.</p>
 */
abstract class LlmStepExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmStepExecutor.class);
    static final int MAX_RETRIES = 2;
    private static final int MAX_OUTPUT_LENGTH = 10_000;

    protected final Resource promptTemplate;

    LlmStepExecutor(Resource promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

    StepOutput execute(StepExecutionContext context, ChatClient chatClient) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var output = callLlm(context, chatClient);
                return validate(output, context.stepName());
            } catch (Exception e) {
                lastException = e;
                log.warn("Step execution attempt {} failed for step '{}': {}",
                        attempt, context.stepName(), e.getMessage());
            }
        }

        throw new StepExecutionException(
                "Step execution failed after " + MAX_RETRIES + " attempts for: " + context.stepName(),
                lastException);
    }

    /**
     * Call LLM with the prompt template. Default implementation passes
     * taskDescription, stepName, and previousContext. Subclasses may override
     * to customize parameters (e.g. THINK steps skip previousContext).
     */
    protected String callLlm(StepExecutionContext context, ChatClient chatClient) {
        return chatClient.prompt()
                .user(u -> u.text(promptTemplate)
                        .param("taskDescription", context.taskDescription())
                        .param("stepName", context.stepName())
                        .param("previousContext", buildPreviousContext(context.previousOutputs())))
                .call()
                .content();
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

    static String buildPreviousContext(List<String> previousOutputs) {
        if (previousOutputs.isEmpty()) {
            return "(no previous context)";
        }
        var sb = new StringBuilder();
        for (int i = 0; i < previousOutputs.size(); i++) {
            sb.append("--- Step ").append(i + 1).append(" output ---\n");
            sb.append(previousOutputs.get(i)).append("\n\n");
        }
        return sb.toString().strip();
    }
}
