package com.echoflow.infrastructure.ai;

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
 * Tests for {@link ReactAgentThinkExecutor} — verifies THINK-specific behavior:
 * only taskDescription and stepName are passed (no previousContext).
 *
 * <p>Base class retry/validate/truncation is covered by {@link ReactAgentStepExecutorTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
class ReactAgentThinkExecutorTest {

    @Mock
    private ReactAgent reactAgent;

    @Mock
    private ChatClient chatClient;

    private ReactAgentThinkExecutor createExecutor(String promptContent) {
        return new ReactAgentThinkExecutor(chatClient, promptContent) {
            @Override
            protected ReactAgent buildAgent() {
                return reactAgent;
            }
        };
    }

    @Test
    void formats_user_message_with_task_and_step_only() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenReturn(new AssistantMessage("Analysis complete"));

        var executor = createExecutor(
                "Task: {taskDescription}\nStep: {stepName}");
        var context = new StepExecutionContext("Build a REST API", "分析", StepType.THINK, List.of());
        var result = executor.execute(context);

        assertThat(result.output()).isEqualTo("Analysis complete");
        verify(reactAgent).call("Task: Build a REST API\nStep: 分析");
    }

    @Test
    void ignores_previous_context_even_when_provided() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenReturn(new AssistantMessage("Think output"));

        var executor = createExecutor("Task: {taskDescription}\nStep: {stepName}");
        var context = new StepExecutionContext("My task", "分析", StepType.THINK,
                List.of("previous output 1", "previous output 2"));
        executor.execute(context);

        // Should NOT contain any previous context
        verify(reactAgent).call("Task: My task\nStep: 分析");
    }

    @Test
    void retries_on_failure_then_succeeds() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenThrow(new GraphRunnerException("model timeout"))
                .thenReturn(new AssistantMessage("Success after retry"));

        var executor = createExecutor("Task: {taskDescription}\nStep: {stepName}");
        var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());
        var result = executor.execute(context);

        assertThat(result.output()).isEqualTo("Success after retry");
        verify(reactAgent, times(2)).call(anyString());
    }

    @Test
    void throws_after_max_retries() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenThrow(new GraphRunnerException("persistent failure"));

        var executor = createExecutor("Task: {taskDescription}\nStep: {stepName}");
        var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed after")
                .hasMessageContaining("分析");
    }

    @Test
    void rejects_empty_output() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenReturn(new AssistantMessage(""));

        var executor = createExecutor("Task: {taskDescription}\nStep: {stepName}");
        var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("empty output");
    }

    @Test
    void agent_name_is_think_executor() {
        var executor = new ReactAgentThinkExecutor(chatClient, "prompt");
        assertThat(executor.agentName()).isEqualTo("think_executor");
    }
}
