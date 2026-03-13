package com.echoflow.infrastructure.ai.executor;

import com.echoflow.application.execution.StepExecutionContext;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Executes THINK steps using Spring AI Alibaba's ReactAgent.
 *
 * <p>Unlike other executors, THINK only passes {@code taskDescription}
 * (not previous context) to avoid biasing the initial analysis.</p>
 */
class ReactAgentThinkExecutor extends ReactAgentStepExecutor {

    ReactAgentThinkExecutor(ChatClient chatClient, String promptContent) {
        super(chatClient, promptContent);
    }

    @Override
    protected String agentName() {
        return "think_executor";
    }

    /**
     * THINK prompt has no {@code {previousContext}} placeholder —
     * only replace taskDescription and stepName.
     */
    @Override
    protected String formatUserMessage(StepExecutionContext context) {
        return promptContent
                .replace("{taskDescription}", context.taskDescription())
                .replace("{stepName}", context.stepName());
    }
}
