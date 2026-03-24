package com.echoflow.infrastructure.ai.tool;

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

    @Test
    void withUrl_creates_copy_with_overridden_url() {
        var original = createTool("");
        var copy = original.withUrl("http://localhost:1");

        // Original still has no URL — returns fallback
        var originalResult = original.sendNotification("Title", "Summary");
        assertThat(originalResult).contains("Webhook not configured");

        // Copy uses the override URL (will fail to connect, but proves URL was set)
        var copyResult = copy.sendNotification("Title", "Summary");
        assertThat(copyResult).contains("Webhook delivery failed");
    }

    @Test
    void withUrl_normalizes_blank_to_null() {
        var original = createTool("http://localhost:1");
        var copy = original.withUrl("   ");

        var result = copy.sendNotification("Title", "Summary");
        assertThat(result).contains("Webhook not configured");
    }

    @Test
    void withUrl_with_null_falls_back_to_original() {
        var original = createTool("http://localhost:1");
        var copy = original.withUrl(null);

        // null override means use original's URL
        var result = copy.sendNotification("Title", "Summary");
        assertThat(result).contains("Webhook delivery failed");
    }

    private WebhookNotifyTool createTool(String webhookUrl) {
        return new WebhookNotifyTool(
                webhookUrl,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
