package org.example.educatorweb.knowledgegraph.build.processor;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.QueryFactory.nearest;

public class KgReferenceStore {

    private static final Logger log = LoggerFactory.getLogger(KgReferenceStore.class);
    static final String COLLECTION_NAME = "kg_references";
    private static final int VECTOR_DIM = 1024;

    private final QdrantClient qdrantClient;
    private volatile boolean initialized;

    public KgReferenceStore(QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    public boolean ensureCollection() {
        if (initialized) return true;
        try {
            if (!qdrantClient.collectionExistsAsync(COLLECTION_NAME).get()) {
                qdrantClient.createCollectionAsync(Collections.CreateCollection.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                        .setParams(Collections.VectorParams.newBuilder()
                            .setSize(VECTOR_DIM).setDistance(Collections.Distance.Cosine)
                            .build())
                        .build())
                    .build()).get();
                log.info("KgReferenceStore: created collection '{}'", COLLECTION_NAME);
            }
            initialized = true;
            return true;
        } catch (Exception e) {
            log.error("KgReferenceStore: cannot init: {}", e.getMessage());
            return false;
        }
    }

    public int store(List<DocumentChunk> chunks, List<float[]> embeddings) {
        if (!ensureCollection() || chunks.isEmpty()) return 0;
        try {
            List<Points.PointStruct> points = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk c = chunks.get(i);
                float[] vec = i < embeddings.size() ? embeddings.get(i) : new float[0];
                if (vec.length == 0) continue;
                List<Float> fltList = new ArrayList<>(vec.length);
                for (float f : vec) fltList.add(f);
                points.add(Points.PointStruct.newBuilder()
                    .setId(Points.PointId.newBuilder().setUuid(c.id().toString()).build())
                    .setVectors(Points.Vectors.newBuilder()
                        .setVector(Points.Vector.newBuilder().addAllData(fltList).build())
                        .build())
                    .putPayload("docId", JsonWithInt.Value.newBuilder().setStringValue(c.docId()).build())
                    .putPayload("source", JsonWithInt.Value.newBuilder().setStringValue(c.source()).build())
                    .putPayload("title", JsonWithInt.Value.newBuilder().setStringValue(c.title()).build())
                    .putPayload("text", JsonWithInt.Value.newBuilder().setStringValue(c.text()).build())
                    .putPayload("topic", JsonWithInt.Value.newBuilder().setStringValue(c.knowledgePoint()).build())
                    .putPayload("status", JsonWithInt.Value.newBuilder().setStringValue("new").build())
                    .build());
            }
            qdrantClient.upsertAsync(Points.UpsertPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME).addAllPoints(points).build()).get();
            log.info("KgReferenceStore: stored {} chunks", points.size());
            return points.size();
        } catch (Exception e) {
            log.error("KgReferenceStore: store failed: {}", e.getMessage());
            return 0;
        }
    }

    public List<String> retrieve(float[] queryVector, String topic, int topK) {
        if (!ensureCollection()) return List.of();
        try {
            var scored = qdrantClient.queryAsync(Points.QueryPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .setLimit(topK)
                .setQuery(nearest(queryVector))
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                .build()).get();
            List<String> texts = new ArrayList<>();
            for (var p : scored) {
                var textVal = p.getPayloadMap().getOrDefault("text",
                    JsonWithInt.Value.newBuilder().setStringValue("").build());
                String text = textVal.getStringValue();
                if (!text.isBlank()) texts.add(text);
            }
            return texts;
        } catch (Exception e) {
            log.error("KgReferenceStore: retrieve failed: {}", e.getMessage());
            return List.of();
        }
    }

    public long countByStatus(String status) {
        try {
            var result = qdrantClient.countAsync(COLLECTION_NAME,
                Points.Filter.newBuilder()
                    .addMust(io.qdrant.client.ConditionFactory.matchKeyword("status", status))
                    .build(),
                true).get();
            return result;
        } catch (Exception e) { return 0; }
    }
}
