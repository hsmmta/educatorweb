package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.List;

@Component
public class VideoAssembler {
    private static final Logger log = LoggerFactory.getLogger(VideoAssembler.class);

    /**
     * Concatenate video clips into a final MP4.
     * Clips that are empty (length 0) are skipped — these represent fallback frames.
     */
    public byte[] assemble(List<byte[]> clips, List<String> transitions) {
        // Filter out empty clips (fallbacks)
        List<byte[]> validClips = clips.stream()
            .filter(c -> c.length > 0)
            .toList();

        if (validClips.isEmpty()) {
            log.warn("No valid video clips — all scenes fell back to text-only. "
                + "Returning empty MP4 placeholder.");
            return new byte[0];
        }

        if (validClips.size() == 1) {
            log.info("VideoAssembler: single clip, no concat needed ({} bytes)", validClips.get(0).length);
            return validClips.get(0);
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("video-assembly-");
            log.info("VideoAssembler: assembling {} clips in {}", validClips.size(), tmpDir);

            // Write each clip to temp file
            for (int i = 0; i < validClips.size(); i++) {
                Path clipPath = tmpDir.resolve(String.format("clip_%03d.mp4", i));
                Files.write(clipPath, validClips.get(i));
            }

            // Build FFmpeg concat file
            Path concatFile = tmpDir.resolve("concat.txt");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < validClips.size(); i++) {
                sb.append(String.format("file 'clip_%03d.mp4'\n", i));
            }
            Files.writeString(concatFile, sb.toString());

            // Run FFmpeg concat
            Path output = tmpDir.resolve("output.mp4");
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "concat", "-safe", "0",
                "-i", concatFile.toAbsolutePath().toString(),
                "-c", "copy",
                output.toAbsolutePath().toString()
            );
            pb.directory(tmpDir.toFile());
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes());
                throw new RuntimeException("FFmpeg failed (exit " + exitCode + "): " + stderr);
            }

            byte[] result = Files.readAllBytes(output);
            log.info("VideoAssembler: produced {} bytes", result.length);
            return result;
        } catch (Exception e) {
            log.error("VideoAssembler failed: {}", e.getMessage());
            return new byte[0];
        } finally {
            if (tmpDir != null) {
                try { deleteRecursively(tmpDir); } catch (IOException ignored) {}
            }
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.forEach(p -> {
                    try { deleteRecursively(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.deleteIfExists(dir);
    }
}
