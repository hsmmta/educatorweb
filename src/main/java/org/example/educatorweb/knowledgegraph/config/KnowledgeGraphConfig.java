package org.example.educatorweb.knowledgegraph.config;

import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.knowledgegraph.service.LlmKnowledgeExtractor;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeGraphConfig {

    @Bean
    public LlmKnowledgeExtractor llmKnowledgeExtractor(
            @Qualifier("deepSeekProvider") ModelProvider deepSeekProvider,
            KnowledgePointRepository repo) {
        return new LlmKnowledgeExtractor(deepSeekProvider, repo);
    }

    // @Bean — deprecated, replaced by KgBuildAgent
    // public KnowledgeGraphInitializer knowledgeGraphInitializer(
    //         KnowledgePointRepository repo,
    //         org.neo4j.driver.Driver neo4jDriver,
    //         @Qualifier("deepSeekProvider") ModelProvider deepSeekProvider) {
    //     return new KnowledgeGraphInitializer(repo, neo4jDriver, deepSeekProvider);
    // }
}
