package com.echoflow.infrastructure.ai;

import com.echoflow.application.execution.StepExecutionContext;
import com.echoflow.application.execution.StepExecutionException;
import com.echoflow.application.execution.StepOutput;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepExecutorRouterTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callSpec;

    private StepExecutorRouter router;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any(Object.class))).thenReturn(requestSpec);

        Resource thinkPrompt = new ByteArrayResource("think {taskDescription} {stepName}".getBytes());
        Resource researchPrompt = new ByteArrayResource("research {taskDescription} {stepName} {previousContext}".getBytes());
        Resource writePrompt = new ByteArrayResource("write {taskDescription} {stepName} {previousContext}".getBytes());
        Resource notifyPrompt = new ByteArrayResource("notify {taskDescription} {stepName} {previousContext}".getBytes());

        router = new StepExecutorRouter(
                chatClientBuilder, thinkPrompt, researchPrompt, writePrompt, notifyPrompt,
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
        verify(chatClient, atLeastOnce()).prompt();
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
        verify(chatClient, atLeastOnce()).prompt();
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

        var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());

        assertThatThrownBy(() -> router.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed after");
    }

    @Test
    void rejects_null_llm_output() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(null);

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
    void fails_after_max_retries() {
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("persistent failure"));

        var context = new StepExecutionContext("task desc", "分析", StepType.THINK, List.of());

        assertThatThrownBy(() -> router.execute(context))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("failed after 2 attempts");
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
}
