package com.echoflow.infrastructure.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ReviewResultParserTest {

    @Test
    void parse_valid_approved_block() {
        var llmOutput = """
                Some preamble text...

                [REVIEW]
                score: 92
                approved: YES
                feedback: No issues found
                """;

        var result = ReviewResultParser.parse(llmOutput);

        assertThat(result.score()).isEqualTo(92);
        assertThat(result.approved()).isTrue();
        assertThat(result.feedback()).isEqualTo("No issues found");
    }

    @Test
    void parse_valid_rejected_block() {
        var llmOutput = """
                [REVIEW]
                score: 45
                approved: NO
                feedback: The report lacks concrete examples and needs better structure.
                """;

        var result = ReviewResultParser.parse(llmOutput);

        assertThat(result.score()).isEqualTo(45);
        assertThat(result.approved()).isFalse();
        assertThat(result.feedback()).contains("lacks concrete examples");
    }

    @Test
    void parse_missing_block_returns_default_approved() {
        var result = ReviewResultParser.parse("Some random LLM output without review block");

        assertThat(result).isEqualTo(ReviewResult.DEFAULT_APPROVED);
        assertThat(result.approved()).isTrue();
        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void parse_null_input_returns_default_approved() {
        assertThat(ReviewResultParser.parse(null)).isEqualTo(ReviewResult.DEFAULT_APPROVED);
    }

    @Test
    void parse_blank_input_returns_default_approved() {
        assertThat(ReviewResultParser.parse("  ")).isEqualTo(ReviewResult.DEFAULT_APPROVED);
    }

    @Test
    void parse_malformed_block_returns_default_approved() {
        var llmOutput = """
                [REVIEW]
                score: not_a_number
                approved: MAYBE
                """;

        assertThat(ReviewResultParser.parse(llmOutput)).isEqualTo(ReviewResult.DEFAULT_APPROVED);
    }

    @Test
    void parse_case_insensitive() {
        var llmOutput = """
                [REVIEW]
                score: 78
                approved: no
                feedback: Needs improvement
                """;

        var result = ReviewResultParser.parse(llmOutput);

        assertThat(result.score()).isEqualTo(78);
        assertThat(result.approved()).isFalse();
        assertThat(result.feedback()).isEqualTo("Needs improvement");
    }

    @Test
    void parse_without_feedback_line() {
        var llmOutput = """
                [REVIEW]
                score: 95
                approved: YES
                """;

        var result = ReviewResultParser.parse(llmOutput);

        assertThat(result.score()).isEqualTo(95);
        assertThat(result.approved()).isTrue();
        assertThat(result.feedback()).isEmpty();
    }
}
