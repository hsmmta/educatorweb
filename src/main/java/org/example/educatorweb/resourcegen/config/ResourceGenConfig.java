package org.example.educatorweb.resourcegen.config;

import org.example.educatorweb.resourcegen.infrastructure.CheckpointService;
import org.example.educatorweb.resourcegen.orchestration.GraphOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResourceGenConfig {

    @Value("${generation.fanout.thread-pool-size:6}")
    private int threadPoolSize;

    @Autowired(required = false)
    private CheckpointService checkpointService;

    @Bean(destroyMethod = "shutdown")
    public GraphOrchestrator graphOrchestrator() {
        GraphOrchestrator orchestrator = new GraphOrchestrator(threadPoolSize);
        if (checkpointService != null) {
            orchestrator.setCheckpointService(checkpointService);
        }
        return orchestrator;
    }
}
