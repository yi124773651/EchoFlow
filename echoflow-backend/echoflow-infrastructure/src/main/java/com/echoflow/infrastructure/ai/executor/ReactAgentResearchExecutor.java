package com.echoflow.infrastructure.ai.executor;

import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * Executes RESEARCH steps using ReactAgent with GitHub search tool calling.
 *
 * <p>The ReactAgent autonomously decides whether to invoke search tools
 * based on the task context. A {@link ToolRetryInterceptor} retries failed tool calls
 * with exponential backoff.</p>
 */
class ReactAgentResearchExecutor extends ReactAgentStepExecutor {

    ReactAgentResearchExecutor(ChatClient chatClient, String promptContent,
                               List<Object> tools) {
        super(chatClient, promptContent, tools);
    }

    @Override
    protected String agentName() {
        return "research_executor";
    }
}
