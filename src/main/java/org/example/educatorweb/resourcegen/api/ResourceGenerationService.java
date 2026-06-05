package org.example.educatorweb.resourcegen.api;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ProgressEvent;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.agents.DesignAgent;
import org.example.educatorweb.resourcegen.agents.RequireAgent;
import org.example.educatorweb.resourcegen.agents.ReviewAgent;
import org.example.educatorweb.resourcegen.agents.generators.CodeGenerator;
import org.example.educatorweb.resourcegen.agents.generators.DocGenerator;
import org.example.educatorweb.resourcegen.agents.generators.HtmlGenerator;
import org.example.educatorweb.resourcegen.agents.generators.MindmapGenerator;
import org.example.educatorweb.resourcegen.agents.generators.QuizGenerator;
import org.example.educatorweb.resourcegen.agents.generators.PptGenerator;
import org.example.educatorweb.resourcegen.agents.generators.VideoGenerator;
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
    private final QuizGenerator quizGenerator;
    private final CodeGenerator codeGenerator;
    private final HtmlGenerator htmlGenerator;
    private final PptGenerator pptGenerator;
    private final VideoGenerator videoGenerator;
    private final ReviewAgent reviewAgent;

    public ResourceGenerationService(GraphOrchestrator orchestrator,
                                     RequireAgent requireAgent,
                                     DesignAgent designAgent,
                                     DocGenerator docGenerator,
                                     MindmapGenerator mindmapGenerator,
                                     QuizGenerator quizGenerator,
                                     CodeGenerator codeGenerator,
                                     HtmlGenerator htmlGenerator,
                                     PptGenerator pptGenerator,
                                     VideoGenerator videoGenerator,
                                     ReviewAgent reviewAgent) {
        this.orchestrator = orchestrator;
        this.requireAgent = requireAgent;
        this.designAgent = designAgent;
        this.docGenerator = docGenerator;
        this.mindmapGenerator = mindmapGenerator;
        this.quizGenerator = quizGenerator;
        this.codeGenerator = codeGenerator;
        this.htmlGenerator = htmlGenerator;
        this.pptGenerator = pptGenerator;
        this.videoGenerator = videoGenerator;
        this.reviewAgent = reviewAgent;
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
            .node("REVIEW", reviewAgent)
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
        if (types.contains(ResourceType.QUIZ) || types.isEmpty()) {
            builder.node("GEN_QUIZ", quizGenerator);
            genNodes.add("GEN_QUIZ");
        }
        if (types.contains(ResourceType.CODE) || types.isEmpty()) {
            builder.node("GEN_CODE", codeGenerator);
            genNodes.add("GEN_CODE");
        }
        if (types.contains(ResourceType.HTML) || types.isEmpty()) {
            builder.node("GEN_HTML", htmlGenerator);
            genNodes.add("GEN_HTML");
        }
        if (types.contains(ResourceType.PPT) || types.isEmpty()) {
            builder.node("GEN_PPT", pptGenerator);
            genNodes.add("GEN_PPT");
        }
        if (types.contains(ResourceType.VIDEO) || types.isEmpty()) {
            builder.node("GEN_VIDEO", videoGenerator);
            genNodes.add("GEN_VIDEO");
        }

        if (!genNodes.isEmpty()) {
            builder.fanOut("DESIGN", genNodes);
            for (String n : genNodes) builder.router(n, Router.ALWAYS_DONE);
            // After all generators complete, route to REVIEW
            builder.edge("DESIGN", "REVIEW");
            // REVIEW can retry back to DESIGN
            builder.retryEdge("REVIEW", "DESIGN");
            // Router on REVIEW: check state.stage() for next destination
            builder.router("REVIEW", state -> {
                return switch (state.stage()) {
                    case DESIGN -> "DESIGN";
                    case DONE -> "DONE";
                    case FALLBACK -> "FALLBACK";
                    default -> "DONE";
                };
            });
        } else {
            builder.router("DESIGN", Router.ALWAYS_DONE);
        }
        return builder.build();
    }
}
