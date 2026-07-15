package org.example.educatorweb.resourcegen.orchestration;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ProgressEvent;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class GraphOrchestratorTest {

    private final GraphOrchestrator orchestrator = new GraphOrchestrator(4);

    private static GenerateRequest sampleRequest() {
        return new GenerateRequest("student-1", "quadratic-equations",
            List.of(ResourceType.DOC));
    }

    // ---- Test 1: Simple two-node serial execution (A -> B -> DONE) ----
    @Test
    void shouldExecuteSerialPipeline() {
        AtomicBoolean aExecuted = new AtomicBoolean(false);
        AtomicBoolean bExecuted = new AtomicBoolean(false);

        GenerationGraph graph = GenerationGraph.builder()
            .node("A", state -> {
                aExecuted.set(true);
                return state.withStage(ProgressStage.REQUIRE);
            })
            .node("B", state -> {
                bExecuted.set(true);
                return state.withStage(ProgressStage.DESIGN);
            })
            .edge("A", "B")
            .edge("B", "DONE")
            .build();

        GenerationState initialState = GenerationState.initial(sampleRequest());

        StepVerifier.create(orchestrator.run(graph, initialState))
            .expectNextMatches(evt -> "INIT".equals(evt.stage()) && evt.message().contains("started"))
            .expectNextMatches(evt -> evt.message().contains("Entering") && evt.message().contains("'A'"))
            .expectNextMatches(evt -> "REQUIRE".equals(evt.stage()) && evt.message().contains("completed"))
            .expectNextMatches(evt -> evt.message().contains("Entering") && evt.message().contains("'B'"))
            .expectNextMatches(evt -> "DESIGN".equals(evt.stage()) && evt.message().contains("completed"))
            .expectNextMatches(evt -> "DONE".equals(evt.stage()) && evt.message().contains("successfully"))
            .verifyComplete();

        assertThat(aExecuted.get()).isTrue();
        assertThat(bExecuted.get()).isTrue();
    }

    // ---- Test 2: FanOut with 2 parallel nodes (verify both execute) ----
    @Test
    void shouldExecuteFanOutInParallel() {
        AtomicBoolean branch1Executed = new AtomicBoolean(false);
        AtomicBoolean branch2Executed = new AtomicBoolean(false);

        GenerationGraph graph = GenerationGraph.builder()
            .node("START", state -> state)
            .node("GEN_DOC", state -> {
                branch1Executed.set(true);
                return state;
            })
            .node("GEN_QUIZ", state -> {
                branch2Executed.set(true);
                return state;
            })
            .edge("START", "GENERATING")
            .fanOut("GENERATING", List.of("GEN_DOC", "GEN_QUIZ"))
            .edge("GENERATING", "DONE")
            .build();

        GenerationState initialState = GenerationState.initial(sampleRequest());

        StepVerifier.create(orchestrator.run(graph, initialState))
            .expectNextMatches(evt -> evt.message().contains("started"))
            .thenConsumeWhile(evt -> !"DONE".equals(evt.stage()))
            .expectNextMatches(evt -> "DONE".equals(evt.stage()))
            .verifyComplete();

        assertThat(branch1Executed.get()).isTrue();
        assertThat(branch2Executed.get()).isTrue();
    }

    // ---- Test 3: Error handling (node throws -> FALLBACK) ----
    @Test
    void shouldHandleNodeExceptionAndRouteToFallback() {
        GenerationGraph graph = GenerationGraph.builder()
            .node("A", state -> {
                throw new RuntimeException("Simulated failure");
            })
            .edge("A", "DONE")
            .build();

        GenerationState initialState = GenerationState.initial(sampleRequest());

        StepVerifier.create(orchestrator.run(graph, initialState))
            .expectNextMatches(evt -> evt.message().contains("started"))
            .expectNextMatches(evt -> evt.message().contains("Entering"))
            .expectNextMatches(evt -> evt.message().contains("Error") && evt.message().contains("Simulated failure"))
            .expectNextMatches(evt -> evt.stage().equals("FALLBACK") && evt.message().contains("failed"))
            .verifyComplete();
    }

    // ---- Test 4: Invalid graph — cycle detection triggers build error ----
    @Test
    void shouldDetectCycleAndRejectBuild() {
        // START -> A -> B -> A creates a cycle, even though START is a valid start node
        assertThatThrownBy(() ->
            GenerationGraph.builder()
                .node("START", state -> state)
                .node("A", state -> state)
                .node("B", state -> state)
                .edge("START", "A")
                .edge("A", "B")
                .edge("B", "A")
                .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("cycle");
    }

    // ---- Test 5: Graph without start node should fail at build ----
    @Test
    void shouldRejectGraphWithNoStartNode() {
        // All nodes have incoming edges from fanOut source X (not a registered node),
        // so no node qualifies as a start node.
        assertThatThrownBy(() ->
            GenerationGraph.builder()
                .node("A", state -> state)
                .node("B", state -> state)
                .fanOut("X", List.of("A", "B"))
                .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("start node");
    }
}
