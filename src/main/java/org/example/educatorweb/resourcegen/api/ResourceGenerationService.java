package org.example.educatorweb.resourcegen.api;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ProgressEvent;
import org.example.educatorweb.resourcegen.agents.DesignAgent;
import org.example.educatorweb.resourcegen.agents.RequireAgent;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.orchestration.GenerationGraph;
import org.example.educatorweb.resourcegen.orchestration.GraphOrchestrator;
import org.example.educatorweb.resourcegen.orchestration.Router;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ResourceGenerationService {

    private final GraphOrchestrator orchestrator;
    private final RequireAgent requireAgent;
    private final DesignAgent designAgent;

    public ResourceGenerationService(GraphOrchestrator orchestrator,
                                     RequireAgent requireAgent,
                                     DesignAgent designAgent) {
        this.orchestrator = orchestrator;
        this.requireAgent = requireAgent;
        this.designAgent = designAgent;
    }

    public Flux<ProgressEvent> generate(GenerateRequest req) {
        GenerationState initialState = GenerationState.initial(req);

        GenerationGraph graph = GenerationGraph.builder()
            .node("REQUIRE", requireAgent)
            .node("DESIGN", designAgent)
            .edge("REQUIRE", "DESIGN")
            .router("DESIGN", Router.ALWAYS_DONE)
            .build();

        return orchestrator.run(graph, initialState);
    }
}
