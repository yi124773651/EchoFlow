package com.echoflow.infrastructure.ai.executor;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.infrastructure.ai.tool.WebSearchTool;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Executes THINK steps using Spring AI Alibaba's ReactAgent.
 *
 * <p>Unlike other executors, THINK only passes {@code taskDescription}
 * (not previous context) to avoid biasing the initial analysis.</p>
 */
class ReactAgentThinkExecutor extends ReactAgentStepExecutor {

    private final WebSearchTool webSearchTool;

    ReactAgentThinkExecutor(ChatClient chatClient, String promptContent,
                            WebSearchTool webSearchTool) {
        super(chatClient, promptContent);
        this.webSearchTool = webSearchTool;
    }

    @Override
    protected String agentName() {
        return "think_executor";
    }

    @Override
    protected Builder configureAgent(Builder builder) {
        return builder
                .methodTools(webSearchTool)
                .interceptors(new ToolRetryInterceptor(2));
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
