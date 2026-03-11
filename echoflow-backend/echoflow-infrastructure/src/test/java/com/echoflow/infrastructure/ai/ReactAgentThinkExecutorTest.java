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
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * POC-2: Verifies that ReactAgent can replace the existing LlmThinkExecutor
 * while maintaining the same contract through LlmStepExecutor.
 */
@ExtendWith(MockitoExtension.class)
class ReactAgentThinkExecutorTest {

    @Mock
    private ReactAgent reactAgent;

    @Test
    void call_returns_assistant_message_content() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenReturn(new AssistantMessage("Analysis: The task requires careful planning."));

        var executor = new ReactAgentThinkExecutor(reactAgent);

        var context = new StepExecutionContext("Build a REST API", "分析", StepType.THINK, List.of());
        var result = executor.execute(context);

        assertThat(result.output()).isEqualTo("Analysis: The task requires careful planning.");
        verify(reactAgent).call("Build a REST API");
    }

    @Test
    void call_passes_task_description_only_not_previous_context() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenReturn(new AssistantMessage("Think output"));

        var executor = new ReactAgentThinkExecutor(reactAgent);

        var context = new StepExecutionContext("My task", "分析", StepType.THINK,
                List.of("previous output 1", "previous output 2"));
        executor.execute(context);

        // THINK executor should only pass taskDescription, not previous context
        verify(reactAgent).call("My task");
    }

    @Test
    void retries_on_failure_then_succeeds() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenThrow(new GraphRunnerException("model timeout"))
                .thenReturn(new AssistantMessage("Success after retry"));

        var executor = new ReactAgentThinkExecutor(reactAgent);

        var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());
        var result = executor.execute(context);

        assertThat(result.output()).isEqualTo("Success after retry");
        verify(reactAgent, times(2)).call("task");
    }

    @Test
    void throws_step_execution_exception_after_max_retries() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenThrow(new GraphRunnerException("persistent failure"));

        var executor = new ReactAgentThinkExecutor(reactAgent);

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

        var executor = new ReactAgentThinkExecutor(reactAgent);

        var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("empty output");
    }

    @Test
    void rejects_null_content_from_assistant_message() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenReturn(new AssistantMessage((String) null));

        var executor = new ReactAgentThinkExecutor(reactAgent);

        var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(StepExecutionException.class);
    }

    @Test
    void truncates_long_output() throws GraphRunnerException {
        when(reactAgent.call(anyString()))
                .thenReturn(new AssistantMessage("x".repeat(15_000)));

        var executor = new ReactAgentThinkExecutor(reactAgent);

        var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());
        var result = executor.execute(context);

        assertThat(result.output()).hasSizeLessThan(15_000);
        assertThat(result.output()).endsWith("[Output truncated]");
    }
}
