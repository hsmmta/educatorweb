package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Xunfei Spark Open API provider — OpenAI-compatible HTTP API.
 * Uses spark-api-open.xf-yun.com which provides an OpenAI-compatible endpoint.
 * Falls back gracefully when not configured.
 */
public class XunfeiProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(XunfeiProvider.class);

    // Spark X2 endpoint (OpenAI-compatible since 2026.02)
    private static final String DEFAULT_BASE_URL = "https://spark-api-open.xf-yun.com/x2";
    private static final String DEFAULT_MODEL = "spark-x";

    private final ChatClient chatClient;
    private final boolean enabled;

    /**
     * @param apiKey    Xunfei API Key
     * @param apiSecret Xunfei API Secret (used to form AK:SK auth for HTTP API)
     * @param baseUrl   Xunfei Spark API base URL (e.g. https://spark-api-open.xf-yun.com/x2)
     * @param model     Model name (spark-x for X1.5/X2)
     * @param enabled   Whether this provider is enabled in config
     */
    public XunfeiProvider(String apiKey, String apiSecret, String baseUrl, String model, boolean enabled) {
        // Xunfei HTTP API requires api_key="AK:SK" format (key:secret concatenated)
        String authKey = apiKey;
        if (apiSecret != null && !apiSecret.isBlank()) {
            authKey = apiKey + ":" + apiSecret;
        }
        this.enabled = enabled && apiKey != null && !apiKey.isBlank();

        if (!this.enabled) {
            this.chatClient = null;
            log.info("XunfeiProvider: disabled (no API key configured)");
            return;
        }

        String url = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        String mdl = (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;

        var httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(3));

        var restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        // Xunfei Spark API path is /chat/completions (NOT /v1/chat/completions)
        var api = OpenAiApi.builder()
            .baseUrl(url)
            .apiKey(authKey)
            .completionsPath("/chat/completions")
            .restClientBuilder(restClientBuilder)
            .build();

        var chatModel = new OpenAiChatModel(api, OpenAiChatOptions.builder()
            .model(mdl).temperature(0.7).build());
        this.chatClient = ChatClient.builder(chatModel).build();

        log.info("XunfeiProvider initialized: baseUrl={}, model={}", url, mdl);
    }

    @Override
    public String chat(String prompt) {
        if (!enabled || chatClient == null) {
            throw new UnsupportedOperationException("Xunfei Spark is not configured. Set XUNFEI_API_KEY in .env.");
        }
        return chatClient.prompt().user(prompt).call().content();
    }

    @Override
    public String providerName() { return "xunfei"; }

    @Override
    public boolean isEnabled() { return enabled; }
}
