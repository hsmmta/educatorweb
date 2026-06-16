package org.example.educatorweb.knowledgegraph.build.config;

import io.qdrant.client.QdrantClient;
import org.example.educatorweb.knowledgegraph.build.builder.KgNeo4jWriter;
import org.example.educatorweb.knowledgegraph.build.builder.KgNodeBuilder;
import org.example.educatorweb.knowledgegraph.build.processor.KgContentProcessor;
import org.example.educatorweb.knowledgegraph.build.processor.KgReferenceStore;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KgBuildProperties.class)
public class KgBuildConfig {

    @Bean
    public KgReferenceStore kgReferenceStore(QdrantClient qdrantClient) {
        return new KgReferenceStore(qdrantClient);
    }

    @Bean
    public KgContentProcessor kgContentProcessor(EmbeddingService embeddingService) {
        return new KgContentProcessor(embeddingService);
    }

    @Bean
    public KgNodeBuilder kgNodeBuilder(
            @Qualifier("deepSeekProvider") ModelProvider deepSeekProvider) {
        return new KgNodeBuilder(deepSeekProvider);
    }

    @Bean
    public KgNeo4jWriter kgNeo4jWriter(Driver neo4jDriver) {
        return new KgNeo4jWriter(neo4jDriver);
    }
}
