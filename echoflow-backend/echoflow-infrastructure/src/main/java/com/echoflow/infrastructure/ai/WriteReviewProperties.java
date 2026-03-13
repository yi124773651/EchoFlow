package com.echoflow.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the WRITE review loop.
 *
 * <p>When {@code enabled} is true, WRITE steps are wrapped with an
 * LLM-as-Judge review loop that evaluates output quality and triggers
 * revision if the score is below the threshold.</p>
 *
 * <pre>
 * echoflow:
 *   review:
 *     enabled: true
 *     max-attempts: 3
 *     quality-threshold: 80
 * </pre>
 */
@ConfigurationProperties(prefix = "echoflow.review")
public record WriteReviewProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("80") int qualityThreshold
) {}
