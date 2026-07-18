package org.example.educatorweb.knowledgegraph.api;

import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository.KnowledgePointSummary;
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
    private static final int MAX_NODES = 300;

    private final KnowledgePointRepository kpRepo;

    public KnowledgeGraphController(KnowledgePointRepository kpRepo) {
        this.kpRepo = kpRepo;
    }

    /**
     * GET /api/knowledge-graph/overview
     * Returns nodes and edges for force-directed graph visualization.
     * Uses lightweight summary DTOs to avoid loading full entity graphs.
     */
    @GetMapping("/overview")
    public ResponseResult<Map<String, Object>> getOverview() {
        try {
            long total = kpRepo.count();
            log.info("KnowledgeGraph: total nodes in Neo4j = {}", total);

            // Use lightweight summary DTOs (id, name, category, difficulty only)
            List<KnowledgePointSummary> summaries = kpRepo.findAllSummaries();
            List<Map<String, Object>> nodes = new ArrayList<>();
            Set<String> nodeIds = new HashSet<>();

            int count = 0;
            for (KnowledgePointSummary s : summaries) {
                if (count >= MAX_NODES) break;
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", s.id());
                node.put("name", s.name() != null ? s.name() : s.id());
                node.put("category", normalizeCategory(s.category()));
                node.put("difficulty", s.difficulty());
                node.put("description", "");
                nodes.add(node);
                nodeIds.add(s.id());
                count++;
            }
            log.info("KnowledgeGraph: loaded {} nodes (lightweight)", nodes.size());

            // Fetch edges only for the loaded nodes (max 300 × 3 queries = 900 queries, manageable)
            Set<String> edgeKeys = new HashSet<>();
            List<Map<String, Object>> edges = new ArrayList<>();
            int edgeErrors = 0;

            for (String nid : nodeIds) {
                try {
                    for (var p : kpRepo.findPrerequisites(nid)) {
                        if (nodeIds.contains(p.getId())) {
                            String key = nid + "|REQUIRES|" + p.getId();
                            if (edgeKeys.add(key)) {
                                edges.add(Map.of("source", nid, "target", p.getId(), "relation", "REQUIRES"));
                            }
                        }
                    }
                } catch (Exception e) { edgeErrors++; }

                try {
                    for (var c : kpRepo.findSubPoints(nid)) {
                        if (nodeIds.contains(c.getId())) {
                            String key = nid + "|CONTAINS|" + c.getId();
                            if (edgeKeys.add(key)) {
                                edges.add(Map.of("source", nid, "target", c.getId(), "relation", "CONTAINS"));
                            }
                        }
                    }
                } catch (Exception e) { edgeErrors++; }

                try {
                    for (var r : kpRepo.findRelated(nid)) {
                        if (nodeIds.contains(r.getId())) {
                            String key1 = nid + "|RELATED|" + r.getId();
                            String key2 = r.getId() + "|RELATED|" + nid;
                            if (edgeKeys.add(key1) && edgeKeys.add(key2)) {
                                edges.add(Map.of("source", nid, "target", r.getId(), "relation", "RELATED_TO"));
                            }
                        }
                    }
                } catch (Exception e) { edgeErrors++; }
            }

            log.info("KnowledgeGraph: {} nodes, {} edges ({} edge errors)",
                nodes.size(), edges.size(), edgeErrors);
            return ResponseResult.success(Map.of(
                "nodes", nodes,
                "edges", edges,
                "totalInDb", total
            ));
        } catch (Exception e) {
            log.error("KnowledgeGraph: overview failed: {}", e.getMessage());
            return ResponseResult.error("知识图谱加载失败：" + e.getMessage());
        }
    }

    /** Map Neo4j raw category to one of 5 standard categories. */
    private static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) return "概念";
        String c = raw.strip();
        // Direct matches
        if (c.equals("数学基础") || c.equals("概念") || c.equals("算法") || c.equals("应用") || c.equals("工具")) {
            return c;
        }
        // Fuzzy mapping
        String lower = c.toLowerCase();
        if (lower.contains("math") || lower.contains("数学") || lower.contains("线性代数") || lower.contains("概率") || lower.contains("统计")) return "数学基础";
        if (lower.contains("concept") || lower.contains("基础") || lower.contains("概述") || lower.contains("简介") || lower.contains("理论") || lower.contains("原理")) return "概念";
        if (lower.contains("algorithm") || lower.contains("模型") || lower.contains("学习") || lower.contains("训练") || lower.contains("优化") || lower.contains("网络") || lower.contains("梯度") || lower.contains("回归") || lower.contains("分类") || lower.contains("聚类") || lower.contains("树") || lower.contains("森林")) return "算法";
        if (lower.contains("application") || lower.contains("应用") || lower.contains("实战") || lower.contains("案例") || lower.contains("系统") || lower.contains("推荐") || lower.contains("预测") || lower.contains("检测") || lower.contains("识别")) return "应用";
        if (lower.contains("tool") || lower.contains("工具") || lower.contains("库") || lower.contains("框架") || lower.contains("python") || lower.contains("代码") || lower.contains("编程")) return "工具";
        return "概念"; // fallback
    }
}
