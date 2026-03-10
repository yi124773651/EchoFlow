package com.echoflow.infrastructure.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class GitHubSearchToolTest {

    @Test
    void searchRepositories_rejects_blank_query() {
        var tool = createTool("https://api.github.com");
        var result = tool.searchRepositories("   ");

        assertThat(result).contains("Error: search query must not be empty");
    }

    @Test
    void searchRepositories_rejects_null_query() {
        var tool = createTool("https://api.github.com");
        var result = tool.searchRepositories(null);

        assertThat(result).contains("Error: search query must not be empty");
    }

    @Test
    void searchRepositories_returns_fallback_on_connection_error() {
        // Point to a non-routable address to trigger connection failure
        var tool = createTool("http://localhost:1");
        var result = tool.searchRepositories("java redis");

        assertThat(result).contains("GitHub search temporarily unavailable");
        assertThat(result).contains("Please proceed with your existing knowledge");
    }

    @Test
    void constructor_accepts_blank_token() {
        // Should not throw — blank token means unauthenticated access
        assertThatCode(() -> createTool("https://api.github.com"))
                .doesNotThrowAnyException();
    }

    private GitHubSearchTool createTool(String baseUrl) {
        return new GitHubSearchTool(
                baseUrl, "",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 5);
    }
}
