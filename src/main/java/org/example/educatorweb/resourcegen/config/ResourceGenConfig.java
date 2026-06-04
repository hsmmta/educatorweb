package org.example.educatorweb.resourcegen.config;

import org.example.educatorweb.resourcegen.orchestration.GraphOrchestrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResourceGenConfig {

    @Value("${generation.fanout.thread-pool-size:6}")
    private int threadPoolSize;

    @Bean(destroyMethod = "shutdown")
    public GraphOrchestrator graphOrchestrator() {
        return new GraphOrchestrator(threadPoolSize);
    }
}
