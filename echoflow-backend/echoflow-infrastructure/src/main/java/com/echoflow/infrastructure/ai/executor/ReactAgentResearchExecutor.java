package com.echoflow.infrastructure.ai.executor;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.echoflow.infrastructure.ai.tool.GitHubSearchTool;
import com.echoflow.infrastructure.ai.tool.WebSearchTool;
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
    private final WebSearchTool webSearchTool;

    ReactAgentResearchExecutor(ChatClient chatClient, String promptContent,
                               GitHubSearchTool gitHubSearchTool,
                               WebSearchTool webSearchTool) {
        super(chatClient, promptContent);
        this.gitHubSearchTool = gitHubSearchTool;
        this.webSearchTool = webSearchTool;
    }

    @Override
    protected String agentName() {
        return "research_executor";
    }

    @Override
    protected Builder configureAgent(Builder builder) {
        return builder
                .methodTools(gitHubSearchTool, webSearchTool)
                .interceptors(new ToolRetryInterceptor(2));
    }
}
