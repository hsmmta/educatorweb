package org.example.educatorweb.resourcegen.api;

import org.example.educatorweb.resourcegen.infrastructure.CodeSandboxService;
import org.example.educatorweb.resourcegen.infrastructure.CodeSandboxService.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves generated PPT/VIDEO files and provides a code-execution endpoint
 * for the interactive Jupyter-like code editor in the frontend.
 */
@RestController
@RequestMapping("/api/generate")
public class ResourceDownloadController {

    private static final Logger log = LoggerFactory.getLogger(ResourceDownloadController.class);
    private static final Path BASE_DIR = Path.of("generated-resources").toAbsolutePath().normalize();

    private final CodeSandboxService sandbox;

    public ResourceDownloadController(CodeSandboxService sandbox) {
        this.sandbox = sandbox;
    }

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

    /**
     * Execute Python code in the sandbox and return stdout/stderr.
     * Used by the frontend Jupyter-like interactive code editor.
     */
    @PostMapping("/run-code")
    public ResponseEntity<Map<String, Object>> runCode(@RequestBody Map<String, String> body) {
        String code = body.getOrDefault("code", "");
        if (code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Code must not be empty"));
        }
        log.info("ResourceDownloadController: running code ({} chars)", code.length());
        ExecutionResult result = sandbox.execute(code);
        Map<String, Object> response = Map.of(
            "stdout", result.stdout(),
            "stderr", result.stderr(),
            "exitCode", result.exitCode(),
            "executionTimeMs", result.executionTimeMs(),
            "timedOut", result.timedOut()
        );
        return ResponseEntity.ok(response);
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
