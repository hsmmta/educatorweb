package org.example.educatorweb.resourcegen.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves generated PPT/VIDEO files from the generated-resources directory.
 * Path traversal is prevented by validating the resolved path stays within the base dir.
 */
@RestController
@RequestMapping("/api/generate")
public class ResourceDownloadController {

    private static final Logger log = LoggerFactory.getLogger(ResourceDownloadController.class);
    private static final Path BASE_DIR = Path.of("generated-resources").toAbsolutePath().normalize();

    @GetMapping("/download/{requestId}/{filename}")
    public ResponseEntity<Resource> download(@PathVariable String requestId,
                                             @PathVariable String filename) {
        // Reject path-traversal attempts
        if (requestId.contains("..") || requestId.contains("/") || requestId.contains("\\")
            || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            log.warn("ResourceDownloadController: rejected suspicious path {}/{}", requestId, filename);
            return ResponseEntity.badRequest().build();
        }

        Path target = BASE_DIR.resolve(requestId).resolve(filename).normalize();
        // Ensure the resolved path is still inside BASE_DIR
        if (!target.startsWith(BASE_DIR)) {
            log.warn("ResourceDownloadController: path escapes base dir: {}", target);
            return ResponseEntity.badRequest().build();
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            log.warn("ResourceDownloadController: file not found: {}", target);
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(target);
        String contentType = guessContentType(filename);
        String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encodedName)
            .contentType(MediaType.parseMediaType(contentType))
            .body(resource);
    }

    private String guessContentType(String filename) {
        try {
            String probed = Files.probeContentType(Path.of(filename));
            if (probed != null) return probed;
        } catch (IOException ignored) {}
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }
}
