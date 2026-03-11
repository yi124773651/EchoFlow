package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.agent.Builder;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Executes RESEARCH steps using ReactAgent with GitHub search tool calling.
 *
 * <p>The ReactAgent autonomously decides whether to invoke the {@link GitHubSearchTool}
 * based on the task context. A {@link ToolRetryInterceptor} retries failed tool calls
 * with exponential backoff.</p>
 */
class ReactAgentResearchExecutor extends ReactAgentStepExecutor {

    private final GitHubSearchTool gitHubSearchTool;

    ReactAgentResearchExecutor(ChatClient chatClient, String promptContent,
                               GitHubSearchTool gitHubSearchTool) {
        super(chatClient, promptContent);
        this.gitHubSearchTool = gitHubSearchTool;
    }

    @Override
    protected String agentName() {
        return "research_executor";
    }

    @Override
    protected Builder configureAgent(Builder builder) {
        return builder
                .methodTools(gitHubSearchTool)
                .interceptors(new ToolRetryInterceptor(2));
    }
}
