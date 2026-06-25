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
 * Calls Zhipu (智谱) Embedding-3 API to convert text to vectors.
 * Endpoint: https://open.bigmodel.cn/api/paas/v4/embeddings
 * Model: embedding-3, default dimension: 2048
 */
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final String EMBEDDING_URL = "https://open.bigmodel.cn/api/paas/v4/embeddings";
    private static final String EMBEDDING_MODEL = "embedding-3";
    private static final int EMBEDDING_DIMENSIONS = 2048;

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
     * Generate embedding vectors for multiple texts, automatically batching
     * to respect the Zhipu API limit of 4 inputs per request.
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        List<float[]> allEmbeddings = new ArrayList<>();
        int batchSize = 4; // Zhipu embedding-3 API max inputs per request

        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
            List<float[]> batchResult = embedSingleBatch(batch);
            allEmbeddings.addAll(batchResult);
        }

        log.debug("EmbeddingService: generated {} embeddings in {} batches, dimension={}",
            allEmbeddings.size(),
            (texts.size() + batchSize - 1) / batchSize,
            allEmbeddings.isEmpty() || allEmbeddings.get(0).length == 0 ? 0 : allEmbeddings.get(0).length);
        return allEmbeddings;
    }

    /**
     * Send a single batch (max 4 texts) to the Zhipu Embedding API.
     */
    private List<float[]> embedSingleBatch(List<String> texts) {
        try {
            Map<String, Object> body = Map.of(
                "model", EMBEDDING_MODEL,
                "input", texts,
                "dimensions", EMBEDDING_DIMENSIONS
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

            return embeddings;

        } catch (IOException | InterruptedException e) {
            log.error("EmbeddingService: API call failed: {}", e.getMessage());
            return texts.stream().map(t -> new float[0]).toList();
        }
    }
}
