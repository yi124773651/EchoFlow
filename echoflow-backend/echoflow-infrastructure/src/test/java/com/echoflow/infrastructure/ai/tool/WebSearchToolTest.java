package com.echoflow.infrastructure.ai.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSearchToolTest {

    @Mock
    WebSearchProvider searchProvider;

    @InjectMocks
    WebSearchTool webSearchTool;

    @Test
    void should_return_error_when_query_is_null() {
        var result = webSearchTool.search(null, false);
        assertThat(result).contains("Error");
        verifyNoInteractions(searchProvider);
    }

    @Test
    void should_return_error_when_query_is_blank() {
        var result = webSearchTool.search("  ", false);
        assertThat(result).contains("Error");
        verifyNoInteractions(searchProvider);
    }

    @Test
    void should_return_error_when_query_exceeds_400_chars() {
        var longQuery = "a".repeat(401);
        var result = webSearchTool.search(longQuery, false);
        assertThat(result).contains("400");
        verifyNoInteractions(searchProvider);
    }

    @Test
    void should_use_max_5_results_for_snippet_mode() {
        when(searchProvider.search(any())).thenReturn(List.of(
            new WebSearchProvider.SearchResult("Title", "https://example.com", "Snippet", null)
        ));

        webSearchTool.search("test query", false);

        verify(searchProvider).search(argThat(req ->
            req.maxResults() == 5 && !req.includeRawContent()
        ));
    }

    @Test
    void should_use_max_3_results_for_raw_content_mode() {
        when(searchProvider.search(any())).thenReturn(List.of(
            new WebSearchProvider.SearchResult("Title", "https://example.com", "Snippet", "Full content")
        ));

        webSearchTool.search("test query", true);

        verify(searchProvider).search(argThat(req ->
            req.maxResults() == 3 && req.includeRawContent()
        ));
    }

    @Test
    void should_return_no_results_message_when_empty() {
        when(searchProvider.search(any())).thenReturn(List.of());

        var result = webSearchTool.search("test query", false);

        assertThat(result).contains("No results found");
    }

    @Test
    void should_format_snippet_results_as_markdown() {
        when(searchProvider.search(any())).thenReturn(List.of(
            new WebSearchProvider.SearchResult("Result 1", "https://example.com/1", "Snippet 1", null),
            new WebSearchProvider.SearchResult("Result 2", "https://example.com/2", "Snippet 2", null)
        ));

        var result = webSearchTool.search("test query", false);

        assertThat(result)
            .contains("Result 1")
            .contains("https://example.com/1")
            .contains("Snippet 1")
            .contains("Result 2");
    }

    @Test
    void should_include_raw_content_in_output_when_requested() {
        when(searchProvider.search(any())).thenReturn(List.of(
            new WebSearchProvider.SearchResult("Title", "https://example.com", "Snippet", "Full page content here")
        ));

        var result = webSearchTool.search("test query", true);

        assertThat(result).contains("Full page content here");
    }

    @Test
    void should_return_error_when_provider_throws_exception() {
        when(searchProvider.search(any())).thenThrow(new RuntimeException("connection refused"));

        var result = webSearchTool.search("test query", false);

        assertThat(result).contains("Error");
    }
}
