package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.StepExecutionContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;

/**
 * Executes RESEARCH steps with GitHub search tool calling.
 *
 * <p>Passes a {@link GitHubSearchTool} to the LLM via Spring AI's tool calling API.
 * The LLM autonomously decides whether to invoke the tool based on the task context.</p>
 */
class LlmResearchExecutor extends LlmStepExecutor {

    private final GitHubSearchTool gitHubSearchTool;

    LlmResearchExecutor(ChatClient chatClient, Resource promptTemplate,
                         GitHubSearchTool gitHubSearchTool) {
        super(chatClient, promptTemplate);
        this.gitHubSearchTool = gitHubSearchTool;
    }

    @Override
    protected String callLlm(StepExecutionContext context) {
        return chatClient.prompt()
                .user(u -> u.text(promptTemplate)
                        .param("taskDescription", context.taskDescription())
                        .param("stepName", context.stepName())
                        .param("previousContext", buildPreviousContext(context.previousOutputs())))
                .tools(gitHubSearchTool)
                .call()
                .content();
    }
}
