package org.example.educatorweb.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls DeepSeek's embedding API to convert text to vectors.
 * Uses the same DEEPSEEK_API_KEY as the chat provider.
 */
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final String EMBEDDING_URL = "https://api.deepseek.com/v1/embeddings";
    private static final String EMBEDDING_MODEL = "deepseek-chat";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmbeddingService(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate embedding vector for a single text.
     * @return float array of embedding dimensions, or empty array on failure
     */
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    /**
     * Generate embedding vectors for multiple texts in one API call.
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        try {
            Map<String, Object> body = Map.of(
                "model", EMBEDDING_MODEL,
                "input", texts
            );
            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EMBEDDING_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Embedding API returned {}: {}", response.statusCode(), response.body());
                return texts.stream().map(t -> new float[0]).toList();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.get("data");
            List<float[]> embeddings = new ArrayList<>();

            for (JsonNode item : data) {
                JsonNode embArray = item.get("embedding");
                float[] vec = new float[embArray.size()];
                for (int i = 0; i < embArray.size(); i++) {
                    vec[i] = embArray.get(i).floatValue();
                }
                embeddings.add(vec);
            }

            log.debug("EmbeddingService: generated {} embeddings, dimension={}",
                embeddings.size(), embeddings.isEmpty() ? 0 : embeddings.get(0).length);
            return embeddings;

        } catch (IOException | InterruptedException e) {
            log.error("EmbeddingService: API call failed: {}", e.getMessage());
            return texts.stream().map(t -> new float[0]).toList();
        }
    }
}
