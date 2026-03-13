package com.echoflow.infrastructure.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Conditional configuration for the WRITE review loop.
 *
 * <p>When {@code echoflow.review.enabled=true}, creates an {@link LlmWriteReviewer}
 * bean that enables the review loop in {@link GraphOrchestrator}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "echoflow.review", name = "enabled", havingValue = "true")
class WriteReviewConfig {

    @Bean
    LlmWriteReviewer llmWriteReviewer(
            @Value("classpath:prompts/review-write.st") Resource promptResource,
            ChatClientProvider chatClientProvider) throws IOException {
        var promptContent = promptResource.getContentAsString(StandardCharsets.UTF_8);
        return new LlmWriteReviewer(promptContent, chatClientProvider.defaultClient());
    }
}
