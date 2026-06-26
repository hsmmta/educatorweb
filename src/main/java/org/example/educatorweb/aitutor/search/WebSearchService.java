package org.example.educatorweb.aitutor.search;

import java.util.List;

/**
 * Internet search interface for AiTutor.
 * Provides web results as a fallback when the private think-tank and knowledge
 * graph don't have enough information.
 */
public interface WebSearchService {

    /**
     * Search the web for a query.
     *
     * @param query the search query
     * @param maxResults maximum number of results to return (typically 3-5)
     * @return list of search results (title, snippet, url), never null
     */
    List<SearchResult> search(String query, int maxResults);

    record SearchResult(String title, String snippet, String url) {}
}
