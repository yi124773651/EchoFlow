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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link StepExecutorRouter} — verifies unified ReactAgent routing
 * and fallback to LLM executors for all step types.
 */
@ExtendWith(MockitoExtension.class)
class StepExecutorRouterTest {

    @Mock private ReactAgentStepExecutor reactThinkExecutor;
    @Mock private ReactAgentStepExecutor reactResearchExecutor;
    @Mock private ReactAgentStepExecutor reactWriteExecutor;
    @Mock private ReactAgentStepExecutor reactNotifyExecutor;

    @Mock private ChatClient primaryChatClient;
    @Mock private ChatClient fallbackChatClient;
    @Mock private ChatClient.ChatClientRequestSpec fallbackRequestSpec;
    @Mock private ChatClient.CallResponseSpec fallbackCallSpec;

    private StepExecutorRouter router;

    @BeforeEach
    void setUp() {
        // Fallback client mock chain (for LLM fallback path)
        lenient().when(fallbackChatClient.prompt()).thenReturn(fallbackRequestSpec);
        lenient().when(fallbackRequestSpec.user(any(Consumer.class))).thenReturn(fallbackRequestSpec);
        lenient().when(fallbackRequestSpec.tools(any(Object.class))).thenReturn(fallbackRequestSpec);

        var thinkPrompt = new ByteArrayResource("think {taskDescription} {stepName}".getBytes());
        var researchPrompt = new ByteArrayResource("research {taskDescription} {stepName} {previousContext}".getBytes());
        var writePrompt = new ByteArrayResource("write {taskDescription} {stepName} {previousContext}".getBytes());
        var notifyPrompt = new ByteArrayResource("notify {taskDescription} {stepName} {previousContext}".getBytes());

        router = new StepExecutorRouter(
                Map.of(
                        StepType.THINK, reactThinkExecutor,
                        StepType.RESEARCH, reactResearchExecutor,
                        StepType.WRITE, reactWriteExecutor,
                        StepType.NOTIFY, reactNotifyExecutor),
                Map.of(
                        StepType.THINK, new LlmThinkExecutor(thinkPrompt),
                        StepType.RESEARCH, new LlmResearchExecutor(researchPrompt,
                                new GitHubSearchTool("https://api.github.com", "",
                                        Duration.ofSeconds(5), Duration.ofSeconds(10), 5)),
                        StepType.WRITE, new LlmWriteExecutor(writePrompt),
                        StepType.NOTIFY, new LlmNotifyExecutor(notifyPrompt,
                                new WebhookNotifyTool("", Duration.ofSeconds(5), Duration.ofSeconds(10)))),
                Map.of(
                        StepType.THINK, primaryChatClient,
                        StepType.RESEARCH, primaryChatClient,
                        StepType.WRITE, primaryChatClient,
                        StepType.NOTIFY, primaryChatClient),
                fallbackChatClient);
    }

    // --- All step types route to ReactAgent ---

    @Nested
    class ReactAgentRouting {

        @Test
        void routes_think_to_react_agent() {
            when(reactThinkExecutor.execute(any()))
                    .thenReturn(new StepOutput("Think result"));

            var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("Think result");
            verify(reactThinkExecutor).execute(context);
        }

        @Test
        void routes_research_to_react_agent() {
            when(reactResearchExecutor.execute(any()))
                    .thenReturn(new StepOutput("Research result"));

            var context = new StepExecutionContext("task", "调研", StepType.RESEARCH, List.of("prev"));
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("Research result");
            verify(reactResearchExecutor).execute(context);
        }

        @Test
        void routes_write_to_react_agent() {
            when(reactWriteExecutor.execute(any()))
                    .thenReturn(new StepOutput("# Report\nContent"));

            var context = new StepExecutionContext("task", "撰写", StepType.WRITE, List.of("analysis", "research"));
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("# Report\nContent");
            verify(reactWriteExecutor).execute(context);
        }

        @Test
        void routes_notify_to_react_agent() {
            when(reactNotifyExecutor.execute(any()))
                    .thenReturn(new StepOutput("Notification sent"));

            var context = new StepExecutionContext("task", "通知", StepType.NOTIFY, List.of("report"));
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("Notification sent");
            verify(reactNotifyExecutor).execute(context);
        }
    }

    // --- Fallback from ReactAgent to LLM ---

    @Nested
    class FallbackBehavior {

        @Test
        void falls_back_to_llm_when_react_agent_fails_for_think() {
            when(reactThinkExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("ReactAgent failed"));
            when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
            when(fallbackCallSpec.content()).thenReturn("LLM fallback think");

            var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("LLM fallback think");
            verify(reactThinkExecutor).execute(context);
            verify(fallbackChatClient, atLeastOnce()).prompt();
        }

        @Test
        void falls_back_to_llm_when_react_agent_fails_for_research() {
            when(reactResearchExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("ReactAgent failed"));
            when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
            when(fallbackCallSpec.content()).thenReturn("LLM fallback research");

            var context = new StepExecutionContext("task", "调研", StepType.RESEARCH, List.of());
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("LLM fallback research");
            verify(reactResearchExecutor).execute(context);
            verify(fallbackChatClient, atLeastOnce()).prompt();
        }

        @Test
        void falls_back_to_llm_when_react_agent_fails_for_write() {
            when(reactWriteExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("ReactAgent failed"));
            when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
            when(fallbackCallSpec.content()).thenReturn("LLM fallback write");

            var context = new StepExecutionContext("task", "撰写", StepType.WRITE, List.of("prev"));
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("LLM fallback write");
            verify(reactWriteExecutor).execute(context);
            verify(fallbackChatClient, atLeastOnce()).prompt();
        }

        @Test
        void falls_back_to_llm_when_react_agent_fails_for_notify() {
            when(reactNotifyExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("ReactAgent failed"));
            when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
            when(fallbackCallSpec.content()).thenReturn("LLM fallback notify");

            var context = new StepExecutionContext("task", "通知", StepType.NOTIFY, List.of("report"));
            var result = router.execute(context);

            assertThat(result.output()).isEqualTo("LLM fallback notify");
            verify(reactNotifyExecutor).execute(context);
            verify(fallbackChatClient, atLeastOnce()).prompt();
        }

        @Test
        void throws_when_primary_and_fallback_are_same_client() {
            var sameClientRouter = new StepExecutorRouter(
                    Map.of(
                            StepType.THINK, reactThinkExecutor,
                            StepType.RESEARCH, reactResearchExecutor,
                            StepType.WRITE, reactWriteExecutor,
                            StepType.NOTIFY, reactNotifyExecutor),
                    Map.of(
                            StepType.THINK, new LlmThinkExecutor(
                                    new ByteArrayResource("think {taskDescription} {stepName}".getBytes())),
                            StepType.RESEARCH, new LlmResearchExecutor(
                                    new ByteArrayResource("r {taskDescription} {stepName} {previousContext}".getBytes()),
                                    new GitHubSearchTool("https://api.github.com", "",
                                            Duration.ofSeconds(5), Duration.ofSeconds(10), 5)),
                            StepType.WRITE, new LlmWriteExecutor(
                                    new ByteArrayResource("w {taskDescription} {stepName} {previousContext}".getBytes())),
                            StepType.NOTIFY, new LlmNotifyExecutor(
                                    new ByteArrayResource("n {taskDescription} {stepName} {previousContext}".getBytes()),
                                    new WebhookNotifyTool("", Duration.ofSeconds(5), Duration.ofSeconds(10)))),
                    Map.of(
                            StepType.THINK, primaryChatClient,
                            StepType.RESEARCH, primaryChatClient,
                            StepType.WRITE, primaryChatClient,
                            StepType.NOTIFY, primaryChatClient),
                    primaryChatClient); // same as primary!

            when(reactThinkExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("ReactAgent failed"));

            var context = new StepExecutionContext("task", "分析", StepType.THINK, List.of());

            assertThatThrownBy(() -> sameClientRouter.execute(context))
                    .isInstanceOf(StepExecutionException.class)
                    .hasMessageContaining("ReactAgent failed");
        }
    }

    // --- LLM fallback details ---

    @Nested
    class LlmFallbackDetails {

        @Test
        void research_fallback_registers_tools() {
            when(reactResearchExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("fail"));
            when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
            when(fallbackCallSpec.content()).thenReturn("Research with tools");

            var context = new StepExecutionContext("task", "调研", StepType.RESEARCH, List.of());
            router.execute(context);

            verify(fallbackRequestSpec).tools(any(GitHubSearchTool.class));
        }

        @Test
        void notify_fallback_registers_tools() {
            when(reactNotifyExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("fail"));
            when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
            when(fallbackCallSpec.content()).thenReturn("Notify with tools");

            var context = new StepExecutionContext("task", "通知", StepType.NOTIFY, List.of());
            router.execute(context);

            verify(fallbackRequestSpec).tools(any(WebhookNotifyTool.class));
        }

        @Test
        void propagates_exception_when_both_react_agent_and_llm_fallback_fail() {
            when(reactResearchExecutor.execute(any()))
                    .thenThrow(new StepExecutionException("ReactAgent failed"));
            when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
            when(fallbackCallSpec.content()).thenThrow(new RuntimeException("fallback timeout"));

            var context = new StepExecutionContext("task", "调研", StepType.RESEARCH, List.of());

            assertThatThrownBy(() -> router.execute(context))
                    .isInstanceOf(StepExecutionException.class)
                    .hasMessageContaining("failed after");
        }
    }
}
