package com.echoflow.infrastructure.ai.tool;

import java.util.List;

interface WebSearchProvider {

    record SearchRequest(
        String query,
        boolean includeRawContent,
        int maxResults
    ) {}

    record SearchResult(
        String title,
        String url,
        String snippet,
        String rawContent
    ) {}

    List<SearchResult> search(SearchRequest request);
}
