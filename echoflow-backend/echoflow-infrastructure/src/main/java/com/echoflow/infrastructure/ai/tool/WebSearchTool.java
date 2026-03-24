package com.echoflow.infrastructure.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.stream.IntStream;

public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private final WebSearchProvider searchProvider;

    public WebSearchTool(WebSearchProvider searchProvider) {
        this.searchProvider = searchProvider;
    }

    @Tool(description = "Search the web for real-time information on any topic. "
        + "Returns titles, URLs, and content snippets from web pages. "
        + "Set includeRawContent to true only when you need full page content for in-depth analysis.")
    String search(
        @ToolParam(description = "Search query keywords, max 400 characters")
        String query,
        @ToolParam(description = "Set true to include full page content, false for snippets only")
        boolean includeRawContent
    ) {
        if (query == null || query.isBlank()) {
            return "Error: search query cannot be empty.";
        }
        if (query.length() > 400) {
            return "Error: search query exceeds 400 character limit.";
        }

        int maxResults = includeRawContent ? 3 : 5;

        List<WebSearchProvider.SearchResult> results;
        try {
            results = searchProvider.search(
                new WebSearchProvider.SearchRequest(query, includeRawContent, maxResults));
        } catch (Exception e) {
            log.warn("Web search failed: {}", e.getMessage());
            return "Error: web search unavailable. " + e.getMessage();
        }

        if (results.isEmpty()) {
            return "No results found for: " + query;
        }

        return formatResults(query, results, includeRawContent);
    }

    private String formatResults(String query, List<WebSearchProvider.SearchResult> results,
                                  boolean includeRawContent) {
        var sb = new StringBuilder();
        sb.append("## Search Results for \"").append(query).append("\"\n\n");

        IntStream.range(0, results.size()).forEach(i -> {
            var r = results.get(i);
            sb.append(i + 1).append(". **").append(r.title()).append("** (").append(r.url()).append(")\n");
            sb.append("   ").append(r.snippet()).append("\n");
            if (includeRawContent && r.rawContent() != null && !r.rawContent().isBlank()) {
                sb.append("   **[Full Content]:** ").append(r.rawContent()).append("\n");
            }
            sb.append("\n");
        });

        return sb.toString();
    }
}
