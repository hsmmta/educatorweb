package org.example.educatorweb.resourcegen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

@ConfigurationProperties(prefix = "model-routing")
public record ModelRoutingProperties(
    ModelConfig text,
    ModelConfig visual,
    Map<String, ProviderConfig> providers,
    ModelConfig video
) {
    public ModelRoutingProperties {
        if (video == null) {
            video = new ModelConfig("seedance", "seedance-v1", 0.7);
        }
    }

    public record ModelConfig(String provider, String model, double temperature, String imageModel) {
        public ModelConfig {
            if (temperature == 0) temperature = 0.7;
            if (imageModel == null) imageModel = model;
        }
        // Backward-compatible constructor without imageModel
        public ModelConfig(String provider, String model, double temperature) {
            this(provider, model, temperature, model);
        }
    }

    public record ProviderConfig(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String apiSecret,
        String appId
    ) {
        public ProviderConfig {
            if (baseUrl == null) baseUrl = "";
            if (apiKey == null) apiKey = "";
        }
    }
}
