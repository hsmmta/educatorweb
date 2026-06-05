package org.example.educatorweb.resourcegen.infrastructure;

import org.example.educatorweb.resourcegen.model.VideoScene;
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

    /**
     * Convert a list of images to an MP4 video.
     * Each image is shown for its scene's durationSeconds.
     */
    public byte[] imagesToVideo(List<byte[]> images, List<VideoScene> scenes) {
        if (images.isEmpty()) {
            log.warn("No images to assemble");
            return new byte[0];
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("video-images-");
            log.info("VideoAssembler: creating video from {} images in {}", images.size(), tmpDir);

            // Write each image to temp file
            for (int i = 0; i < images.size(); i++) {
                Path imgPath = tmpDir.resolve(String.format("slide_%03d.png", i));
                byte[] imgBytes = images.get(i);
                Files.write(imgPath, imgBytes);
            }

            // Build FFmpeg command: concat images with duration per slide
            Path concatFile = tmpDir.resolve("concat.txt");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < images.size(); i++) {
                int duration = (i < scenes.size()) ? scenes.get(i).durationSeconds() : 10;
                sb.append(String.format("file 'slide_%03d.png'\n", i));
                sb.append(String.format("duration %d\n", duration));
            }
            // Last image needs a final entry for FFmpeg concat
            int lastIdx = images.size() - 1;
            sb.append(String.format("file 'slide_%03d.png'\n", lastIdx));
            Files.writeString(concatFile, sb.toString());

            Path output = tmpDir.resolve("output.mp4");
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "concat", "-safe", "0",
                "-i", concatFile.toAbsolutePath().toString(),
                "-vf", "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2",
                "-pix_fmt", "yuv420p",
                "-c:v", "libx264",
                output.toAbsolutePath().toString()
            );
            pb.directory(tmpDir.toFile());
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes());
                throw new RuntimeException("FFmpeg image-to-video failed: " + stderr);
            }

            byte[] result = Files.readAllBytes(output);
            log.info("VideoAssembler: produced {} bytes from {} slides", result.length, images.size());
            return result;
        } catch (Exception e) {
            log.error("VideoAssembler imagesToVideo failed: {}", e.getMessage());
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
