package org.example.educatorweb.resourcegen.api;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ProgressEvent;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.agents.DesignAgent;
import org.example.educatorweb.resourcegen.agents.RequireAgent;
import org.example.educatorweb.resourcegen.agents.generators.DocGenerator;
import org.example.educatorweb.resourcegen.agents.generators.MindmapGenerator;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.orchestration.GenerationGraph;
import org.example.educatorweb.resourcegen.orchestration.GraphOrchestrator;
import org.example.educatorweb.resourcegen.orchestration.Router;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Service
public class ResourceGenerationService {

    private final GraphOrchestrator orchestrator;
    private final RequireAgent requireAgent;
    private final DesignAgent designAgent;
    private final DocGenerator docGenerator;
    private final MindmapGenerator mindmapGenerator;

    public ResourceGenerationService(GraphOrchestrator orchestrator,
                                     RequireAgent requireAgent,
                                     DesignAgent designAgent,
                                     DocGenerator docGenerator,
                                     MindmapGenerator mindmapGenerator) {
        this.orchestrator = orchestrator;
        this.requireAgent = requireAgent;
        this.designAgent = designAgent;
        this.docGenerator = docGenerator;
        this.mindmapGenerator = mindmapGenerator;
    }

    public Flux<ProgressEvent> generate(GenerateRequest req) {
        GenerationState initialState = GenerationState.initial(req);
        GenerationGraph graph = buildGraph(req.types());
        return orchestrator.run(graph, initialState);
    }

    private GenerationGraph buildGraph(List<ResourceType> types) {
        var builder = GenerationGraph.builder()
            .node("REQUIRE", requireAgent)
            .node("DESIGN", designAgent)
            .edge("REQUIRE", "DESIGN");

        List<String> genNodes = new ArrayList<>();
        if (types.contains(ResourceType.DOC) || types.isEmpty()) {
            builder.node("GEN_DOC", docGenerator);
            genNodes.add("GEN_DOC");
        }
        if (types.contains(ResourceType.MINDMAP) || types.isEmpty()) {
            builder.node("GEN_MINDMAP", mindmapGenerator);
            genNodes.add("GEN_MINDMAP");
        }

        if (!genNodes.isEmpty()) {
            builder.fanOut("DESIGN", genNodes);
            for (String n : genNodes) builder.router(n, Router.ALWAYS_DONE);
        } else {
            builder.router("DESIGN", Router.ALWAYS_DONE);
        }
        return builder.build();
    }
}
