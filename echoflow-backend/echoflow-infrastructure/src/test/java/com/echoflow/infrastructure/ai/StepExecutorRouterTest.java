package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.domain.execution.StepType;
import org.junit.jupiter.api.BeforeEach;
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

    @Mock private ChatClientProvider chatClientProvider;
    @Mock private ChatClient primaryChatClient;
    @Mock private ChatClient fallbackChatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callSpec;
    @Mock private ChatClient.ChatClientRequestSpec fallbackRequestSpec;
    @Mock private ChatClient.CallResponseSpec fallbackCallSpec;

    private StepExecutorRouter router;

    @BeforeEach
    void setUp() {
        // Primary client mock chain
        lenient().when(primaryChatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any(Object.class))).thenReturn(requestSpec);

        // Fallback client mock chain
        lenient().when(fallbackChatClient.prompt()).thenReturn(fallbackRequestSpec);
        lenient().when(fallbackRequestSpec.user(any(Consumer.class))).thenReturn(fallbackRequestSpec);
        lenient().when(fallbackRequestSpec.tools(any(Object.class))).thenReturn(fallbackRequestSpec);

        // ChatClientProvider: all step types use primary, fallback is separate
        lenient().when(chatClientProvider.resolve("primary")).thenReturn(primaryChatClient);
        lenient().when(chatClientProvider.resolve("fallback")).thenReturn(fallbackChatClient);

        var routing = new MultiModelProperties.RoutingConfig(
                Map.of("think", "primary", "research", "primary",
                        "write", "primary", "notify", "primary"),
                "fallback");
        var properties = new MultiModelProperties(Map.of(), routing);

        Resource thinkPrompt = new ByteArrayResource("think {taskDescription} {stepName}".getBytes());
        Resource researchPrompt = new ByteArrayResource("research {taskDescription} {stepName} {previousContext}".getBytes());
        Resource writePrompt = new ByteArrayResource("write {taskDescription} {stepName} {previousContext}".getBytes());
        Resource notifyPrompt = new ByteArrayResource("notify {taskDescription} {stepName} {previousContext}".getBytes());

        router = new StepExecutorRouter(
                chatClientProvider, properties,
                thinkPrompt, researchPrompt, writePrompt, notifyPrompt,
                "https://api.github.com", "",
                Duration.ofSeconds(5), Duration.ofSeconds(10), 5,
                "", Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    void routes_think_step_to_llm() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Analysis complete");

        var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());
        var result = router.execute(context);

        assertThat(result.output()).isEqualTo("Analysis complete");
        verify(primaryChatClient, atLeastOnce()).prompt();
    }

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

        var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());

        assertThatThrownBy(() -> router.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed after");
    }

    @Test
    void rejects_null_llm_output() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(null);
        // Fallback also returns null
        when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
        when(fallbackCallSpec.content()).thenReturn(null);

        var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());

        assertThatThrownBy(() -> router.execute(context))
                .isInstanceOf(StepExecutionException.class);
    }

    @Test
    void retries_on_failure_then_succeeds() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content())
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn("Success after retry");

        var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());
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

    // --- Multi-model routing tests ---

    @Test
    void falls_back_when_primary_model_fails() {
        // Primary fails all retries
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("primary timeout"));

        // Fallback succeeds
        when(fallbackRequestSpec.call()).thenReturn(fallbackCallSpec);
        when(fallbackCallSpec.content()).thenReturn("Fallback success");

        var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());
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

        var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());

        assertThatThrownBy(() -> router.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed after");
    }

    @Test
    void skips_fallback_when_same_as_primary() {
        // Configure routing where fallback == primary (same instance)
        var routing = new MultiModelProperties.RoutingConfig(
                Map.of("think", "same-model", "research", "same-model",
                        "write", "same-model", "notify", "same-model"),
                "same-model");
        var properties = new MultiModelProperties(Map.of(), routing);

        when(chatClientProvider.resolve("same-model")).thenReturn(primaryChatClient);

        Resource thinkPrompt = new ByteArrayResource("think {taskDescription} {stepName}".getBytes());
        Resource researchPrompt = new ByteArrayResource("research {taskDescription} {stepName} {previousContext}".getBytes());
        Resource writePrompt = new ByteArrayResource("write {taskDescription} {stepName} {previousContext}".getBytes());
        Resource notifyPrompt = new ByteArrayResource("notify {taskDescription} {stepName} {previousContext}".getBytes());

        var sameRouter = new StepExecutorRouter(
                chatClientProvider, properties,
                thinkPrompt, researchPrompt, writePrompt, notifyPrompt,
                "https://api.github.com", "",
                Duration.ofSeconds(5), Duration.ofSeconds(10), 5,
                "", Duration.ofSeconds(5), Duration.ofSeconds(10));

        // Primary fails all retries
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("persistent failure"));

        var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());

        // Should throw directly without trying fallback (same instance)
        assertThatThrownBy(() -> sameRouter.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed after 2 attempts");

        // Only primary attempts (MAX_RETRIES), no separate fallback
        verify(primaryChatClient, times(LlmStepExecutor.MAX_RETRIES)).prompt();
    }

    @Test
    void uses_different_chat_clients_per_step_type() {
        var thinkClient = mock(ChatClient.class);
        var researchClient = mock(ChatClient.class);
        var thinkReqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var researchReqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var thinkCallSpec2 = mock(ChatClient.CallResponseSpec.class);
        var researchCallSpec2 = mock(ChatClient.CallResponseSpec.class);

        when(chatClientProvider.resolve("strong")).thenReturn(thinkClient);
        when(chatClientProvider.resolve("fast")).thenReturn(researchClient);
        when(chatClientProvider.resolve("fallback")).thenReturn(fallbackChatClient);

        when(thinkClient.prompt()).thenReturn(thinkReqSpec);
        when(thinkReqSpec.user(any(Consumer.class))).thenReturn(thinkReqSpec);
        when(thinkReqSpec.call()).thenReturn(thinkCallSpec2);
        when(thinkCallSpec2.content()).thenReturn("Think output");

        when(researchClient.prompt()).thenReturn(researchReqSpec);
        when(researchReqSpec.user(any(Consumer.class))).thenReturn(researchReqSpec);
        when(researchReqSpec.tools(any(Object.class))).thenReturn(researchReqSpec);
        when(researchReqSpec.call()).thenReturn(researchCallSpec2);
        when(researchCallSpec2.content()).thenReturn("Research output");

        var routing = new MultiModelProperties.RoutingConfig(
                Map.of("think", "strong", "research", "fast",
                        "write", "strong", "notify", "fast"),
                "fallback");
        var properties = new MultiModelProperties(Map.of(), routing);

        Resource thinkPrompt = new ByteArrayResource("think {taskDescription} {stepName}".getBytes());
        Resource researchPrompt = new ByteArrayResource("research {taskDescription} {stepName} {previousContext}".getBytes());
        Resource writePrompt = new ByteArrayResource("write {taskDescription} {stepName} {previousContext}".getBytes());
        Resource notifyPrompt = new ByteArrayResource("notify {taskDescription} {stepName} {previousContext}".getBytes());

        var multiRouter = new StepExecutorRouter(
                chatClientProvider, properties,
                thinkPrompt, researchPrompt, writePrompt, notifyPrompt,
                "https://api.github.com", "",
                Duration.ofSeconds(5), Duration.ofSeconds(10), 5,
                "", Duration.ofSeconds(5), Duration.ofSeconds(10));

        var thinkResult = multiRouter.execute(
                new StepExecutionContext("task", "分析", StepType.THINK, List.of()));
        var researchResult = multiRouter.execute(
                new StepExecutionContext("task", "调研", StepType.RESEARCH, List.of()));

        assertThat(thinkResult.output()).isEqualTo("Think output");
        assertThat(researchResult.output()).isEqualTo("Research output");
        verify(thinkClient, atLeastOnce()).prompt();
        verify(researchClient, atLeastOnce()).prompt();
    }
}
