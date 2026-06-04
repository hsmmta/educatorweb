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
}
