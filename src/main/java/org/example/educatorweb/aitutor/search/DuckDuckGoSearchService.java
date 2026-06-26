package org.example.educatorweb.aitutor.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DuckDuckGo Instant Answer API implementation.
 * Free, no API key required. Returns structured results for web queries.
 */
@Service
public class DuckDuckGoSearchService implements WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(DuckDuckGoSearchService.class);

    private static final String API_URL = "https://api.duckduckgo.com/";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public DuckDuckGoSearchService() {
        this.webClient = WebClient.builder()
            .baseUrl(API_URL)
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        List<SearchResult> results = new ArrayList<>();

        try {
            String response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .queryParam("q", query)
                    .queryParam("format", "json")
                    .queryParam("no_html", "1")
                    .queryParam("skip_disambig", "1")
                    .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .onErrorReturn("")
                .block();

            if (response == null || response.isBlank()) {
                log.debug("DuckDuckGo: empty response for query '{}'", query);
                return results;
            }

            JsonNode root = objectMapper.readTree(response);

            // 1. Abstract text (direct answer)
            String abstractText = fieldText(root, "AbstractText");
            String abstractUrl = fieldText(root, "AbstractURL");
            if (!abstractText.isBlank()) {
                results.add(new SearchResult(
                    fieldText(root, "Heading").isBlank() ? "DuckDuckGo" : fieldText(root, "Heading"),
                    abstractText,
                    abstractUrl.isBlank() ? ("https://duckduckgo.com/?q=" + query) : abstractUrl
                ));
            }

            // 2. Related topics
            JsonNode relatedTopics = root.get("RelatedTopics");
            if (relatedTopics != null && relatedTopics.isArray()) {
                for (JsonNode topic : relatedTopics) {
                    if (results.size() >= maxResults) break;
                    String text = fieldText(topic, "Text");
                    String url = fieldText(topic, "FirstURL");
                    if (!text.isBlank()) {
                        String title = text.length() > 80 ? text.substring(0, 80) + "..." : text;
                        results.add(new SearchResult(title, text, url.isBlank()
                            ? ("https://duckduckgo.com/?q=" + query) : url));
                    }
                }
            }

            // 3. Infobox content as supplementary
            JsonNode infobox = root.get("Infobox");
            if (infobox != null && !infobox.isEmpty() && results.size() < maxResults) {
                StringBuilder sb = new StringBuilder();
                JsonNode content = infobox.get("content");
                if (content != null && content.isArray()) {
                    for (int i = 0; i < Math.min(content.size(), 5); i++) {
                        JsonNode item = content.get(i);
                        String label = fieldText(item, "label");
                        String value = fieldText(item, "value");
                        if (!label.isBlank() && !value.isBlank()) {
                            sb.append(label).append(": ").append(value).append("; ");
                        }
                    }
                }
                if (!sb.isEmpty()) {
                    results.add(new SearchResult("百科数据", sb.toString(),
                        "https://duckduckgo.com/?q=" + query));
                }
            }

            log.debug("DuckDuckGo: query '{}' returned {} results", query, results.size());
        } catch (Exception e) {
            log.warn("DuckDuckGo: search failed for '{}': {}", query, e.getMessage());
        }

        return results;
    }

    private String fieldText(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull() ? fieldNode.asText().trim() : "";
    }
}
