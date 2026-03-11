package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.application.execution.StepOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * Abstract base for ReactAgent-backed step executors.
 *
 * <p>Encapsulates per-call ReactAgent construction, retry logic, output validation,
 * and truncation. Subclasses customize the agent (tools, interceptors) via
 * {@link #configureAgent} and the user message format via {@link #formatUserMessage}.</p>
 *
 * <p>A fresh {@link ReactAgent} is built for each {@link #execute} call to avoid
 * state leakage (e.g. {@link ModelCallLimitHook} counters accumulating across
 * unrelated executions).</p>
 */
abstract class ReactAgentStepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentStepExecutor.class);
    static final int MAX_RETRIES = 2;
    static final int MAX_OUTPUT_LENGTH = 10_000;

    protected final ChatClient chatClient;
    protected final String promptContent;

    ReactAgentStepExecutor(ChatClient chatClient, String promptContent) {
        this.chatClient = chatClient;
        this.promptContent = promptContent;
    }

    /**
     * Unique agent name for logging / identification.
     */
    protected abstract String agentName();

    /**
     * Hook for subclasses to add tools, interceptors, etc.
     * The builder already has chatClient and default hooks configured.
     */
    protected Builder configureAgent(Builder builder) {
        return builder;
    }

    /**
     * Build the user message from context. Default replaces all standard placeholders.
     * THINK executor overrides to skip {@code {previousContext}}.
     */
    protected String formatUserMessage(StepExecutionContext context) {
        return promptContent
                .replace("{taskDescription}", context.taskDescription())
                .replace("{stepName}", context.stepName())
                .replace("{previousContext}", buildPreviousContext(context.previousOutputs()));
    }

    StepOutput execute(StepExecutionContext context) {
        var agent = buildAgent();
        var userMessage = formatUserMessage(context);
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var assistantMessage = agent.call(userMessage);
                var content = assistantMessage.getText();
                return validate(content, context.stepName());
            } catch (GraphRunnerException e) {
                lastException = e;
                log.warn("ReactAgent {} attempt {} failed for step '{}': {}",
                        agentName(), attempt, context.stepName(), e.getMessage());
            }
        }

        throw new StepExecutionException(
                "Step execution failed after " + MAX_RETRIES + " attempts for: " + context.stepName(),
                lastException);
    }

    /**
     * Build a fresh ReactAgent for this execution. Protected to allow
     * test subclasses to inject a mock agent.
     */
    protected ReactAgent buildAgent() {
        var builder = ReactAgent.builder()
                .name(agentName())
                .chatClient(chatClient)
                .hooks(
                        ModelCallLimitHook.builder().runLimit(5).build(),
                        new MessageTrimmingHook(20));
        return configureAgent(builder).build();
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
