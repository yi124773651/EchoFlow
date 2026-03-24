package com.echoflow.infrastructure.ai.tool;

import com.echoflow.infrastructure.ai.config.TavilyProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

class TavilyWebSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(TavilyWebSearchProvider.class);
    private static final int MAX_RETRIES_429 = 2;
    private static final int MAX_RETRIES_5XX = 1;

    private final RestClient restClient;
    private final TavilyProperties properties;

    TavilyWebSearchProvider(TavilyProperties properties) {
        this.properties = properties;
        var httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        if (!properties.isConfigured()) {
            log.warn("Tavily API key is not configured. Web search is disabled.");
            return List.of();
        }

        var body = Map.of(
            "query", request.query(),
            "search_depth", properties.searchDepth(),
            "max_results", request.maxResults(),
            "include_raw_content", request.includeRawContent()
        );

        for (int attempt = 1; attempt <= 1 + MAX_RETRIES_429; attempt++) {
            try {
                var response = restClient.post()
                    .uri("/search")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .body(body)
                    .retrieve()
                    .body(TavilyResponse.class);

                if (response == null || response.results() == null) {
                    return List.of();
                }

                log.debug("Tavily search completed in {}s, request_id={}",
                    response.responseTime(), response.requestId());

                return response.results().stream()
                    .map(r -> new SearchResult(
                        r.title(),
                        r.url(),
                        r.content(),
                        truncateRawContent(r.rawContent())
                    ))
                    .toList();

            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                var retryAfter = e.getResponseHeaders() != null
                    ? e.getResponseHeaders().getFirst("retry-after") : null;
                var waitSeconds = parseRetryAfter(retryAfter);
                log.warn("Tavily rate limited (429). Retry after {}s (attempt {}/{})",
                    waitSeconds, attempt, MAX_RETRIES_429);
                if (attempt <= MAX_RETRIES_429) {
                    sleep(waitSeconds * 1000L);
                } else {
                    return List.of();
                }
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                log.warn("Tavily server error ({}). Attempt {}/{}",
                    e.getStatusCode(), attempt, 1 + MAX_RETRIES_5XX);
                if (attempt > MAX_RETRIES_5XX) {
                    return List.of();
                }
                sleep(1000L);
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                log.warn("Tavily client error ({}): {}", e.getStatusCode(), e.getMessage());
                return List.of();
            } catch (Exception e) {
                log.warn("Tavily search failed: {}", e.getMessage());
                return List.of();
            }
        }
        return List.of();
    }

    private String truncateRawContent(String rawContent) {
        if (rawContent == null) {
            return null;
        }
        return rawContent.length() > properties.maxRawContentLength()
            ? rawContent.substring(0, properties.maxRawContentLength())
            : rawContent;
    }

    private int parseRetryAfter(String retryAfter) {
        if (retryAfter == null) {
            return 2;
        }
        try {
            return Math.min(Integer.parseInt(retryAfter), 10);
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResponse(
        List<TavilyResult> results,
        @JsonProperty("response_time") double responseTime,
        @JsonProperty("request_id") String requestId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResult(
        String title,
        String url,
        String content,
        double score,
        @JsonProperty("raw_content") String rawContent
    ) {}
}
