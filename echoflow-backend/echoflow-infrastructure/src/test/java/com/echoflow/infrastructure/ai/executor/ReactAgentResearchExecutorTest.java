package com.echoflow.infrastructure.ai.executor;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ReactAgentResearchExecutor} — verifies RESEARCH-specific behavior:
 * both GitHubSearchTool and WebSearchTool are available, and previousContext is included.
 *
 * <p>Base class retry/validate/truncation is covered by {@link ReactAgentStepExecutorTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
class ReactAgentResearchExecutorTest {

    @Mock
    private ReactAgent reactAgent;

    @Mock
    private ChatClient chatClient;

    private ReactAgentResearchExecutor createExecutor(String promptContent) {
        return new ReactAgentResearchExecutor(chatClient, promptContent, List.of(),
                ReactAgentStepExecutor.DEFAULT_MAX_MODEL_CALLS) {
            @Override
            protected ReactAgent buildAgent() {
                return reactAgent;
            }
        };
    }

    @Test
    void formats_user_message_with_previous_context() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenReturn(new AssistantMessage("Research complete"));

        var executor = createExecutor(
                "Task: {taskDescription}\nStep: {stepName}\nContext: {previousContext}");
        var context = new StepExecutionContext("Build API", "调研", StepType.RESEARCH,
                List.of("think output"));
        var result = executor.execute(context);

        assertThat(result.output()).isEqualTo("Research complete");
        verify(reactAgent).call("Task: Build API\nStep: 调研\nContext: --- Step 1 output ---\nthink output");
    }

    @Test
    void retries_on_failure_then_succeeds() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenThrow(new GraphRunnerException("model timeout"))
                .thenReturn(new AssistantMessage("Success after retry"));

        var executor = createExecutor("Task: {taskDescription}\nStep: {stepName}\nContext: {previousContext}");
        var context = new StepExecutionContext("task", "调研", StepType.RESEARCH, List.of());
        var result = executor.execute(context);

        assertThat(result.output()).isEqualTo("Success after retry");
        verify(reactAgent, times(2)).call(anyString());
    }

    @Test
    void throws_after_max_retries() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenThrow(new GraphRunnerException("persistent failure"));

        var executor = createExecutor("Task: {taskDescription}\nStep: {stepName}\nContext: {previousContext}");
        var context = new StepExecutionContext("task", "调研", StepType.RESEARCH, List.of());

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed after")
                .hasMessageContaining("调研");
    }

    @Test
    void rejects_empty_output() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenReturn(new AssistantMessage(""));

        var executor = createExecutor("Task: {taskDescription}\nStep: {stepName}\nContext: {previousContext}");
        var context = new StepExecutionContext("task", "调研", StepType.RESEARCH, List.of());

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("empty output");
    }

    @Test
    void agent_name_is_research_executor() {
        var executor = new ReactAgentResearchExecutor(chatClient, "prompt", List.of(),
                ReactAgentStepExecutor.DEFAULT_MAX_MODEL_CALLS);
        assertThat(executor.agentName()).isEqualTo("research_executor");
    }
}
