package org.example.educatorweb.knowledgegraph.api;

import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/knowledge-graph")
public class KnowledgeGraphController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphController.class);
    private static final int MAX_NODES = 500;

    private final KnowledgePointRepository kpRepo;

    public KnowledgeGraphController(KnowledgePointRepository kpRepo) {
        this.kpRepo = kpRepo;
    }

    /**
     * GET /api/knowledge-graph/overview
     * Returns nodes and edges for force-directed graph visualization.
     */
    @GetMapping("/overview")
    public ResponseResult<Map<String, Object>> getOverview() {
        try {
            long total = kpRepo.count();
            log.info("KnowledgeGraph: total nodes in Neo4j = {}", total);

            // Fetch all knowledge points (limited)
            Iterable<KnowledgePoint> allKps = kpRepo.findAll();
            List<Map<String, Object>> nodes = new ArrayList<>();
            Set<String> nodeIds = new HashSet<>();

            int count = 0;
            for (KnowledgePoint kp : allKps) {
                if (count >= MAX_NODES) break;
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", kp.getId());
                node.put("name", kp.getName() != null ? kp.getName() : kp.getId());
                node.put("category", kp.getCategory() != null ? kp.getCategory() : "未分类");
                node.put("difficulty", kp.getDifficulty());
                node.put("description", kp.getDescription() != null
                    ? kp.getDescription().substring(0, Math.min(100, kp.getDescription().length()))
                    : "");
                nodes.add(node);
                nodeIds.add(kp.getId());
                count++;
            }

            // Fetch edges: REQUIRES, CONTAINS, RELATED_TO (deduplicated)
            Set<String> edgeKeys = new HashSet<>();
            List<Map<String, Object>> edges = new ArrayList<>();

            for (String nid : nodeIds) {
                try {
                    // REQUIRES
                    for (KnowledgePoint p : kpRepo.findPrerequisites(nid)) {
                        if (nodeIds.contains(p.getId())) {
                            String key = nid + "|REQUIRES|" + p.getId();
                            if (edgeKeys.add(key)) {
                                edges.add(Map.of("source", nid, "target", p.getId(), "relation", "REQUIRES"));
                            }
                        }
                    }
                    // CONTAINS
                    for (KnowledgePoint c : kpRepo.findSubKnowledgePoints(nid)) {
                        if (nodeIds.contains(c.getId())) {
                            String key = nid + "|CONTAINS|" + c.getId();
                            if (edgeKeys.add(key)) {
                                edges.add(Map.of("source", nid, "target", c.getId(), "relation", "CONTAINS"));
                            }
                        }
                    }
                    // RELATED_TO
                    for (KnowledgePoint r : kpRepo.findRelated(nid)) {
                        if (nodeIds.contains(r.getId())) {
                            String key1 = nid + "|RELATED|" + r.getId();
                            String key2 = r.getId() + "|RELATED|" + nid;
                            if (edgeKeys.add(key1) && edgeKeys.add(key2)) {
                                edges.add(Map.of("source", nid, "target", r.getId(), "relation", "RELATED_TO"));
                            }
                        }
                    }
                } catch (Exception e) {
                    // skip individual node errors
                }
            }

            log.info("KnowledgeGraph: returning {} nodes, {} edges", nodes.size(), edges.size());
            return ResponseResult.success(Map.of(
                "nodes", nodes,
                "edges", edges,
                "totalInDb", total
            ));
        } catch (Exception e) {
            log.error("KnowledgeGraph: overview failed: {}", e.getMessage());
            return ResponseResult.error("Failed to load knowledge graph: " + e.getMessage());
        }
    }
}
