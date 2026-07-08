package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final String BASE_DIR = "generated-resources";

    /**
     * Store byte content to a file under generated-resources/{requestId}/{filename}.
     *
     * @param requestId the unique request identifier (used as subdirectory)
     * @param content   the file content as bytes
     * @param filename  the target filename
     * @return the absolute path of the stored file
     */
    public String store(String requestId, byte[] content, String filename) {
        try {
            Path dir = Path.of(BASE_DIR, requestId);
            Files.createDirectories(dir);
            Path file = dir.resolve(filename);
            Files.write(file, content);
            String absolutePath = file.toAbsolutePath().toString();
            log.info("Stored file: {} ({} bytes)", absolutePath, content.length);
            return absolutePath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + filename, e);
        }
    }

    /**
     * Store content under the organized folder scheme:
     * {@code generated-resources/{userId}/{pushType}/{topic}/{resourceType}/{filename}}
     *
     * @param userId       the student identifier
     * @param pushType     "topic-push" or "path-push"
     * @param topic        the knowledge-point / topic label
     * @param resourceType DOC, QUIZ, CODE, etc.
     * @param content      file content as bytes
     * @param filename     target filename (e.g. "讲解.md", "练习.json")
     * @return the absolute path of the stored file
     */
    public String storeOrganized(String userId, String pushType, String topic,
                                  String resourceType, byte[] content, String filename) {
        try {
            Path dir = Path.of(BASE_DIR, userId, pushType, sanitize(topic), resourceType);
            Files.createDirectories(dir);
            Path file = dir.resolve(filename);
            Files.write(file, content);
            String absolutePath = file.toAbsolutePath().toString();
            log.info("Stored organized file: {} ({} bytes)", absolutePath, content.length);
            return absolutePath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store organized file: " + filename, e);
        }
    }

    /**
     * Copy an existing file into the organized folder scheme.
     *
     * @return the new absolute path
     */
    public String copyToOrganized(String sourcePath, String userId, String pushType,
                                   String topic, String resourceType, String filename) {
        try {
            Path source = Path.of(sourcePath);
            if (!Files.exists(source)) {
                throw new RuntimeException("Source file does not exist: " + sourcePath);
            }
            Path dir = Path.of(BASE_DIR, userId, pushType, sanitize(topic), resourceType);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String absolutePath = target.toAbsolutePath().toString();
            log.info("Copied file to organized path: {} -> {}", sourcePath, absolutePath);
            return absolutePath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy file to organized path: " + filename, e);
        }
    }

    /** Sanitize a string for use as a directory name. */
    private String sanitize(String name) {
        if (name == null || name.isBlank()) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
