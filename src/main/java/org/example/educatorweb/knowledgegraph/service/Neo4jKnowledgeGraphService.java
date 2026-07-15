package org.example.educatorweb.knowledgegraph.service;

import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Neo4j-backed implementation of KnowledgeGraphService.
 * Resolves knowledge context via Cypher graph traversal, with DeepSeek LLM fallback
 * for knowledge points not yet in the graph.
 */
@Service
@Primary
public class Neo4jKnowledgeGraphService implements KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jKnowledgeGraphService.class);

    private final KnowledgePointRepository repo;
    private final LlmKnowledgeExtractor extractor;

    public Neo4jKnowledgeGraphService(KnowledgePointRepository repo,
                                       LlmKnowledgeExtractor extractor) {
        this.repo = repo;
        this.extractor = extractor;
    }

    @Override
    public KnowledgeContext queryContext(String knowledgePoint) {
        // Try exact ID match first, then name match
        Optional<KnowledgePoint> nodeOpt = Optional.empty();
        try {
            nodeOpt = repo.findById(knowledgePoint);
            if (nodeOpt.isEmpty()) {
                nodeOpt = repo.findByName(knowledgePoint);
            }
        } catch (Exception e) {
            log.debug("Neo4jKnowledgeGraphService: repo lookup failed for '{}': {}", knowledgePoint, e.getMessage());
        }

        if (nodeOpt.isPresent()) {
            KnowledgePoint node = nodeOpt.get();
            try {
                List<String> prerequisites = repo.findPrerequisites(node.getId()).stream()
                    .map(KnowledgePoint::getName).toList();
                List<String> successors = repo.findSuccessors(node.getId()).stream()
                    .map(KnowledgePoint::getName).toList();
                List<String> related = repo.findRelated(node.getId()).stream()
                    .map(KnowledgePoint::getName).toList();

                return new KnowledgeContext(prerequisites, successors, related, node.getDifficulty());
            } catch (Exception e) {
                log.warn("Neo4j query failed for '{}': {}", knowledgePoint, e.getMessage());
                return new KnowledgeContext(List.of(), List.of(), List.of(), 0);
            }
        }

        // Not in graph — fire async LLM enrichment, return empty context immediately.
        // The DeepSeek call can take 10-30s and is NOT on the critical chat path;
        // making it async keeps chat responsive. Next time this topic is queried,
        // it will be served from Neo4j (enriched by the async task).
        log.info("Knowledge point '{}' not in Neo4j, scheduling async LLM enrichment", knowledgePoint);
        final String topic = knowledgePoint;
        CompletableFuture.runAsync(() -> {
            try {
                extractor.extract(topic);
            } catch (Exception e) {
                log.debug("Async KG enrichment failed for '{}': {}", topic, e.getMessage());
                Thread.interrupted(); // clear any stale flag on this worker thread
            }
        });
        return new KnowledgeContext(List.of(), List.of(), List.of(), 0);
    }
}
