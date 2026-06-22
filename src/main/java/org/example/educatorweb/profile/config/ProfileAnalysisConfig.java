package org.example.educatorweb.profile.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j configuration for the profile analysis agent.
 * Creates an {@link OpenAiChatModel} pointed at DeepSeek's OpenAI-compatible endpoint.
 */
@Configuration
public class ProfileAnalysisConfig {

    @Value("${model-routing.providers.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${model-routing.text.model:deepseek-chat}")
    private String modelName;

    @Bean
    public OpenAiChatModel profileAnalysisChatModel() {
        String apiKey = resolveApiKey();
        return OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(0.3)   // low temperature for consistent structured output
            .timeout(Duration.ofSeconds(120))
            .logRequests(true)
            .logResponses(true)
            .build();
    }

    private String resolveApiKey() {
        String key = System.getProperty("DEEPSEEK_API_KEY");
        if (key != null && !key.isBlank()) return key;
        key = System.getenv("DEEPSEEK_API_KEY");
        return key != null ? key : "";
    }
}
