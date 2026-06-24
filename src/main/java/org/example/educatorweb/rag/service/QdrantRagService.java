package org.example.educatorweb.rag.service;

import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.QueryFactory.nearest;

/**
 * Qdrant-backed implementation of RagService.
 * Stores document chunk vectors and retrieves them via semantic similarity.
 * Each user's documents are isolated via the "userId" payload field.
 */
@Service
@Primary
public class QdrantRagService implements RagService {

    private static final Logger log = LoggerFactory.getLogger(QdrantRagService.class);

    private static final String COLLECTION_NAME = "ml_documents";
    private static final int VECTOR_DIMENSION = 2048; // Zhipu embedding-3 dimension

    private final QdrantClient qdrantClient;
    private final EmbeddingService embeddingService;
    private volatile boolean collectionInitialized = false;

    public QdrantRagService(QdrantClient qdrantClient, EmbeddingService embeddingService) {
        this.qdrantClient = qdrantClient;
        this.embeddingService = embeddingService;
    }

    @Override
    public List<DocumentSnippet> retrieve(String userId, String query, int topK) {
        if (!ensureCollection()) {
            log.warn("QdrantRagService: collection not available, returning empty");
            return List.of();
        }

        try {
            float[] queryVector = embeddingService.embed(query);
            if (queryVector.length == 0) {
                log.warn("QdrantRagService: empty embedding, returning empty");
                return List.of();
            }

            var queryBuilder = Points.QueryPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .setLimit(topK)
                .setQuery(nearest(queryVector))
                .setWithPayload(Points.WithPayloadSelector.newBuilder()
                    .setEnable(true).build());

            // Filter by userId so each user only sees their own documents
            if (userId != null && !userId.isBlank()) {
                queryBuilder.setFilter(Points.Filter.newBuilder()
                    .addMust(ConditionFactory.matchKeyword("userId", userId))
                    .build());
            }

            List<Points.ScoredPoint> scoredPoints = qdrantClient.queryAsync(
                queryBuilder.build()
            ).get();

            List<DocumentSnippet> results = new ArrayList<>();
            for (Points.ScoredPoint point : scoredPoints) {
                var payload = point.getPayloadMap();
                String text = payload.getOrDefault("text",
                    JsonWithInt.Value.newBuilder().setStringValue("").build()).getStringValue();
                String source = payload.getOrDefault("source",
                    JsonWithInt.Value.newBuilder().setStringValue("").build()).getStringValue();
                double score = 1.0 - point.getScore(); // Qdrant returns distance, convert to similarity

                if (!text.isBlank()) {
                    results.add(new DocumentSnippet(text, source, Math.max(0.0, score)));
                }
            }

            log.debug("QdrantRagService: retrieved {} results for user={} query={}",
                results.size(), userId, query.substring(0, Math.min(query.length(), 30)));
            return results;

        } catch (InterruptedException | ExecutionException e) {
            log.error("QdrantRagService: query failed: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * Store document chunks in Qdrant, associated with a specific user.
     */
    public int store(String userId, List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return 0;
        if (!ensureCollection()) return 0;

        try {
            List<String> texts = chunks.stream().map(DocumentChunk::text).toList();
            List<float[]> embeddings = embeddingService.embedBatch(texts);

            List<Points.PointStruct> pointStructs = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                float[] vector = i < embeddings.size() ? embeddings.get(i) : new float[0];
                if (vector.length == 0) continue;

                List<Float> floatList = new ArrayList<>(vector.length);
                for (float f : vector) floatList.add(f);

                Points.PointStruct point = Points.PointStruct.newBuilder()
                    .setId(Points.PointId.newBuilder().setUuid(chunk.id().toString()).build())
                    .setVectors(Points.Vectors.newBuilder()
                        .setVector(Points.Vector.newBuilder().addAllData(floatList).build())
                        .build())
                    .putPayload("userId", JsonWithInt.Value.newBuilder().setStringValue(userId).build())
                    .putPayload("docId", JsonWithInt.Value.newBuilder().setStringValue(chunk.docId()).build())
                    .putPayload("source", JsonWithInt.Value.newBuilder().setStringValue(chunk.source()).build())
                    .putPayload("title", JsonWithInt.Value.newBuilder().setStringValue(chunk.title()).build())
                    .putPayload("text", JsonWithInt.Value.newBuilder().setStringValue(chunk.text()).build())
                    .putPayload("knowledgePoint", JsonWithInt.Value.newBuilder().setStringValue(chunk.knowledgePoint()).build())
                    .putPayload("page", JsonWithInt.Value.newBuilder().setIntegerValue(chunk.page()).build())
                    .build();
                pointStructs.add(point);
            }

            if (pointStructs.isEmpty()) {
                log.warn("QdrantRagService: all embeddings failed, nothing to store");
                return 0;
            }

            qdrantClient.upsertAsync(
                Points.UpsertPoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .addAllPoints(pointStructs)
                    .build()
            ).get();

            log.info("QdrantRagService: stored {} chunks for user={}", pointStructs.size(), userId);
            return pointStructs.size();

        } catch (InterruptedException | ExecutionException e) {
            log.error("QdrantRagService: store failed: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /**
     * List all unique documents uploaded by a specific user.
     * Groups chunk-level points by source/docId and returns file-level metadata.
     */
    public List<Map<String, Object>> listDocuments(String userId) {
        if (!ensureCollection()) return List.of();

        try {
            var filter = Points.Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("userId", userId))
                .build();

            var scrollResponse = qdrantClient.scrollAsync(
                Points.ScrollPoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .setFilter(filter)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setLimit(1000)
                    .build()
            ).get();

            // Group by source field — all chunks from the same file share the same source value
            Map<String, Map<String, Object>> docMap = new LinkedHashMap<>();
            for (var point : scrollResponse.getResultList()) {
                var payload = point.getPayloadMap();
                String source = getPayloadString(payload, "source");
                String title = getPayloadString(payload, "title");

                if (source.isBlank()) continue;

                docMap.compute(source, (k, v) -> {
                    if (v == null) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("source", source);
                        entry.put("title", title.isBlank() ? source : title);
                        entry.put("chunks", 1);
                        return entry;
                    }
                    v.put("chunks", ((Integer) v.get("chunks")) + 1);
                    return v;
                });
            }

            return new ArrayList<>(docMap.values());

        } catch (InterruptedException | ExecutionException e) {
            log.error("QdrantRagService: listDocuments failed: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private String getPayloadString(Map<String, JsonWithInt.Value> payload, String key) {
        var value = payload.get(key);
        return value != null ? value.getStringValue() : "";
    }

    private boolean ensureCollection() {
        if (collectionInitialized) return true;

        try {
            boolean exists = qdrantClient.collectionExistsAsync(COLLECTION_NAME).get();
            if (!exists) {
                log.info("QdrantRagService: creating collection '{}'", COLLECTION_NAME);
                qdrantClient.createCollectionAsync(
                    Collections.CreateCollection.newBuilder()
                        .setCollectionName(COLLECTION_NAME)
                        .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                            .setParams(Collections.VectorParams.newBuilder()
                                .setSize(VECTOR_DIMENSION)
                                .setDistance(Collections.Distance.Cosine)
                                .build())
                            .build())
                        .build()
                ).get();

            }
            // Ensure payload index on userId (required for filtering in Qdrant Cloud)
            try {
                var indexResult = qdrantClient.createPayloadIndexAsync(
                    COLLECTION_NAME,
                    "userId",
                    Collections.PayloadSchemaType.Keyword,
                    null,   // default index params
                    true,   // wait until ready
                    null,   // default write ordering
                    null    // no timeout
                ).get();
                log.info("QdrantRagService: payload index on 'userId': status={}", indexResult.getStatus());
            } catch (Exception e) {
                log.warn("QdrantRagService: failed to create index on 'userId' (may already exist): {}",
                    e.getMessage());
            }
            collectionInitialized = true;
            return true;
        } catch (Exception e) {
            log.error("QdrantRagService: cannot initialize collection: {}", e.getMessage());
            return false;
        }
    }
}
