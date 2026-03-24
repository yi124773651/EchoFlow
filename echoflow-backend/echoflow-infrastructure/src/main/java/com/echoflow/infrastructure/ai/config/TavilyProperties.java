package com.echoflow.infrastructure.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "echoflow.tools.tavily")
public record TavilyProperties(
    String apiKey,
    String baseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    String searchDepth,
    int maxRawContentLength
) {
    public TavilyProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.tavily.com";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(15);
        }
        if (searchDepth == null || searchDepth.isBlank()) {
            searchDepth = "basic";
        }
        if (maxRawContentLength <= 0) {
            maxRawContentLength = 8000;
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
