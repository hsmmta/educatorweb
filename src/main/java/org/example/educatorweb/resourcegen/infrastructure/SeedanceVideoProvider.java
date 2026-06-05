package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SeedanceVideoProvider implements VideoProvider {
    private static final Logger log = LoggerFactory.getLogger(SeedanceVideoProvider.class);
    private final String baseUrl;
    private final String apiKey;
    private final boolean enabled;

    public SeedanceVideoProvider(String baseUrl, String apiKey, boolean enabled) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.enabled = enabled;
    }

    @Override
    public byte[] generateVideo(String visualPrompt, int durationSeconds) {
        log.info("SeedanceVideoProvider: generating {}s video ({} chars prompt)",
            durationSeconds, visualPrompt.length());
        try {
            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
            String body = String.format(
                "{\"prompt\":\"%s\",\"duration\":%d}",
                visualPrompt.replace("\\", "\\\\").replace("\"", "\\\""),
                durationSeconds);
            var request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl + "/generate"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMinutes(5))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                log.info("SeedanceVideoProvider: generated {} bytes", response.body().length);
                return response.body();
            }
            log.warn("SeedanceVideoProvider: API returned {}", response.statusCode());
            throw new RuntimeException("Seedance API returned " + response.statusCode());
        } catch (Exception e) {
            log.error("SeedanceVideoProvider failed: {}", e.getMessage());
            throw new RuntimeException("Seedance video generation failed", e);
        }
    }

    @Override
    public byte[] generateImage(String prompt) {
        throw new UnsupportedOperationException("Seedance image generation not implemented");
    }

    @Override public String providerName() { return "seedance"; }
    @Override public boolean isEnabled() { return enabled; }
}
