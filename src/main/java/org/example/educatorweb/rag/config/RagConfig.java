package org.example.educatorweb.rag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.example.educatorweb.rag.service.DocumentIngester;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.example.educatorweb.rag.service.QdrantRagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Value("${qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${qdrant.grpc-port:6334}")
    private int qdrantGrpcPort;

    @Bean
    public QdrantClient qdrantClient() {
        return new QdrantClient(
            QdrantGrpcClient.newBuilder(qdrantHost, qdrantGrpcPort, false).build()
        );
    }

    @Bean
    public EmbeddingService embeddingService() {
        String apiKey = System.getProperty("DEEPSEEK_API_KEY",
            System.getenv().getOrDefault("DEEPSEEK_API_KEY", ""));
        return new EmbeddingService(apiKey);
    }

    @Bean
    public DocumentIngester documentIngester(QdrantRagService ragService) {
        return new DocumentIngester(ragService);
    }
}
