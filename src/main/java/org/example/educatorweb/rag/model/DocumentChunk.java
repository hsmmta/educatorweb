package org.example.educatorweb.rag.model;

import java.util.Map;
import java.util.UUID;

/**
 * Represents a document chunk stored in Qdrant.
 * The vector field is kept separate (managed by EmbeddingService + QdrantClient).
 */
public record DocumentChunk(
    UUID id,
    String docId,
    String source,
    String title,
    String text,
    String knowledgePoint,
    int page,
    float[] embedding
) {
    public static DocumentChunk of(String docId, String source, String title,
                                    String text, String knowledgePoint, int page) {
        return new DocumentChunk(UUID.randomUUID(), docId, source, title,
            text, knowledgePoint, page, null);
    }

    public DocumentChunk withEmbedding(float[] embedding) {
        return new DocumentChunk(id, docId, source, title, text,
            knowledgePoint, page, embedding);
    }

    /**
     * Convert to Qdrant payload (everything except id and vector).
     */
    public Map<String, Object> toPayload() {
        return Map.of(
            "docId", (Object) docId,
            "source", (Object) source,
            "title", (Object) title,
            "text", (Object) text,
            "knowledgePoint", (Object) knowledgePoint,
            "page", (Object) page
        );
    }
}
