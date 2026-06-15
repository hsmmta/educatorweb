package org.example.educatorweb.rag.api;

import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.example.educatorweb.rag.service.DocumentIngester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final DocumentIngester documentIngester;
    private final RagService ragService;

    public RagController(DocumentIngester documentIngester, RagService ragService) {
        this.documentIngester = documentIngester;
        this.ragService = ragService;
    }

    /**
     * Upload a PDF or text document, ingest into Qdrant.
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> uploadDocument(
            @RequestPart("file") FilePart filePart,
            @RequestParam(value = "knowledgePoint", defaultValue = "") String knowledgePoint) {

        return Mono.fromCallable(() -> {
            // Save uploaded file to temp
            Path tempFile = Files.createTempFile("rag-upload-", "-" + filePart.filename());
            try {
                filePart.transferTo(tempFile.toFile()).block();
                File file = tempFile.toFile();
                int chunks = documentIngester.ingest(file);
                Files.deleteIfExists(tempFile);

                log.info("RagController: ingested {} → {} chunks", filePart.filename(), chunks);
                Map<String, Object> result = Map.of(
                    "filename", filePart.filename(),
                    "chunks", (Object) chunks,
                    "status", chunks > 0 ? "ok" : "empty"
                );
                return ResponseEntity.ok(result);
            } catch (Exception e) {
                log.error("RagController: upload failed: {}", e.getMessage());
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                throw e;
            }
        }).onErrorResume(e ->
            Mono.just(ResponseEntity.badRequest()
                .body(Map.<String, Object>of("error", e.getMessage())))
        );
    }

    /**
     * Ingest plain text directly.
     */
    @PostMapping(value = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ingestText(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        String source = body.getOrDefault("source", "manual");
        String knowledgePoint = body.getOrDefault("knowledgePoint", "");

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.<String, Object>of("error", "text is required"));
        }

        int chunks = documentIngester.ingestText(text, source, knowledgePoint);
        return ResponseEntity.ok(Map.<String, Object>of("chunks", chunks, "status", "ok"));
    }

    /**
     * Semantic search over ingested documents.
     */
    @GetMapping("/search")
    public ResponseEntity<List<DocumentSnippet>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK) {

        List<DocumentSnippet> results = ragService.retrieve(query, topK);
        return ResponseEntity.ok(results);
    }
}
