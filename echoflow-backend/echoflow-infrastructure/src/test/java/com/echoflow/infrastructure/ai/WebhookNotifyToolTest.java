package com.echoflow.infrastructure.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class WebhookNotifyToolTest {

    @Test
    void sendNotification_rejects_blank_title() {
        var tool = createTool("");
        var result = tool.sendNotification("   ", "Some summary");

        assertThat(result).contains("Error: notification title must not be empty");
    }

    @Test
    void sendNotification_rejects_blank_summary() {
        var tool = createTool("");
        var result = tool.sendNotification("Task Done", "   ");

        assertThat(result).contains("Error: notification summary must not be empty");
    }

    @Test
    void sendNotification_returns_fallback_when_url_not_configured() {
        var tool = createTool("");
        var result = tool.sendNotification("Task Done", "Key findings here");

        assertThat(result).contains("Notification recorded");
        assertThat(result).contains("Webhook not configured");
        assertThat(result).contains("Task Done");
    }

    @Test
    void sendNotification_returns_fallback_on_connection_error() {
        // Point to a non-routable address to trigger connection failure
        var tool = createTool("http://localhost:1");
        var result = tool.sendNotification("Task Done", "Key findings here");

        assertThat(result).contains("Webhook delivery failed");
    }

    @Test
    void constructor_accepts_blank_url() {
        // Should not throw — blank URL means notification logging only
        assertThatCode(() -> createTool(""))
                .doesNotThrowAnyException();
    }

    private WebhookNotifyTool createTool(String webhookUrl) {
        return new WebhookNotifyTool(
                webhookUrl,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
