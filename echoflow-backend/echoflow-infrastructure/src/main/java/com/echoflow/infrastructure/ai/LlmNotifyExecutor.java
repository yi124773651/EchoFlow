package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.StepExecutionContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;

/**
 * Executes NOTIFY steps with Webhook tool calling.
 *
 * <p>Passes a {@link WebhookNotifyTool} to the LLM via Spring AI's tool calling API.
 * The LLM composes notification content from previous step outputs and invokes
 * the Webhook tool to deliver it.</p>
 */
class LlmNotifyExecutor extends LlmStepExecutor {

    private final WebhookNotifyTool webhookNotifyTool;

    LlmNotifyExecutor(Resource promptTemplate, WebhookNotifyTool webhookNotifyTool) {
        super(promptTemplate);
        this.webhookNotifyTool = webhookNotifyTool;
    }

    @Override
    protected String callLlm(StepExecutionContext context, ChatClient chatClient) {
        return chatClient.prompt()
                .user(u -> u.text(promptTemplate)
                        .param("taskDescription", context.taskDescription())
                        .param("stepName", context.stepName())
                        .param("previousContext", buildPreviousContext(context.previousOutputs())))
                .tools(webhookNotifyTool)
                .call()
                .content();
    }
}
