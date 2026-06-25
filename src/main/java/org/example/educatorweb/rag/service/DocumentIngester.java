package org.example.educatorweb.rag.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Ingests documents (PDF, plain text) into the Qdrant vector store.
 * Pipeline: extract text → chunk → embed → store in Qdrant.
 */
public class DocumentIngester {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngester.class);

    private static final int CHUNK_SIZE_CHARS = 500;
    private static final int CHUNK_OVERLAP_CHARS = 50;

    private final QdrantRagService ragService;

    public DocumentIngester(QdrantRagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Ingest a document file and return the number of chunks created.
     */
    public int ingest(String userId, File file) {
        String fileName = file.getName();
        log.info("DocumentIngester: ingesting {} for user={}", fileName, userId);

        String text = extractText(file);
        if (text == null || text.isBlank()) {
            log.warn("DocumentIngester: no text extracted from {}", fileName);
            return 0;
        }

        String source = fileName.replaceFirst("\\.[^.]+$", "");
        List<DocumentChunk> chunks = chunkText(text, source);

        return ragService.store(userId, chunks);
    }

    /**
     * Ingests raw text directly (e.g., from API or manual input).
     */
    public int ingestText(String userId, String text, String source, String knowledgePoint) {
        List<DocumentChunk> chunks = chunkText(text, source);
        return ragService.store(userId, chunks);
    }

    /**
     * Extract raw text from a file. Supports PDF and plain text.
     */
    private String extractText(File file) {
        String name = file.getName().toLowerCase();

        if (name.endsWith(".pdf")) {
            return extractPdfText(file);
        }

        // Plain text / Markdown
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            log.error("DocumentIngester: failed to read {}: {}", name, e.getMessage());
            return null;
        }
    }

    private String extractPdfText(File file) {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (IOException e) {
            log.error("DocumentIngester: PDF extraction failed for {}: {}",
                file.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Split text into overlapping chunks.
     */
    private List<DocumentChunk> chunkText(String text, String source) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE_CHARS, text.length());
            String chunkText = text.substring(start, end).trim();

            if (!chunkText.isEmpty()) {
                chunks.add(DocumentChunk.of(
                    source + "-chunk-" + chunkIndex,
                    source,
                    source,
                    chunkText,
                    "",
                    chunkIndex + 1
                ));
                chunkIndex++;
            }

            start += (CHUNK_SIZE_CHARS - CHUNK_OVERLAP_CHARS);
        }

        log.debug("DocumentIngester: split '{}' into {} chunks", source, chunks.size());
        return chunks;
    }
}
