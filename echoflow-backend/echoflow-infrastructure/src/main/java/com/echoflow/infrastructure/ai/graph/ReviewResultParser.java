package com.echoflow.infrastructure.ai.graph;

import java.util.regex.Pattern;

/**
 * Parses the {@code [REVIEW]} block from LLM-as-Judge output.
 *
 * <p>Expected format:
 * <pre>
 * [REVIEW]
 * score: 85
 * approved: YES
 * feedback: No issues found
 * </pre>
 *
 * <p>If the block is missing, malformed, or contains unexpected values,
 * returns {@link ReviewResult#DEFAULT_APPROVED} (safe default = always approve).</p>
 */
final class ReviewResultParser {

    private static final Pattern REVIEW_BLOCK = Pattern.compile(
            "\\[REVIEW]\\s*\\n\\s*score:\\s*(\\d+)\\s*\\n\\s*approved:\\s*(YES|NO)(?:\\s*\\n\\s*feedback:\\s*(.+))?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ReviewResultParser() {
    }

    /**
     * Parse a review result from LLM-as-Judge output.
     *
     * @param llmOutput the raw LLM output (may be null)
     * @return parsed result, or {@link ReviewResult#DEFAULT_APPROVED} on any parse failure
     */
    static ReviewResult parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return ReviewResult.DEFAULT_APPROVED;
        }
        var matcher = REVIEW_BLOCK.matcher(llmOutput);
        if (!matcher.find()) {
            return ReviewResult.DEFAULT_APPROVED;
        }
        try {
            var score = Integer.parseInt(matcher.group(1).strip());
            var approved = "YES".equalsIgnoreCase(matcher.group(2).strip());
            var feedback = matcher.group(3) != null ? matcher.group(3).strip() : "";
            return new ReviewResult(score, approved, feedback);
        } catch (NumberFormatException e) {
            return ReviewResult.DEFAULT_APPROVED;
        }
    }
}
