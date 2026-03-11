package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.agent.Builder;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Executes NOTIFY steps using ReactAgent with Webhook notification tool calling.
 *
 * <p>The ReactAgent composes notification content from previous step outputs
 * and invokes the {@link WebhookNotifyTool} to deliver it. A {@link ToolRetryInterceptor}
 * retries failed tool calls with exponential backoff.</p>
 */
class ReactAgentNotifyExecutor extends ReactAgentStepExecutor {

    private final WebhookNotifyTool webhookNotifyTool;

    ReactAgentNotifyExecutor(ChatClient chatClient, String promptContent,
                             WebhookNotifyTool webhookNotifyTool) {
        super(chatClient, promptContent);
        this.webhookNotifyTool = webhookNotifyTool;
    }

    @Override
    protected String agentName() {
        return "notify_executor";
    }

    @Override
    protected Builder configureAgent(Builder builder) {
        return builder
                .methodTools(webhookNotifyTool)
                .interceptors(new ToolRetryInterceptor(2));
    }
}
