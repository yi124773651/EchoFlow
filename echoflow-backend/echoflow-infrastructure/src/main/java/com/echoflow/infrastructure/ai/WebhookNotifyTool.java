package com.echoflow.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

/**
 * Webhook notification tool for Spring AI function calling.
 *
 * <p>Sends a JSON payload to a configured Webhook URL.
 * Returns a human-readable result message for LLM consumption.</p>
 *
 * <p>Package-private — only used by {@link LlmNotifyExecutor} via
 * {@link StepExecutorRouter} wiring.</p>
 */
class WebhookNotifyTool {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifyTool.class);

    private final RestClient restClient;
    private final String webhookUrl;

    WebhookNotifyTool(String webhookUrl,
                      Duration connectTimeout,
                      Duration readTimeout) {
        this.webhookUrl = webhookUrl;

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "EchoFlow/0.1")
                .build();
    }

    @Tool(description = "Send a notification via Webhook. " +
            "Use this to deliver task completion summaries, important findings, or alerts. " +
            "Provide a clear title and a concise summary of what to notify about.")
    String sendNotification(
            @ToolParam(description = "Short notification title, e.g. 'Task Completed: Market Analysis'")
            String title,
            @ToolParam(description = "Notification summary in plain text, 1-3 paragraphs. " +
                    "Include key findings or results from previous steps.")
            String summary) {

        if (title == null || title.isBlank()) {
            return "Error: notification title must not be empty.";
        }
        if (summary == null || summary.isBlank()) {
            return "Error: notification summary must not be empty.";
        }

        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("Webhook URL not configured, notification logged only: {}", title);
            return "Notification recorded (Webhook not configured): " + title;
        }

        log.info("Sending webhook notification: title='{}'", title);

        try {
            var payload = new WebhookPayload(title, summary, Instant.now().toString());

            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            return "Notification sent successfully: " + title;
        } catch (Exception e) {
            log.warn("Webhook notification failed for '{}': {}", title, e.getMessage());
            return "Webhook delivery failed: " + e.getMessage()
                    + ". The notification content has been recorded in the execution log.";
        }
    }

    record WebhookPayload(String title, String summary, String timestamp) {}
}
