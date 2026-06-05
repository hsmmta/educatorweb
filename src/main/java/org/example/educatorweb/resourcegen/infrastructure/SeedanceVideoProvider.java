package org.example.educatorweb.resourcegen.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public class SeedanceVideoProvider implements VideoProvider {
    private static final Logger log = LoggerFactory.getLogger(SeedanceVideoProvider.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final int MAX_POLL_ATTEMPTS = 60; // 5 minutes max

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String videoModel;
    private final String imageModel;
    private final boolean enabled;

    public SeedanceVideoProvider(String baseUrl, String apiKey,
                                  String videoModel, String imageModel, boolean enabled) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.videoModel = videoModel;
        this.imageModel = imageModel;
        this.enabled = enabled;
        log.info("SeedanceVideoProvider: baseUrl={}, videoModel={}, imageModel={}, enabled={}",
            baseUrl, videoModel, imageModel, enabled);
    }

    // ═══════════════════════════════════════════
    // Video generation (async: submit → poll → download)
    // ═══════════════════════════════════════════

    @Override
    public byte[] generateVideo(String visualPrompt, int durationSeconds) {
        // Clamp duration to Seedance 1.5 pro range: [4, 12] or -1
        int duration = Math.clamp(durationSeconds, 4, 12);
        log.info("Seedance: submitting video task (model={}, duration={}s)", videoModel, duration);

        try {
            // Step 1: Submit task
            String body = String.format("""
                {"model":"%s","content":[{"type":"text","text":"%s"}],
                "duration":%d,"resolution":"720p","watermark":false,
                "generate_audio":false,"return_last_frame":false}
                """, videoModel, escape(visualPrompt), duration);

            String taskJson = post("/contents/generations/tasks", body);
            JsonNode task = objectMapper.readTree(taskJson);
            String taskId = task.get("id").asText();
            log.info("Seedance: task submitted, id={}", taskId);

            // Step 2: Poll until complete
            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                Thread.sleep(POLL_INTERVAL.toMillis());
                String statusJson = get("/contents/generations/tasks/" + taskId);
                JsonNode status = objectMapper.readTree(statusJson);
                String state = status.get("status").asText();

                if ("succeeded".equals(state)) {
                    String videoUrl = status.path("output").path("video_url").asText();
                    if (videoUrl.isBlank()) {
                        videoUrl = status.at("/data/0/url").asText();
                    }
                    if (!videoUrl.isBlank()) {
                        log.info("Seedance: video ready, downloading from {}", videoUrl);
                        return download(videoUrl);
                    }
                    throw new RuntimeException("Seedance task succeeded but no video URL in response");
                }
                if ("failed".equals(state) || "expired".equals(state)) {
                    String error = status.path("error").path("message").asText("unknown");
                    throw new RuntimeException("Seedance task " + state + ": " + error);
                }
                log.debug("Seedance: task {} status={} (attempt {}/{})", taskId, state, i + 1, MAX_POLL_ATTEMPTS);
            }
            throw new RuntimeException("Seedance task " + taskId + " timed out after " + MAX_POLL_ATTEMPTS + " polls");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Seedance polling interrupted", e);
        } catch (Exception e) {
            log.error("Seedance video generation failed: {}", e.getMessage());
            throw new RuntimeException("Seedance video generation failed", e);
        }
    }

    // ═══════════════════════════════════════════
    // Image generation (sync)
    // ═══════════════════════════════════════════

    @Override
    public byte[] generateImage(String prompt) {
        log.info("Seedream: generating image (model={})", imageModel);
        try {
            String body = String.format("""
                {"model":"%s","prompt":"%s","size":"2048x2048",
                "response_format":"b64_json","watermark":false}
                """, imageModel, escape(prompt));

            String response = post("/images/generations", body);
            JsonNode root = objectMapper.readTree(response);

            // Extract base64 image data
            String b64 = root.at("/data/0/b64_json").asText();
            if (!b64.isBlank()) {
                return Base64.getDecoder().decode(b64);
            }
            // Alternate: image URL
            String imgUrl = root.at("/data/0/url").asText();
            if (!imgUrl.isBlank()) {
                log.info("Seedream: downloading image from URL");
                return download(imgUrl);
            }
            throw new RuntimeException("No image data in Seedream response");
        } catch (Exception e) {
            log.error("Seedream image generation failed: {}", e.getMessage());
            throw new RuntimeException("Seedream image generation failed", e);
        }
    }

    // ═══════════════════════════════════════════
    // HTTP helpers
    // ═══════════════════════════════════════════

    private String post(String path, String body) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        var request = HttpRequest.newBuilder()
            .uri(java.net.URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofMinutes(5))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Ark API " + path + " returned " + response.statusCode()
                + ": " + response.body().substring(0, Math.min(300, response.body().length())));
        }
        return response.body();
    }

    private String get(String path) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var request = HttpRequest.newBuilder()
            .uri(java.net.URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Ark API " + path + " returned " + response.statusCode());
        }
        return response.body();
    }

    private byte[] download(String url) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var request = HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .timeout(Duration.ofMinutes(3))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Download returned " + response.statusCode());
        }
        return response.body();
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    @Override public String providerName() { return "seedance"; }
    @Override public boolean isEnabled() { return enabled; }
}
