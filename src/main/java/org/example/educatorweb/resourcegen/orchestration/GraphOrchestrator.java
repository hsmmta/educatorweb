package org.example.educatorweb.resourcegen.orchestration;

import org.example.educatorweb.common.model.ProgressEvent;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.infrastructure.CheckpointService;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.*;

public class GraphOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GraphOrchestrator.class);

    private final ExecutorService threadPool;
    private CheckpointService checkpointService; // nullable — set by Spring if Redis is available

    public GraphOrchestrator(int threadPoolSize) {
        this.threadPool = Executors.newFixedThreadPool(threadPoolSize);
    }

    /**
     * Set the optional checkpoint service. When non-null, the orchestrator saves
     * state snapshots after each node execution and attempts resume on restart.
     * Called by {@code ResourceGenConfig} when a Redis-backed
     * {@link CheckpointService} bean is available in the context.
     */
    public void setCheckpointService(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    public Flux<ProgressEvent> run(GenerationGraph graph, GenerationState initialState) {
        Sinks.Many<ProgressEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

        return sink.asFlux().doOnSubscribe(s -> {
            CompletableFuture.runAsync(() -> {
                try {
                    executePipeline(graph, initialState, sink);
                } catch (Exception e) {
                    handleFatalError(sink, initialState, e);
                } finally {
                    sink.tryEmitComplete();
                }
            }, threadPool);
        });
    }

    private void executePipeline(GenerationGraph graph, GenerationState state,
                                  Sinks.Many<ProgressEvent> sink) {
        // Attempt resume from checkpoint
        if (checkpointService != null) {
            Optional<GenerationState> checkpoint = checkpointService.load(state.requestId());
            if (checkpoint.isPresent()) {
                state = checkpoint.get();
                log.info("Resumed from checkpoint for requestId={}, stage={}",
                    state.requestId(), state.stage());
                emit(sink, state, "Resumed from checkpoint at stage " + state.stage(), 0);
            }
        }

        String startNode = GenerationGraph.findStartNode(graph);
        if (startNode == null) {
            String msg = "Graph has no start node";
            emit(sink, state, msg, 0);
            sink.tryEmitComplete();
            return;
        }

        emit(sink, state, "Pipeline started", 0);

        String currentNode = startNode;
        int totalNodes = countExecutableNodes(graph);
        int executedCount = 0;

        while (currentNode != null && !"DONE".equals(currentNode) && !"FALLBACK".equals(currentNode)) {
            state = state.withStage(mapNodeToStage(currentNode));
            emit(sink, state, "Entering node '" + currentNode + "'",
                calculateProgress(executedCount, totalNodes));

            // Execute the node's agent if present (run before fanOut so
            // downstream generators receive the node's output, e.g. blueprint)
            AgentNode agent = graph.nodes.get(currentNode);
            if (agent != null) {
                try {
                    state = agent.execute(state);
                    executedCount++;
                    saveCheckpoint(state);
                    emit(sink, state, "Node '" + currentNode + "' completed",
                        calculateProgress(executedCount, totalNodes));
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    state = state.withError(errorMsg);
                    saveCheckpoint(state);
                    emit(sink, state, "Error in node '" + currentNode + "': " + errorMsg, 99);
                    break;
                }
            }

            // Execute fanOut if configured for this node
            if (graph.fanOuts.containsKey(currentNode)) {
                try {
                    state = executeFanOut(graph, currentNode, state, sink);
                    executedCount += graph.fanOuts.get(currentNode).size();
                    saveCheckpoint(state);
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    state = state.withError(errorMsg);
                    saveCheckpoint(state);
                    emit(sink, state, "FanOut error in '" + currentNode + "': " + errorMsg, 99);
                    break;
                }
            }

            // Determine next node
            if (graph.routers.containsKey(currentNode)) {
                currentNode = graph.routers.get(currentNode).route(state);
            } else if (graph.edges.containsKey(currentNode)) {
                List<String> nextNodes = graph.edges.get(currentNode);
                currentNode = nextNodes.isEmpty() ? "DONE" : nextNodes.get(0);
            } else {
                currentNode = "DONE";
            }
        }

        // Terminal handling
        if (ProgressStage.FALLBACK.equals(state.stage()) || state.error() != null) {
            if (!ProgressStage.FALLBACK.equals(state.stage())) {
                state = state.withStage(ProgressStage.FALLBACK);
            }
            saveCheckpoint(state);
            emit(sink, state, "Pipeline failed: " + state.error(), 100);
        } else {
            state = state.withStage(ProgressStage.DONE);
            deleteCheckpoint(state); // clean up on successful completion

            // Build payload with all generated resources
            Map<String, Object> payload = new LinkedHashMap<>();
            for (var entry : state.results().entrySet()) {
                var resource = entry.getValue();
                Map<String, Object> resourceData = new LinkedHashMap<>();
                resourceData.put("resourceId", resource.resourceId());
                resourceData.put("type", resource.type().name());
                resourceData.put("title", resource.title());
                resourceData.put("content", resource.content());
                resourceData.put("metadata", resource.metadata());
                // For file-based resources (PPT, VIDEO), expose a web-accessible download path
                String typeName = resource.type().name();
                if (("PPT".equals(typeName) || "VIDEO".equals(typeName))
                        && resource.content() != null
                        && !resource.content().startsWith("Generation failed")) {
                    try {
                        String filename = java.nio.file.Path.of(resource.content()).getFileName().toString();
                        resourceData.put("downloadPath", state.requestId() + "/" + filename);
                    } catch (Exception e) {
                        log.warn("Could not derive download path from {}: {}", resource.content(), e.getMessage());
                    }
                }
                payload.put(entry.getKey().name(), resourceData);
            }
            emit(sink, state, "Pipeline completed successfully — " + state.results().size() + " resources generated", 100, payload);
        }
    }

    private void saveCheckpoint(GenerationState state) {
        if (checkpointService != null) {
            try {
                checkpointService.save(state.requestId(), state);
            } catch (Exception e) {
                log.debug("Checkpoint save skipped (non-critical): {}", e.getMessage());
            }
        }
    }

    private void deleteCheckpoint(GenerationState state) {
        if (checkpointService != null) {
            try {
                checkpointService.delete(state.requestId());
            } catch (Exception e) {
                log.debug("Checkpoint delete skipped (non-critical): {}", e.getMessage());
            }
        }
    }

    private GenerationState executeFanOut(GenerationGraph graph, String sourceNode,
                                           GenerationState state,
                                           Sinks.Many<ProgressEvent> sink) {
        List<String> fanOutTargets = graph.fanOuts.get(sourceNode);
        List<CompletableFuture<GenerationState>> futures = new ArrayList<>();

        for (String target : fanOutTargets) {
            AgentNode agent = graph.nodes.get(target);
            if (agent == null) {
                throw new IllegalStateException(
                    "FanOut target '" + target + "' has no agent registered");
            }
            CompletableFuture<GenerationState> future = CompletableFuture.supplyAsync(() -> {
                GenerationState result = agent.execute(state);
                emit(sink, result, "FanOut node '" + target + "' completed", 0);
                return result;
            }, threadPool);
            futures.add(future);
        }

        // Wait for all branches to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("FanOut execution failed: " + cause.getMessage(), cause);
        }

        // Merge all branch results
        GenerationState merged = state;
        for (CompletableFuture<GenerationState> future : futures) {
            merged = mergeResults(merged, future.join());
        }
        return merged;
    }

    private GenerationState mergeResults(GenerationState base, GenerationState branch) {
        Map<ResourceType, org.example.educatorweb.resourcegen.model.GeneratedResource> mergedResults =
            new LinkedHashMap<>(base.results());
        mergedResults.putAll(branch.results());

        List<org.example.educatorweb.resourcegen.model.QualityReport> mergedReviews = new ArrayList<>(base.reviews());
        mergedReviews.addAll(branch.reviews());

        org.example.educatorweb.resourcegen.model.ResourceBlueprint mergedBlueprint =
            base.blueprint() != null ? base.blueprint() : branch.blueprint();

        return new GenerationState(
            base.requestId(), base.studentId(), base.knowledgePoint(),
            base.types(),
            base.profile() != null ? base.profile() : branch.profile(),
            base.knowledgeContext() != null ? base.knowledgeContext() : branch.knowledgeContext(),
            base.ragContext() != null ? base.ragContext() : branch.ragContext(),
            mergedBlueprint,
            mergedResults,
            mergedReviews,
            base.reviewRetries() + branch.reviewRetries(),
            base.stage(),
            base.error() != null ? base.error() : branch.error()
        );
    }

    private void emit(Sinks.Many<ProgressEvent> sink, GenerationState state,
                      String message, int progressPercent) {
        emit(sink, state, message, progressPercent, null);
    }

    private void emit(Sinks.Many<ProgressEvent> sink, GenerationState state,
                      String message, int progressPercent, Map<String, Object> payload) {
        sink.tryEmitNext(new ProgressEvent(
            state.requestId(),
            state.stage() != null ? state.stage().name() : "INIT",
            message,
            progressPercent,
            payload
        ));
    }

    private void handleFatalError(Sinks.Many<ProgressEvent> sink,
                                   GenerationState state, Exception e) {
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        GenerationState errorState = state.withError(errorMsg);
        emit(sink, errorState, "Fatal error: " + errorMsg, 100);
    }

    private ProgressStage mapNodeToStage(String nodeName) {
        try {
            return ProgressStage.valueOf(nodeName);
        } catch (IllegalArgumentException e) {
            return ProgressStage.GENERATING;
        }
    }

    private int calculateProgress(int executed, int total) {
        if (total <= 0) return 0;
        return Math.min(100, (executed * 100) / total);
    }

    private int countExecutableNodes(GenerationGraph graph) {
        int count = graph.nodes.size();
        // Count fanOut targets that don't have their own node entry
        for (List<String> targets : graph.fanOuts.values()) {
            for (String target : targets) {
                if (!graph.nodes.containsKey(target) || graph.nodes.get(target) == null) {
                    count++;
                }
            }
        }
        // Remove DONE/FALLBACK if they appear as nodes
        int adjusted = 0;
        for (String node : graph.nodes.keySet()) {
            if (!"DONE".equals(node) && !"FALLBACK".equals(node)) {
                adjusted++;
            }
        }
        // fanOut targets with null agents still need to execute
        Set<String> allTargets = new HashSet<>();
        for (List<String> targets : graph.fanOuts.values()) {
            allTargets.addAll(targets);
        }
        for (String target : allTargets) {
            if (graph.nodes.containsKey(target) && graph.nodes.get(target) != null) {
                // already counted in adjusted
            } else if (!"DONE".equals(target) && !"FALLBACK".equals(target)) {
                adjusted++;
            }
        }
        return adjusted;
    }

    public void shutdown() {
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Thread pool did not terminate gracefully");
                }
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
