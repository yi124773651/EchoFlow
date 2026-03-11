package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.application.execution.StepOutput;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepExecutorRouterTest {

    @Mock private ChatClient primaryChatClient;
    @Mock private ChatClient fallbackChatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callSpec;
    @Mock private ChatClient.ChatClientRequestSpec fallbackRequestSpec;
    @Mock private ChatClient.CallResponseSpec fallbackCallSpec;
    @Mock private ReactAgentThinkExecutor reactAgentThinkExecutor;

    private StepExecutorRouter router;

    @BeforeEach
    void setUp() {
        // Primary client mock chain (for non-THINK steps)
        lenient().when(primaryChatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any(Object.class))).thenReturn(requestSpec);

        // Fallback client mock chain
        lenient().when(fallbackChatClient.prompt()).thenReturn(fallbackRequestSpec);
        lenient().when(fallbackRequestSpec.user(any(Consumer.class))).thenReturn(fallbackRequestSpec);
        lenient().when(fallbackRequestSpec.tools(any(Object.class))).thenReturn(fallbackRequestSpec);

        Resource researchPrompt = new ByteArrayResource("research {taskDescription} {stepName} {previousContext}".getBytes());
        Resource writePrompt = new ByteArrayResource("write {taskDescription} {stepName} {previousContext}".getBytes());
        Resource notifyPrompt = new ByteArrayResource("notify {taskDescription} {stepName} {previousContext}".getBytes());
        Resource thinkPrompt = new ByteArrayResource("think {taskDescription} {stepName}".getBytes());

        // Use the package-private test constructor
        router = new StepExecutorRouter(
                reactAgentThinkExecutor,
                new LlmThinkExecutor(thinkPrompt),
                new LlmResearchExecutor(researchPrompt, new GitHubSearchTool(
                        "https://api.github.com", "",
                        Duration.ofSeconds(5), Duration.ofSeconds(10), 5)),
                new LlmWriteExecutor(writePrompt),
                new LlmNotifyExecutor(notifyPrompt, new WebhookNotifyTool(
                        "", Duration.ofSeconds(5), Duration.ofSeconds(10))),
                Map.of(
                        StepType.THINK, primaryChatClient,
                        StepType.RESEARCH, primaryChatClient,
                        StepType.WRITE, primaryChatClient,
                        StepType.NOTIFY, primaryChatClient),
                fallbackChatClient);
    }

    // --- POC-2: THINK steps via ReactAgent ---

    @Nested
    class ThinkStepViaReactAgent {

        @Test
        void routes_think_step_to_react_agent() {
            when(reactAgentThinkExecutor.execute(any()))
                    .thenReturn(new StepOutput("Analysis complete"));

            var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("Analysis complete");
            verify(reactAgentThinkExecutor).execute(context);
            // ChatClient should NOT be called for THINK (ReactAgent handles it internally)
            verify(primaryChatClient, never()).prompt();
        }

        @Test
        void falls_back_to_llm_when_react_agent_fails() {
            when(reactAgentThinkExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("ReactAgent failed"));

            // Fallback via LlmThinkExecutor + fallbackClient
            when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
            when(fallbackCallSpec.content()).thenReturn("Fallback think output");

            var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("Fallback think output");
            verify(reactAgentThinkExecutor).execute(context);
            verify(fallbackChatClient, atLeastOnce()).prompt();
        }

        @Test
        void throws_when_react_agent_and_fallback_are_same_client() {
            // Rebuild router with same client for primary and fallback
            Resource thinkPrompt = new ByteArrayResource("think {taskDescription} {stepName}".getBytes());
            Resource researchPrompt = new ByteArrayResource("research {taskDescription} {stepName} {previousContext}".getBytes());
            Resource writePrompt = new ByteArrayResource("write {taskDescription} {stepName} {previousContext}".getBytes());
            Resource notifyPrompt = new ByteArrayResource("notify {taskDescription} {stepName} {previousContext}".getBytes());

            var sameRouter = new StepExecutorRouter(
                    reactAgentThinkExecutor,
                    new LlmThinkExecutor(thinkPrompt),
                    new LlmResearchExecutor(researchPrompt, new GitHubSearchTool(
                            "https://api.github.com", "",
                            Duration.ofSeconds(5), Duration.ofSeconds(10), 5)),
                    new LlmWriteExecutor(writePrompt),
                    new LlmNotifyExecutor(notifyPrompt, new WebhookNotifyTool(
                            "", Duration.ofSeconds(5), Duration.ofSeconds(10))),
                    Map.of(
                            StepType.THINK, primaryChatClient,
                            StepType.RESEARCH, primaryChatClient,
                            StepType.WRITE, primaryChatClient,
                            StepType.NOTIFY, primaryChatClient),
                    primaryChatClient); // same as primary!

            when(reactAgentThinkExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("ReactAgent failed"));

            var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());

            assertThatThrownBy(() -> sameRouter.execute(context))
                    .isInstanceOf(StepExecutionException.class)
                    .hasMessageContaining("ReactAgent failed");
        }
    }

    // --- Non-THINK steps remain unchanged ---

    @Test
    void routes_research_step_to_llm() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Research findings");

        var context = new StepExecutionContext("task desc", "调研", StepType.RESEARCH, List.of("prev output"));
        var result = router.execute(context);

        assertThat(result.output()).isEqualTo("Research findings");
    }

    @Test
    void research_step_registers_tools() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Research with tools");

        var context = new StepExecutionContext("task desc", "调研", StepType.RESEARCH, List.of());
        router.execute(context);

        verify(requestSpec).tools(any(GitHubSearchTool.class));
    }

    @Test
    void routes_write_step_to_llm() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("# Report\n\nDetailed content");

        var context = new StepExecutionContext("task desc", "撰写", StepType.WRITE, List.of("analysis", "research"));
        var result = router.execute(context);

        assertThat(result.output()).isEqualTo("# Report\n\nDetailed content");
    }

    @Test
    void routes_notify_step_to_llm() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Notification sent successfully");

        var context = new StepExecutionContext("task desc", "发送通知", StepType.NOTIFY, List.of("report output"));
        var result = router.execute(context);

        assertThat(result.output()).isEqualTo("Notification sent successfully");
        verify(primaryChatClient, atLeastOnce()).prompt();
    }

    @Test
    void notify_step_registers_tools() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Notification with tools");

        var context = new StepExecutionContext("task desc", "发送通知", StepType.NOTIFY, List.of());
        router.execute(context);

        verify(requestSpec).tools(any(WebhookNotifyTool.class));
    }

    @Test
    void rejects_empty_llm_output() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("");
        // Fallback also returns empty
        when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
        when(fallbackCallSpec.content()).thenReturn("");

        var context = new StepExecutionContext("task desc", "调研", StepType.RESEARCH, List.of());

        assertThatThrownBy(() -> router.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed after");
    }

    @Test
    void retries_on_failure_then_succeeds() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content())
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn("Success after retry");

        var context = new StepExecutionContext("task desc", "调研", StepType.RESEARCH, List.of());
        var result = router.execute(context);

        assertThat(result.output()).isEqualTo("Success after retry");
    }

    @Test
    void truncates_long_output() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("x".repeat(15_000));

        var context = new StepExecutionContext("task desc", "撰写", StepType.WRITE, List.of());
        var result = router.execute(context);

        assertThat(result.output()).hasSizeLessThan(15_000);
        assertThat(result.output()).endsWith("[Output truncated]");
    }

    // --- Multi-model routing tests (non-THINK) ---

    @Test
    void falls_back_when_primary_model_fails_for_non_think_step() {
        // Primary fails all retries
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("primary timeout"));

        // Fallback succeeds
        when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
        when(fallbackCallSpec.content()).thenReturn("Fallback success");

        var context = new StepExecutionContext("task desc", "调研", StepType.RESEARCH, List.of());
        var result = router.execute(context);

        assertThat(result.output()).isEqualTo("Fallback success");
        verify(primaryChatClient, atLeastOnce()).prompt();
        verify(fallbackChatClient, atLeastOnce()).prompt();
    }

    @Test
    void propagates_exception_when_both_primary_and_fallback_fail() {
        // Primary fails
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("primary timeout"));

        // Fallback also fails
        when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
        when(fallbackCallSpec.content()).thenThrow(new RuntimeException("fallback timeout"));

        var context = new StepExecutionContext("task desc", "调研", StepType.RESEARCH, List.of());

        assertThatThrownBy(() -> router.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed after");
    }
}
