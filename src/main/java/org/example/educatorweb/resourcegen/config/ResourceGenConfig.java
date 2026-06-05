package org.example.educatorweb.resourcegen.config;

import org.example.educatorweb.resourcegen.infrastructure.CheckpointService;
import org.example.educatorweb.resourcegen.orchestration.GraphOrchestrator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResourceGenConfig {

    private static final Logger log = LoggerFactory.getLogger(ResourceGenConfig.class);

    @Value("${generation.fanout.thread-pool-size:6}")
    private int threadPoolSize;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String model;

    @Autowired(required = false)
    private CheckpointService checkpointService;

    @Bean
    public OpenAiApi openAiApi() {
        // Read API key from .env directly — more reliable than @Value resolution ordering
        String apiKey = System.getProperty("DEEPSEEK_API_KEY",
            System.getenv("DEEPSEEK_API_KEY"));
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("sk-your")) {
            log.warn("DEEPSEEK_API_KEY not set or is placeholder — LLM calls will fail");
        }
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
