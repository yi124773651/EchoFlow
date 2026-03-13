package com.echoflow.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmWriteReviewerTest {

    private static final String PROMPT = """
            Review: {taskDescription} {stepName} {writeOutput}
            """;

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    @Test
    void evaluate_parses_approved_response() {
        var reviewer = new LlmWriteReviewer(PROMPT, chatClient);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("""
                [REVIEW]
                score: 90
                approved: YES
                feedback: No issues found
                """);

        var result = reviewer.evaluate("test task", "撰写", "# Report\nContent...");

        assertThat(result.score()).isEqualTo(90);
        assertThat(result.approved()).isTrue();
    }

    @Test
    void evaluate_returns_default_on_llm_exception() {
        var reviewer = new LlmWriteReviewer(PROMPT, chatClient);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM timeout"));

        var result = reviewer.evaluate("test task", "撰写", "# Report\nContent...");

        assertThat(result).isEqualTo(ReviewResult.DEFAULT_APPROVED);
        assertThat(result.approved()).isTrue();
    }

    @Test
    void evaluate_returns_default_for_empty_output() {
        var reviewer = new LlmWriteReviewer(PROMPT, chatClient);

        var result = reviewer.evaluate("test task", "撰写", "");

        assertThat(result).isEqualTo(ReviewResult.DEFAULT_APPROVED);
        verifyNoInteractions(chatClient);
    }
}
