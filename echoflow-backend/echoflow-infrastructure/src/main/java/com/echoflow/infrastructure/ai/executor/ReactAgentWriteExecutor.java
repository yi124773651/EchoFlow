package com.echoflow.infrastructure.ai.executor;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * Executes WRITE steps using ReactAgent.
 *
 * <p>Typically no tools — the agent synthesizes a comprehensive Markdown report
 * from previous step context passed in the user message.</p>
 */
class ReactAgentWriteExecutor extends ReactAgentStepExecutor {

    ReactAgentWriteExecutor(ChatClient chatClient, String promptContent,
                            List<Object> tools) {
        super(chatClient, promptContent, tools);
    }

    @Override
    protected String agentName() {
        return "write_executor";
    }
}
