package com.echoflow.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * GitHub Repository Search tool for Spring AI function calling.
 *
 * <p>Searches the GitHub REST API v3 for repositories matching a query.
 * Returns a pre-formatted text summary suitable for LLM consumption.</p>
 *
 * <p>Package-private — only used by {@link LlmResearchExecutor} via
 * {@link StepExecutorRouter} wiring.</p>
 */
class GitHubSearchTool {

    private static final Logger log = LoggerFactory.getLogger(GitHubSearchTool.class);
    private static final int DESCRIPTION_MAX_LENGTH = 200;

    private final RestClient restClient;
    private final int maxResults;

    GitHubSearchTool(String baseUrl, String token,
                     Duration connectTimeout, Duration readTimeout,
                     int maxResults) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        var builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .defaultHeader("User-Agent", "EchoFlow/0.1");

        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }

        this.restClient = builder.build();
        this.maxResults = maxResults;
    }

    @Tool(description = "Search GitHub repositories by keyword. " +
            "Returns a list of matching repositories with name, description, stars, and URL. " +
            "Use this when the task requires finding open-source projects, libraries, or code examples.")
    String searchRepositories(
            @ToolParam(description = "Search query keywords, e.g. 'redis java client' or 'machine learning python'")
            String query) {

        if (query == null || query.isBlank()) {
            return "Error: search query must not be empty.";
        }

        log.info("GitHub search: query='{}'", query);

        try {
            var response = restClient.get()
                    .uri("/search/repositories?q={query}&sort=stars&order=desc&per_page={perPage}",
                            query, maxResults)
                    .retrieve()
                    .body(GitHubSearchResponse.class);

            if (response == null || response.items() == null || response.items().isEmpty()) {
                return "No repositories found for query: " + query;
            }

            return formatResults(query, response);
        } catch (Exception e) {
            log.warn("GitHub search failed for query '{}': {}", query, e.getMessage());
            return "GitHub search temporarily unavailable: " + e.getMessage()
                    + ". Please proceed with your existing knowledge.";
        }
    }

    private String formatResults(String query, GitHubSearchResponse response) {
        var sb = new StringBuilder();
        sb.append("GitHub search results for '").append(query).append("'");
        sb.append(" (").append(response.totalCount()).append(" total matches, showing top ")
                .append(response.items().size()).append("):\n\n");

        for (int i = 0; i < response.items().size(); i++) {
            var item = response.items().get(i);
            sb.append(i + 1).append(". **").append(item.fullName()).append("**\n");
            if (item.description() != null && !item.description().isBlank()) {
                var desc = item.description().length() > DESCRIPTION_MAX_LENGTH
                        ? item.description().substring(0, DESCRIPTION_MAX_LENGTH) + "..."
                        : item.description();
                sb.append("   ").append(desc).append("\n");
            }
            sb.append("   Stars: ").append(item.stargazersCount());
            sb.append(" | Language: ").append(item.language() != null ? item.language() : "N/A");
            sb.append(" | URL: ").append(item.htmlUrl()).append("\n\n");
        }

        return sb.toString().strip();
    }

    /**
     * GitHub Search API response — untrusted external input, used only within this tool.
     */
    record GitHubSearchResponse(
            @JsonProperty("total_count") int totalCount,
            List<RepositoryItem> items
    ) {}

    record RepositoryItem(
            @JsonProperty("full_name") String fullName,
            String description,
            @JsonProperty("stargazers_count") int stargazersCount,
            String language,
            @JsonProperty("html_url") String htmlUrl
    ) {}
}
