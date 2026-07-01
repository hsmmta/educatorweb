package org.example.educatorweb.aitutor.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around Chroma's REST API for conversation storage.
 *
 * <p>Uses Spring {@link RestClient} — no extra dependency needed.
 * Chroma base URL is configured via {@code chroma.base-url} in application.yml,
 * defaulting to {@code http://localhost:8000}.
 *
 * <h3>API reference</h3>
 * <ul>
 *   <li>GET  /api/v2/tenants/.../collections/{name}  — collection info</li>
 *   <li>POST /api/v2/tenants/.../collections           — create collection</li>
 *   <li>POST /api/v2/tenants/.../collections/{id}/add  — insert records</li>
 *   <li>POST /api/v2/tenants/.../collections/{id}/query — semantic search</li>
 * </ul>
 */
public class ChromaClient {

    private static final Logger log = LoggerFactory.getLogger(ChromaClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String COLLECTION_NAME = "ai_tutor_conversations";
    private static final String CHROMA_COLLECTIONS =
        "/api/v2/tenants/default_tenant/databases/default_database/collections";

    private final RestClient restClient;
    /** Cached collection UUID after first lookup. */
    private volatile String collectionId;
    /** Track whether we failed to reach Chroma (avoid repeated log spam). */
    private volatile boolean unavailable;

    public ChromaClient(String baseUrl) {
        var httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Store a message record (question or answer) in Chroma.
     *
     * @param id          unique document id, e.g. {@code conv-123:user:1718000000000}
     * @param embedding   vector representation of the message text
     * @param text        the raw message text
     * @param metadata    userId, conversationId, role, timestamp, etc.
     */
    public boolean add(String id, List<Float> embedding, String text, Map<String, Object> metadata) {
        String collId = ensureCollection();
        if (collId == null) return false;

        try {
            Map<String, Object> body = Map.of(
                "ids", List.of(id),
                "embeddings", List.of(embedding),
                "documents", List.of(text),
                "metadatas", List.of(metadata)
            );
            // Chroma accepts either name or UUID in the path
            restClient.post()
                .uri(CHROMA_COLLECTIONS + "/" + collId + "/add")
                .body(body)
                .retrieve()
                .toBodilessEntity();
            log.debug("ChromaClient: stored record {}", id);
            return true;
        } catch (Exception e) {
            log.warn("ChromaClient: add failed for {}: {}", id, e.getMessage());
            unavailable = true;
            return false;
        }
    }

    /**
     * Query conversation history for a user by semantic similarity.
     *
     * @param queryEmbedding embedding of the current question
     * @param userId         filter to this user's messages
     * @param nResults       max number of results to return
     * @return list of matching records, each a map with keys: id, document, metadata, distance
     */
    public List<Map<String, Object>> query(List<Float> queryEmbedding, String userId, int nResults) {
        String collId = ensureCollection();
        if (collId == null) return List.of();

        try {
            Map<String, Object> body = Map.of(
                "query_embeddings", List.of(queryEmbedding),
                "n_results", nResults,
                "where", Map.of("userId", userId)
            );
            String resp = restClient.post()
                .uri(CHROMA_COLLECTIONS + "/" + collId + "/query")
                .body(body)
                .retrieve()
                .body(String.class);

            // Parse response
            Map<String, Object> result = OBJECT_MAPPER.readValue(resp, new TypeReference<>() {});
            // Chroma returns {"ids": [[...]], "documents": [[...]], "metadatas": [[...]], "distances": [[...]]}
            // Each value is a nested list: outer = per-query, inner = per-result
            return flattenQueryResult(result);
        } catch (Exception e) {
            log.warn("ChromaClient: query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * List distinct conversations for a user, grouped by conversationId,
     * sorted by last-activity timestamp (most recent first).
     *
     * <p>Uses Chroma's POST .../collections/{name}/get with a metadata
     * filter on {@code userId}. Each returned entry is a map containing
     * {@code conversationId}, {@code title} (first user question, truncated)
     * and {@code timestamp} (latest message time in the conversation).
     */
    public List<Map<String, Object>> listConversations(String userId) {
        ChromaGetResult result = chromaGet(Map.of("userId", userId));
        if (result == null || result.metadatas() == null) return List.of();

        List<Map<String, Object>> metadatas = result.metadatas();
        List<String> documents = result.documents();

        Map<String, Map<String, Object>> convMap = new LinkedHashMap<>();
        // Earliest user-message timestamp per conversation (drives title selection).
        Map<String, String> earliestUserTs = new HashMap<>();

        for (int i = 0; i < metadatas.size(); i++) {
            Map<String, Object> meta = metadatas.get(i);
            if (meta == null) continue;
            String convId = (String) meta.get("conversationId");
            if (convId == null) continue;
            String role = (String) meta.get("role");
            String ts = meta.get("timestamp") != null ? String.valueOf(meta.get("timestamp")) : null;
            String doc = (documents != null && i < documents.size()) ? documents.get(i) : null;

            String tsForLambda = ts;
            Map<String, Object> entry = convMap.computeIfAbsent(convId, k -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("conversationId", k);
                e.put("title", "");
                e.put("timestamp", tsForLambda);
                return e;
            });

            // Track the latest timestamp as the conversation's last-activity time.
            if (ts != null) {
                String existing = (String) entry.get("timestamp");
                if (existing == null || ts.compareTo(existing) > 0) {
                    entry.put("timestamp", ts);
                }
            }

            // Title = earliest user message text, truncated.
            if ("user".equals(role) && doc != null && !doc.isBlank()) {
                String prev = earliestUserTs.get(convId);
                String cmp = ts != null ? ts : "";
                if (prev == null || cmp.compareTo(prev) < 0) {
                    earliestUserTs.put(convId, cmp);
                    entry.put("title", truncate(doc, 50));
                }
            }
        }

        List<Map<String, Object>> conversations = new ArrayList<>(convMap.values());
        // Most-recent activity first.
        conversations.sort(Comparator.comparing((Map<String, Object> c) -> {
            Object t = c.get("timestamp");
            return t != null ? t.toString() : "";
        }).reversed());
        return conversations;
    }

    /**
     * Get all messages for a specific conversation, ordered chronologically.
     * Each entry is a map with keys {@code metadata} and {@code document}.
     * Within an identical timestamp the user message is placed before the
     * assistant reply.
     */
    public List<Map<String, Object>> getConversationMessages(String conversationId, String userId) {
        ChromaGetResult result = chromaGet(Map.of(
            "$and", List.of(
                Map.of("userId", userId),
                Map.of("conversationId", conversationId)
            )
        ));
        if (result == null || result.metadatas() == null || result.documents() == null) return List.of();

        List<Map<String, Object>> metadatas = result.metadatas();
        List<String> documents = result.documents();

        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < Math.min(metadatas.size(), documents.size()); i++) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("metadata", metadatas.get(i));
            msg.put("document", documents.get(i));
            messages.add(msg);
        }
        messages.sort(Comparator
            .comparing(ChromaClient::timestampOf)
            .thenComparingInt(ChromaClient::roleRank));
        return messages;
    }

    /** Parsed Chroma {@code /get} response: flat metadata + document lists. */
    private record ChromaGetResult(List<Map<String, Object>> metadatas, List<String> documents) {}

    /**
     * Run a Chroma POST /get with the given {@code where} filter, requesting
     * metadatas + documents. Returns the parsed (flat) lists, or {@code null}
     * if the collection is unavailable or the response is missing/malformed.
     */
    private ChromaGetResult chromaGet(Map<String, Object> where) {
        String collId = ensureCollection();
        if (collId == null) return null;
        try {
            Map<String, Object> body = Map.of(
                "where", where,
                "include", List.of("metadatas", "documents")
            );
            Map<String, Object> response = restClient.post()
                .uri(CHROMA_COLLECTIONS + "/" + collId + "/get")
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (response == null) return null;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> metadatas = (List<Map<String, Object>>) response.get("metadatas");
            @SuppressWarnings("unchecked")
            List<String> documents = (List<String>) response.get("documents");
            return new ChromaGetResult(metadatas, documents);
        } catch (Exception e) {
            log.warn("ChromaClient: get failed: {}", e.getMessage());
            return null;
        }
    }

    /** Truncate text to {@code max} characters, appending an ellipsis when cut. */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    /** Extract the timestamp string from a {metadata, document} message map. */
    private static String timestampOf(Map<String, Object> message) {
        Object meta = message.get("metadata");
        if (meta instanceof Map<?, ?> m) {
            Object ts = m.get("timestamp");
            return ts != null ? ts.toString() : "";
        }
        return "";
    }

    /** Order user messages (0) before assistant replies (1) on timestamp ties. */
    private static int roleRank(Map<String, Object> message) {
        Object meta = message.get("metadata");
        if (meta instanceof Map<?, ?> m) {
            return "user".equals(m.get("role")) ? 0 : 1;
        }
        return 1;
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private String ensureCollection() {
        if (unavailable) return null;
        if (collectionId != null) return collectionId;

        try {
            // Try to get existing collection
            String resp = restClient.get()
                .uri(CHROMA_COLLECTIONS + "/" + COLLECTION_NAME)
                .retrieve()
                .body(String.class);

            Map<String, Object> info = OBJECT_MAPPER.readValue(resp, new TypeReference<>() {});
            collectionId = (String) info.get("id");
            if (collectionId != null) {
                log.info("ChromaClient: collection '{}' found (id={})", COLLECTION_NAME, collectionId);
                return collectionId;
            }
        } catch (Exception e) {
            log.info("ChromaClient: collection '{}' not found, creating...", COLLECTION_NAME);
        }

        // Create collection
        try {
            Map<String, Object> body = Map.of(
                "name", COLLECTION_NAME,
                "metadata", Map.of("hnsw:space", "cosine")
            );
            String resp = restClient.post()
                .uri(CHROMA_COLLECTIONS)
                .body(body)
                .retrieve()
                .body(String.class);

            Map<String, Object> info = OBJECT_MAPPER.readValue(resp, new TypeReference<>() {});
            collectionId = (String) info.get("id");
            log.info("ChromaClient: collection '{}' created (id={})", COLLECTION_NAME, collectionId);
            return collectionId;
        } catch (Exception e) {
            log.error("ChromaClient: cannot create collection '{}': {}", COLLECTION_NAME, e.getMessage());
            unavailable = true;
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> flattenQueryResult(Map<String, Object> raw) {
        List<List<String>> ids = (List<List<String>>) raw.get("ids");
        List<List<String>> documents = (List<List<String>>) raw.get("documents");
        List<List<Map<String, Object>>> metadatas = (List<List<Map<String, Object>>>) raw.get("metadatas");
        List<List<Double>> distances = (List<List<Double>>) raw.get("distances");

        if (ids == null || ids.isEmpty()) return List.of();

        List<String> idList = ids.get(0);
        List<String> docList = documents != null ? documents.get(0) : List.of();
        List<Map<String, Object>> metaList = metadatas != null ? metadatas.get(0) : List.of();
        List<Double> distList = distances != null ? distances.get(0) : List.of();

        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (int i = 0; i < idList.size(); i++) {
            int idx = i;
            results.add(Map.of(
                "id", idList.get(i),
                "document", i < docList.size() ? docList.get(i) : "",
                "metadata", i < metaList.size() ? metaList.get(i) : Map.of(),
                "distance", i < distList.size() ? distList.get(i) : 1.0
            ));
        }
        return results;
    }
}
