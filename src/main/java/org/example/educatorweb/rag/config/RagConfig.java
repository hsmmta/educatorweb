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

    @Value("${qdrant.api-key:}")
    private String qdrantApiKey;

    @Bean
    public QdrantClient qdrantClient() {
        // Strip protocol prefix for gRPC connection
        String host = qdrantHost.replaceFirst("^https?://", "");
        boolean useTls = qdrantHost.startsWith("https://");

        var builder = QdrantGrpcClient.newBuilder(host, qdrantGrpcPort, useTls);
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            builder.withApiKey(qdrantApiKey);
        }
        return new QdrantClient(builder.build());
    }

    @Bean
    public EmbeddingService embeddingService() {
        String apiKey = System.getProperty("ZHIPU_API_KEY",
            System.getenv().getOrDefault("ZHIPU_API_KEY", ""));
        return new EmbeddingService(apiKey);
    }

    @Bean
    public DocumentIngester documentIngester(QdrantRagService ragService) {
        return new DocumentIngester(ragService);
    }
}
