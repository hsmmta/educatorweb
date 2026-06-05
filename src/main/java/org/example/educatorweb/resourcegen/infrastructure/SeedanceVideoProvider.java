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

    @Override
    public byte[] generateVideo(String visualPrompt, int durationSeconds) {
        log.info("SeedanceVideoProvider: generating {}s video (model={})", durationSeconds, videoModel);
        try {
            var req = new VideoRequest(videoModel, visualPrompt, durationSeconds);
            String body = req.toJson();

            HttpResponse<byte[]> response = sendRequest("/chat/completions", body);
            return extractBinaryFromResponse(response.body(), "video");
        } catch (Exception e) {
            log.error("Seedance video generation failed: {}", e.getMessage());
            throw new RuntimeException("Seedance video generation failed", e);
        }
    }

    @Override
    public byte[] generateImage(String prompt) {
        log.info("SeedanceVideoProvider: generating image (model={})", imageModel);
        try {
            var req = new ImageRequest(imageModel, prompt);
            String body = req.toJson();

            HttpResponse<byte[]> response = sendRequest("/chat/completions", body);
            return extractBinaryFromResponse(response.body(), "image");
        } catch (Exception e) {
            log.error("Seedream image generation failed: {}", e.getMessage());
            throw new RuntimeException("Seedream image generation failed", e);
        }
    }

    private HttpResponse<byte[]> sendRequest(String path, String body) throws Exception {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        var request = HttpRequest.newBuilder()
            .uri(java.net.URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofMinutes(5))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            String errBody = new String(response.body());
            log.warn("Ark API returned {}: {}", response.statusCode(),
                errBody.substring(0, Math.min(200, errBody.length())));
            throw new RuntimeException("Ark API returned " + response.statusCode());
        }
        return response;
    }

    /**
     * Extract base64-encoded video or image from Ark API response.
     * Expected format: {"choices":[{"message":{"content":[{"type":"video_url",
     *   "video_url":{"url":"data:video/mp4;base64,..."}}]}}]}
     */
    private byte[] extractBinaryFromResponse(byte[] responseBody, String type) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode message = choices.get(0).get("message");
            if (message != null) {
                JsonNode content = message.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode part : content) {
                        JsonNode urlNode = part.path(type + "_url").path("url");
                        if (!urlNode.isMissingNode()) {
                            String dataUrl = urlNode.asText();
                            if (dataUrl.contains("base64,")) {
                                String b64 = dataUrl.substring(dataUrl.indexOf("base64,") + 7);
                                return Base64.getDecoder().decode(b64);
                            }
                        }
                    }
                }
                // Fallback: check if content is a plain string with base64
                String textContent = message.path("content").asText(null);
                if (textContent != null && textContent.contains("base64,")) {
                    String b64 = textContent.substring(textContent.indexOf("base64,") + 7);
                    return Base64.getDecoder().decode(b64);
                }
            }
        }
        throw new RuntimeException("Could not extract " + type + " data from Ark API response");
    }

    @Override public String providerName() { return "seedance"; }
    @Override public boolean isEnabled() { return enabled; }

    // Request DTOs
    private record VideoRequest(String model, String prompt, int duration) {
        // Ark API format for video generation
        public String toJson() {
            return String.format("""
                {"model":"%s","messages":[{"role":"user","content":"%s"}],
                "modalities":["video"],"video_config":{"duration":%d}}
                """, model, prompt.replace("\\", "\\\\").replace("\"", "\\\""), duration);
        }
    }

    private record ImageRequest(String model, String prompt) {
        public String toJson() {
            return String.format("""
                {"model":"%s","messages":[{"role":"user","content":"%s"}],
                "modalities":["image"]}
                """, model, prompt.replace("\\", "\\\\").replace("\"", "\\\""));
        }
    }
}
