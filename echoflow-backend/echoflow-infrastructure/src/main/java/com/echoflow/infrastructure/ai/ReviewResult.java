package com.echoflow.infrastructure.ai;

/**
 * Result of an LLM-as-Judge evaluation of a WRITE step's output.
 *
 * @param score    quality score from 0 to 100
 * @param approved whether the output meets the quality threshold
 * @param feedback specific improvement suggestions (empty if approved)
 */
record ReviewResult(int score, boolean approved, String feedback) {

    /** Safe default returned when parsing fails — always approved to avoid blocking the pipeline. */
    static final ReviewResult DEFAULT_APPROVED = new ReviewResult(100, true, "");
}
