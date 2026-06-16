package org.example.educatorweb.knowledgegraph.build.processor;

import org.example.educatorweb.rag.model.DocumentChunk;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class KgContentProcessor {

    private static final Logger log = LoggerFactory.getLogger(KgContentProcessor.class);

    private final EmbeddingService embeddingService;

    public KgContentProcessor(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public List<float[]> embedChunks(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return List.of();
        List<String> texts = chunks.stream().map(DocumentChunk::text).toList();
        List<float[]> embeddings = embeddingService.embedBatch(texts);
        log.debug("KgContentProcessor: embedded {} chunks", embeddings.size());
        return embeddings;
    }
}
