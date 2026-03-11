package com.echoflow.infrastructure.ai;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.Nested;
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
 * Tests for {@link ReactAgentStepExecutor} base class behavior:
 * retry loop, output validation, truncation, and placeholder formatting.
 *
 * <p>Uses a concrete test subclass that overrides {@code buildAgent()} to inject
 * a mock {@link ReactAgent}.</p>
 */
@ExtendWith(MockitoExtension.class)
class ReactAgentStepExecutorTest {

    @Mock
    private ReactAgent reactAgent;

    @Mock
    private ChatClient chatClient;

    /**
     * Concrete test subclass — uses the default formatUserMessage (all placeholders).
     */
    private ReactAgentStepExecutor createExecutor(String promptContent) {
        return new ReactAgentStepExecutor(chatClient, promptContent) {
            @Override
            protected String agentName() {
                return "test_executor";
            }

            @Override
            protected ReactAgent buildAgent() {
                return reactAgent;
            }
        };
    }

    // --- Retry loop ---

    @Nested
    class RetryBehavior {

        @Test
        void succeeds_on_first_attempt() throws GraphRunnerException {
            when(reactAgent.call(anyString()))
                    .thenReturn(new AssistantMessage("Success"));

            var executor = createExecutor("prompt {taskDescription} {stepName} {previousContext}");
            var context = new StepExecutionContext("task", "步骤", StepType.RESEARCH, List.of());
            var result = executor.execute(context);

            assertThat(result.output()).isEqualTo("Success");
            verify(reactAgent, times(1)).call(anyString());
        }

        @Test
        void retries_on_failure_then_succeeds() throws GraphRunnerException {
            when(reactAgent.call(anyString()))
                    .thenThrow(new GraphRunnerException("timeout"))
                    .thenReturn(new AssistantMessage("Recovered"));

            var executor = createExecutor("prompt {taskDescription} {stepName} {previousContext}");
            var context = new StepExecutionContext("task", "步骤", StepType.WRITE, List.of());
            var result = executor.execute(context);

            assertThat(result.output()).isEqualTo("Recovered");
            verify(reactAgent, times(2)).call(anyString());
        }

        @Test
        void throws_after_exhausting_retries() throws GraphRunnerException {
            when(reactAgent.call(anyString()))
                    .thenThrow(new GraphRunnerException("persistent"));

            var executor = createExecutor("prompt {taskDescription} {stepName} {previousContext}");
            var context = new StepExecutionContext("task", "步骤", StepType.RESEARCH, List.of());

            assertThatThrownBy(() -> executor.execute(context))
                    .isInstanceOf(StepExecutionException.class)
                    .hasMessageContaining("failed after")
                    .hasMessageContaining("步骤");
        }
    }

    // --- Output validation ---

    @Nested
    class OutputValidation {

        @Test
        void rejects_empty_output() throws GraphRunnerException {
            when(reactAgent.call(anyString()))
                    .thenReturn(new AssistantMessage(""));

            var executor = createExecutor("prompt {taskDescription} {stepName} {previousContext}");
            var context = new StepExecutionContext("task", "步骤", StepType.WRITE, List.of());

            assertThatThrownBy(() -> executor.execute(context))
                    .isInstanceOf(StepExecutionException.class)
                    .hasMessageContaining("empty output");
        }

        @Test
        void rejects_null_content() throws GraphRunnerException {
            when(reactAgent.call(anyString()))
                    .thenReturn(new AssistantMessage((String) null));

            var executor = createExecutor("prompt {taskDescription} {stepName} {previousContext}");
            var context = new StepExecutionContext("task", "步骤", StepType.NOTIFY, List.of());

            assertThatThrownBy(() -> executor.execute(context))
                    .isInstanceOf(StepExecutionException.class);
        }

        @Test
        void truncates_long_output() throws GraphRunnerException {
            when(reactAgent.call(anyString()))
                    .thenReturn(new AssistantMessage("x".repeat(15_000)));

            var executor = createExecutor("prompt {taskDescription} {stepName} {previousContext}");
            var context = new StepExecutionContext("task", "步骤", StepType.WRITE, List.of());
            var result = executor.execute(context);

            assertThat(result.output()).hasSizeLessThan(15_000);
            assertThat(result.output()).endsWith("[Output truncated]");
        }
    }

    // --- Placeholder formatting ---

    @Nested
    class UserMessageFormatting {

        @Test
        void replaces_all_placeholders() throws GraphRunnerException {
            when(reactAgent.call(anyString()))
                    .thenReturn(new AssistantMessage("ok"));

            var executor = createExecutor(
                    "Task: {taskDescription}\nStep: {stepName}\nContext: {previousContext}");
            var context = new StepExecutionContext("Build API", "调研", StepType.RESEARCH,
                    List.of("step 1 output"));
            executor.execute(context);

            var expectedMessage = "Task: Build API\nStep: 调研\nContext: --- Step 1 output ---\nstep 1 output";
            verify(reactAgent).call(expectedMessage);
        }

        @Test
        void formats_empty_previous_context() throws GraphRunnerException {
            when(reactAgent.call(anyString()))
                    .thenReturn(new AssistantMessage("ok"));

            var executor = createExecutor("Task: {taskDescription} Context: {previousContext}");
            var context = new StepExecutionContext("task", "步骤", StepType.WRITE, List.of());
            executor.execute(context);

            verify(reactAgent).call("Task: task Context: (no previous context)");
        }

        @Test
        void formats_multiple_previous_outputs() throws GraphRunnerException {
            when(reactAgent.call(anyString()))
                    .thenReturn(new AssistantMessage("ok"));

            var executor = createExecutor("{previousContext}");
            var context = new StepExecutionContext("task", "步骤", StepType.WRITE,
                    List.of("output A", "output B"));
            executor.execute(context);

            var expected = "--- Step 1 output ---\noutput A\n\n--- Step 2 output ---\noutput B";
            verify(reactAgent).call(expected);
        }
    }

    // --- buildPreviousContext static method ---

    @Test
    void buildPreviousContext_returns_placeholder_for_empty_list() {
        assertThat(ReactAgentStepExecutor.buildPreviousContext(List.of()))
                .isEqualTo("(no previous context)");
    }

    @Test
    void buildPreviousContext_formats_outputs_with_headers() {
        var result = ReactAgentStepExecutor.buildPreviousContext(List.of("A", "B"));
        assertThat(result).contains("--- Step 1 output ---");
        assertThat(result).contains("--- Step 2 output ---");
        assertThat(result).contains("A");
        assertThat(result).contains("B");
    }
}
