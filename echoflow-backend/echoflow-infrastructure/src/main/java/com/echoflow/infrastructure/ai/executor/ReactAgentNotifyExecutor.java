package com.echoflow.infrastructure.ai.executor;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * Executes NOTIFY steps using ReactAgent with Webhook notification tool calling.
 *
 * <p>The ReactAgent composes notification content from previous step outputs
 * and invokes the webhook tool to deliver it. A {@link ToolRetryInterceptor}
 * retries failed tool calls with exponential backoff.</p>
 */
class ReactAgentNotifyExecutor extends ReactAgentStepExecutor {

    ReactAgentNotifyExecutor(ChatClient chatClient, String promptContent,
                             List<Object> tools) {
        super(chatClient, promptContent, tools);
    }

    @Override
    protected String agentName() {
        return "notify_executor";
    }
}
