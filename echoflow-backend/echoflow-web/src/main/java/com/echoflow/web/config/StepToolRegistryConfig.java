package com.echoflow.web.config;

import com.echoflow.domain.execution.StepType;
import com.echoflow.infrastructure.ai.config.TavilyProperties;
import com.echoflow.infrastructure.ai.executor.StepToolRegistry;
import com.echoflow.infrastructure.ai.tool.GitHubSearchTool;
import com.echoflow.infrastructure.ai.tool.WebSearchTool;
import com.echoflow.infrastructure.ai.tool.WebhookNotifyTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(TavilyProperties.class)
class StepToolRegistryConfig {

    @Bean
    GitHubSearchTool gitHubSearchTool(
            @Value("${echoflow.github.api-base-url:https://api.github.com}") String baseUrl,
            @Value("${echoflow.github.token:}") String token,
            @Value("${echoflow.github.connect-timeout:5s}") Duration connectTimeout,
            @Value("${echoflow.github.read-timeout:10s}") Duration readTimeout,
            @Value("${echoflow.github.max-results:5}") int maxResults) {
        return new GitHubSearchTool(baseUrl, token, connectTimeout, readTimeout, maxResults);
    }

    @Bean
    WebhookNotifyTool webhookNotifyTool(
            @Value("${echoflow.webhook.url:}") String url,
            @Value("${echoflow.webhook.connect-timeout:5s}") Duration connectTimeout,
            @Value("${echoflow.webhook.read-timeout:10s}") Duration readTimeout) {
        return new WebhookNotifyTool(url, connectTimeout, readTimeout);
    }

    @Bean
    WebSearchTool webSearchTool(TavilyProperties tavilyProperties) {
        return WebSearchTool.create(tavilyProperties);
    }

    @Bean
    StepToolRegistry stepToolRegistry(
            GitHubSearchTool gitHubSearchTool,
            WebhookNotifyTool webhookNotifyTool,
            WebSearchTool webSearchTool) {
        return new StepToolRegistry(Map.of(
                StepType.THINK, List.of(webSearchTool),
                StepType.RESEARCH, List.of(gitHubSearchTool, webSearchTool),
                StepType.WRITE, List.of(),
                StepType.NOTIFY, List.of(webhookNotifyTool)));
    }
}
