package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.StepExecutionContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;

/**
 * Executes THINK steps. Does not pass previous context to avoid
 * biasing the initial analysis.
 */
class LlmThinkExecutor extends LlmStepExecutor {

    LlmThinkExecutor(ChatClient chatClient, Resource promptTemplate) {
        super(chatClient, promptTemplate);
    }

    @Override
    protected String callLlm(StepExecutionContext context) {
        return chatClient.prompt()
                .user(u -> u.text(promptTemplate)
                        .param("taskDescription", context.taskDescription())
                        .param("stepName", context.stepName()))
                .call()
                .content();
    }
}
