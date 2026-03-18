package com.echoflow.infrastructure.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for human-in-the-loop approval.
 */
@ConfigurationProperties(prefix = "echoflow.approval")
public record HumanApprovalProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30") int timeoutMinutes
) {}
