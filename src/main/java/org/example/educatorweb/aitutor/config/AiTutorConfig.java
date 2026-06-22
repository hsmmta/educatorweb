package org.example.educatorweb.aitutor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiTutorConfig {

    @Value("${chroma.base-url:http://localhost:8000}")
    private String chromaBaseUrl;

    @Bean
    public ChromaClient chromaClient() {
        return new ChromaClient(chromaBaseUrl);
    }
}
