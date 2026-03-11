package com.echoflow.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Executes WRITE steps using ReactAgent.
 *
 * <p>No tools — the agent synthesizes a comprehensive Markdown report
 * from previous step context passed in the user message.</p>
 */
class ReactAgentWriteExecutor extends ReactAgentStepExecutor {

    ReactAgentWriteExecutor(ChatClient chatClient, String promptContent) {
        super(chatClient, promptContent);
    }

    @Override
    protected String agentName() {
        return "write_executor";
    }
}
