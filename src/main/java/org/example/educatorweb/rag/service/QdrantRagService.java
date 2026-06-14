package org.example.educatorweb.rag.service;

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
import java.util.List;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.QueryFactory.nearest;

/**
 * Qdrant-backed implementation of RagService.
 * Stores document chunk vectors and retrieves them via semantic similarity.
 */
@Service
@Primary
public class QdrantRagService implements RagService {

    private static final Logger log = LoggerFactory.getLogger(QdrantRagService.class);

    private static final String COLLECTION_NAME = "ml_documents";
    private static final int VECTOR_DIMENSION = 1024; // DeepSeek embedding dimension

    private final QdrantClient qdrantClient;
    private final EmbeddingService embeddingService;
    private volatile boolean collectionInitialized = false;

    public QdrantRagService(QdrantClient qdrantClient, EmbeddingService embeddingService) {
        this.qdrantClient = qdrantClient;
        this.embeddingService = embeddingService;
    }

    @Override
    public List<DocumentSnippet> retrieve(String query, int topK) {
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

            List<Points.ScoredPoint> scoredPoints = qdrantClient.queryAsync(
                Points.QueryPoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .setLimit(topK)
                    .setQuery(nearest(queryVector))
                    .setWithPayload(Points.WithPayloadSelector.newBuilder()
                        .setEnable(true).build())
                    .build()
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

            return results;

        } catch (InterruptedException | ExecutionException e) {
            log.error("QdrantRagService: query failed: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * Store document chunks in Qdrant.
     */
    public int store(List<DocumentChunk> chunks) {
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
                    .putPayload("docId", JsonWithInt.Value.newBuilder().setStringValue(chunk.docId()).build())
                    .putPayload("source", JsonWithInt.Value.newBuilder().setStringValue(chunk.source()).build())
                    .putPayload("title", JsonWithInt.Value.newBuilder().setStringValue(chunk.title()).build())
                    .putPayload("text", JsonWithInt.Value.newBuilder().setStringValue(chunk.text()).build())
                    .putPayload("knowledgePoint", JsonWithInt.Value.newBuilder().setStringValue(chunk.knowledgePoint()).build())
                    .putPayload("page", JsonWithInt.Value.newBuilder().setIntegerValue(chunk.page()).build())
                    .build();
                pointStructs.add(point);
            }

            qdrantClient.upsertAsync(
                Points.UpsertPoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .addAllPoints(pointStructs)
                    .build()
            ).get();

            log.info("QdrantRagService: stored {} chunks", pointStructs.size());
            return pointStructs.size();

        } catch (InterruptedException | ExecutionException e) {
            log.error("QdrantRagService: store failed: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return 0;
        }
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
            collectionInitialized = true;
            return true;
        } catch (Exception e) {
            log.error("QdrantRagService: cannot initialize collection: {}", e.getMessage());
            return false;
        }
    }
}
