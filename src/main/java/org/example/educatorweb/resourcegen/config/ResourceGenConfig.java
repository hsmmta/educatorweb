package org.example.educatorweb.resourcegen.config;

import org.example.educatorweb.resourcegen.infrastructure.CheckpointService;
import org.example.educatorweb.resourcegen.orchestration.GraphOrchestrator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResourceGenConfig {

    @Value("${generation.fanout.thread-pool-size:6}")
    private int threadPoolSize;

    @Value("${spring.ai.openai.api-key:sk-placeholder}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String model;

    @Autowired(required = false)
    private CheckpointService checkpointService;

    @Bean
    public OpenAiApi openAiApi() {
        return new OpenAiApi(baseUrl, apiKey);
    }

    @Bean
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi) {
        return new OpenAiChatModel(openAiApi, OpenAiChatOptions.builder()
            .model(model)
            .temperature(0.7)
            .build());
    }

    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean(destroyMethod = "shutdown")
    public GraphOrchestrator graphOrchestrator() {
        GraphOrchestrator orchestrator = new GraphOrchestrator(threadPoolSize);
        if (checkpointService != null) {
            orchestrator.setCheckpointService(checkpointService);
        }
        return orchestrator;
    }
}
