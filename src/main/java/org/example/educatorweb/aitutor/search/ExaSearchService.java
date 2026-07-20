package org.example.educatorweb.aitutor.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Exa AI semantic search — primary web search backend.
 * Falls back to DuckDuckGo when Exa is unavailable or returns no results.
 *
 * Exa free tier: 100 searches/month, no API key required.
 * With API key: 1000 searches/month. Set EXA_API_KEY env var.
 */
@Service
@Primary
public class ExaSearchService implements WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(ExaSearchService.class);
    private static final String EXA_URL = "https://api.exa.ai/search";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final DuckDuckGoSearchService fallback;

    public ExaSearchService() {
        this.webClient = WebClient.builder()
            .baseUrl(EXA_URL)
            .defaultHeader("x-api-key", System.getenv().getOrDefault("EXA_API_KEY", ""))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(512 * 1024))
            .build();
        this.mapper = new ObjectMapper();
        this.fallback = new DuckDuckGoSearchService();
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        // Try Exa first
        List<SearchResult> results = searchExa(query, maxResults);
        if (!results.isEmpty()) {
            log.debug("Exa: {} results for '{}'", results.size(), query);
            return results;
        }

        // Fall back to DuckDuckGo
        log.debug("Exa: no results for '{}', falling back to DuckDuckGo", query);
        return fallback.search(query, maxResults);
    }

    private List<SearchResult> searchExa(String query, int maxResults) {
        List<SearchResult> results = new ArrayList<>();
        try {
            String response = webClient.post()
                .bodyValue(mapper.createObjectNode()
                    .put("query", query)
                    .put("numResults", maxResults)
                    .put("type", "auto")
                    .put("contents", mapper.createObjectNode()
                        .put("text", true)
                        .put("highlights", mapper.createObjectNode()
                            .put("numSentences", 2)
                            .put("highlightsPerUrl", 1))))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .onErrorReturn("")
                .block();

            if (response == null || response.isBlank()) return results;

            JsonNode root = mapper.readTree(response);
            JsonNode items = root.get("results");
            if (items == null || !items.isArray()) return results;

            for (JsonNode item : items) {
                if (results.size() >= maxResults) break;
                String title = fieldText(item, "title");
                String snippet = fieldText(item, "text");
                String url = fieldText(item, "url");
                // If text is empty, try highlights
                if (snippet.isBlank()) {
                    JsonNode highlights = item.get("highlights");
                    if (highlights != null && highlights.isArray() && !highlights.isEmpty()) {
                        snippet = highlights.get(0).asText();
                    }
                }
                if (title.isBlank() && snippet.isBlank()) continue;
                results.add(new SearchResult(
                    title.isBlank() ? url : title,
                    snippet.isBlank() ? title : snippet,
                    url.isBlank() ? "" : url));
            }
        } catch (Exception e) {
            log.warn("Exa: search failed for '{}': {}", query, e.getMessage());
        }
        return results;
    }

    private String fieldText(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode fn = node.get(field);
        return fn != null && !fn.isNull() ? fn.asText().trim() : "";
    }
}
