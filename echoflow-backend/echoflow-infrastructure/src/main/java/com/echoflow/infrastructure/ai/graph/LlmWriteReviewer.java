package com.echoflow.infrastructure.ai.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * LLM-as-Judge evaluator for WRITE step output quality.
 *
 * <p>Calls an LLM with a review prompt that scores the output on completeness,
 * structure, accuracy, and clarity. The LLM response is parsed by
 * {@link ReviewResultParser}.</p>
 *
 * <p>On any LLM call failure, returns {@link ReviewResult#DEFAULT_APPROVED}
 * to avoid blocking the pipeline (graceful degradation).</p>
 */
public class LlmWriteReviewer {

    private static final Logger log = LoggerFactory.getLogger(LlmWriteReviewer.class);

    private final String promptContent;
    private final ChatClient chatClient;

    public LlmWriteReviewer(String promptContent, ChatClient chatClient) {
        this.promptContent = promptContent;
        this.chatClient = chatClient;
    }

    /**
     * Evaluate the quality of a WRITE step's output.
     *
     * @param taskDescription the original task description
     * @param stepName        the step name (for prompt context)
     * @param writeOutput     the WRITE step's output to evaluate
     * @return review result with score, approved flag, and feedback
     */
    ReviewResult evaluate(String taskDescription, String stepName, String writeOutput) {
        if (writeOutput == null || writeOutput.isBlank()) {
            return ReviewResult.DEFAULT_APPROVED;
        }

        try {
            var llmOutput = chatClient.prompt()
                    .user(u -> u.text(promptContent)
                            .param("taskDescription", taskDescription)
                            .param("stepName", stepName)
                            .param("writeOutput", writeOutput))
                    .call()
                    .content();

            var result = ReviewResultParser.parse(llmOutput);
            log.info("Write review for '{}': score={}, approved={}", stepName, result.score(), result.approved());
            return result;
        } catch (Exception e) {
            log.warn("Write review LLM call failed for '{}', auto-approving: {}", stepName, e.getMessage());
            return ReviewResult.DEFAULT_APPROVED;
        }
    }
}
