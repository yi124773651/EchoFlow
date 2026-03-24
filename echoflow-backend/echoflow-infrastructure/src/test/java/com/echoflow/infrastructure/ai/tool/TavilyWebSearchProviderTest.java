package com.echoflow.infrastructure.ai.tool;

import com.echoflow.infrastructure.ai.config.TavilyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyWebSearchProviderTest {

    MockWebServer mockServer;
    ObjectMapper objectMapper = new ObjectMapper();
    TavilyWebSearchProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        var properties = new TavilyProperties(
            "tvly-test-key",
            mockServer.url("/").toString(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            "basic",
            8000
        );
        provider = new TavilyWebSearchProvider(properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void should_send_correct_request_to_tavily() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(successResponse()));

        provider.search(new WebSearchProvider.SearchRequest("Java 21 features", false, 5));

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/search");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tvly-test-key");
        assertThat(request.getHeader("Content-Type")).contains("application/json");

        var body = objectMapper.readValue(request.getBody().readUtf8(), Map.class);
        assertThat(body.get("query")).isEqualTo("Java 21 features");
        assertThat(body.get("max_results")).isEqualTo(5);
        assertThat(body.get("include_raw_content")).isEqualTo(false);
        assertThat(body.get("search_depth")).isEqualTo("basic");
    }

    @Test
    void should_parse_snippet_results() {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(successResponse()));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", false, 5));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().title()).isEqualTo("Test Title");
        assertThat(results.getFirst().url()).isEqualTo("https://example.com");
        assertThat(results.getFirst().snippet()).isEqualTo("Test content snippet");
        assertThat(results.getFirst().rawContent()).isNull();
    }

    @Test
    void should_parse_raw_content_and_truncate_when_exceeding_limit() {
        var longContent = "x".repeat(10000);
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(responseWithRawContent(longContent)));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", true, 3));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rawContent()).hasSize(8000);
    }

    @Test
    void should_handle_null_raw_content_gracefully() {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(successResponse()));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", true, 3));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rawContent()).isNull();
    }

    @Test
    void should_return_empty_list_on_http_401() {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(401)
            .setBody("{\"detail\":{\"error\":\"Invalid API key\"}}"));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", false, 5));

        assertThat(results).isEmpty();
    }

    @Test
    void should_return_empty_list_on_http_400() {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(400)
            .setBody("{\"detail\":{\"error\":\"Bad request\"}}"));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", false, 5));

        assertThat(results).isEmpty();
    }

    @Test
    void should_retry_once_on_http_500_then_return_empty() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", false, 5));

        assertThat(results).isEmpty();
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void should_retry_on_http_429_and_succeed() {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(429)
            .setHeader("retry-after", "1"));
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(successResponse()));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", false, 5));

        assertThat(results).hasSize(1);
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void should_return_empty_list_when_api_key_not_configured() {
        var unconfiguredProps = new TavilyProperties(
            "", mockServer.url("/").toString(),
            Duration.ofSeconds(5), Duration.ofSeconds(10), "basic", 8000
        );
        var unconfiguredProvider = new TavilyWebSearchProvider(unconfiguredProps);

        var results = unconfiguredProvider.search(
            new WebSearchProvider.SearchRequest("test", false, 5));

        assertThat(results).isEmpty();
        assertThat(mockServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    void should_return_empty_list_on_empty_results_array() {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"results\":[],\"response_time\":0.5,\"request_id\":\"abc\"}"));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", false, 5));

        assertThat(results).isEmpty();
    }

    @Test
    void should_return_empty_list_on_http_432_plan_limit() {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(432)
            .setBody("{\"detail\":{\"error\":\"Plan limit exceeded\"}}"));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", false, 5));

        assertThat(results).isEmpty();
    }

    @Test
    void should_return_empty_list_on_http_433_paygo_limit() {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(433)
            .setBody("{\"detail\":{\"error\":\"PayGo limit exceeded\"}}"));

        var results = provider.search(
            new WebSearchProvider.SearchRequest("test", false, 5));

        assertThat(results).isEmpty();
    }

    // --- Helper methods ---

    private String successResponse() {
        return """
            {
              "results": [
                {
                  "title": "Test Title",
                  "url": "https://example.com",
                  "content": "Test content snippet",
                  "score": 0.95,
                  "raw_content": null
                }
              ],
              "response_time": 1.23,
              "request_id": "req-123"
            }
            """;
    }

    private String responseWithRawContent(String rawContent) {
        return """
            {
              "results": [
                {
                  "title": "Test Title",
                  "url": "https://example.com",
                  "content": "Test content snippet",
                  "score": 0.95,
                  "raw_content": "%s"
                }
              ],
              "response_time": 1.23,
              "request_id": "req-123"
            }
            """.formatted(rawContent);
    }
}
