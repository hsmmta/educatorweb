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
                log.warn("Neo4j query failed for '{}', falling back to LLM: {}",
                    knowledgePoint, e.getMessage());
                return extractor.extract(knowledgePoint);
            }
        }

        // Not in graph — use LLM fallback (which also persists to Neo4j)
        log.info("Knowledge point '{}' not in Neo4j, using LLM fallback", knowledgePoint);
        try {
            return extractor.extract(knowledgePoint);
        } catch (Exception e) {
            log.warn("LLM fallback also failed for '{}': {}", knowledgePoint, e.getMessage());
            return new KnowledgeContext(List.of(), List.of(), List.of(), 0);
        }
    }
}
