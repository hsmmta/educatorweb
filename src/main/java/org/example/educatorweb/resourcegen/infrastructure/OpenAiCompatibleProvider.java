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

public class OpenAiCompatibleProvider implements ModelProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private final ChatClient chatClient;
    private final String name;
    private final boolean enabled;

    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey,
                                     String model, boolean enabled) {
        this.name = name;
        this.enabled = enabled;

        var httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(3));

        var restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        var api = OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .restClientBuilder(restClientBuilder)
            .build();

        var chatModel = new OpenAiChatModel(api, OpenAiChatOptions.builder()
            .model(model).temperature(0.7).build());

        this.chatClient = ChatClient.builder(chatModel).build();
        log.info("OpenAiCompatibleProvider '{}' initialized: baseUrl={}, model={}", name, baseUrl, model);
    }

    @Override
    public String chat(String prompt) {
        return chatClient.prompt().user(prompt).call().content();
    }

    @Override
    public String providerName() { return name; }

    @Override
    public boolean isEnabled() { return enabled; }
}
