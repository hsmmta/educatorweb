package org.example.educatorweb.knowledgegraph.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.*;

/**
 * Seeds the Neo4j knowledge graph on first startup if no data exists.
 * Calls DeepSeek to generate ~80 ML knowledge points with relationships.
 */
public class KnowledgeGraphInitializer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphInitializer.class);

    private final KnowledgePointRepository repo;
    private final ModelProvider llmProvider;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphInitializer(KnowledgePointRepository repo, ModelProvider llmProvider) {
        this.repo = repo;
        this.llmProvider = llmProvider;
        this.objectMapper = new ObjectMapper();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        try {
            long count = repo.count();
            if (count > 0) {
                log.info("KnowledgeGraphInitializer: graph already has {} points, skipping seed", count);
                return;
            }
        } catch (Exception e) {
            log.warn("KnowledgeGraphInitializer: Neo4j not available, skipping seed: {}", e.getMessage());
            return;
        }

        log.info("KnowledgeGraphInitializer: Neo4j is empty, generating seed data via DeepSeek...");

        try {
            String json = generateSeedData();
            List<SeedPoint> seedPoints = objectMapper.readValue(json,
                new TypeReference<List<SeedPoint>>() {});

            // Phase 1: save all nodes (without relationships)
            Map<String, KnowledgePoint> nodeMap = new HashMap<>();
            for (SeedPoint sp : seedPoints) {
                KnowledgePoint node = new KnowledgePoint(
                    sp.id(), sp.name(), sp.category(), sp.difficulty(),
                    sp.description(), sp.chapter());
                repo.save(node);
                nodeMap.put(sp.id(), node);
            }
            log.info("KnowledgeGraphInitializer: saved {} knowledge point nodes", seedPoints.size());

            // Phase 2: link relationships (second pass to ensure all nodes exist)
            int relCount = 0;
            for (SeedPoint sp : seedPoints) {
                KnowledgePoint node = nodeMap.get(sp.id());
                if (node == null) continue;

                for (String preId : sp.prerequisites()) {
                    KnowledgePoint prereq = nodeMap.get(preId);
                    if (prereq != null) {
                        node.getPrerequisites().add(prereq);
                        relCount++;
                    }
                }
                for (String relId : sp.relatedConcepts()) {
                    KnowledgePoint related = nodeMap.get(relId);
                    if (related != null) {
                        node.getRelatedConcepts().add(related);
                        relCount++;
                    }
                }
                repo.save(node);
            }

            log.info("KnowledgeGraphInitializer: seed complete — {} nodes, {} relationships",
                seedPoints.size(), relCount);

        } catch (Exception e) {
            log.error("KnowledgeGraphInitializer: seed failed — will rely on runtime fallback: {}",
                e.getMessage());
        }
    }

    private String generateSeedData() {
        String prompt = """
            You are a Machine Learning curriculum expert. Generate a comprehensive knowledge graph
            for a university-level ML course. Requirements:
            - At least 80 knowledge points
            - Cover: 数学基础, 监督学习, 无监督学习, 深度学习, 集成学习, 模型评估与优化, 应用与工具
            - Each point must have: id (English slug), name (Chinese), category, difficulty (1-5),
              description, chapter, prerequisites (list of ids), relatedConcepts (list of ids)
            - prerequisites define strict "must-learn-before" dependencies
            - relatedConcepts link thematically related topics

            Output ONLY valid JSON, no markdown, no explanation:
            {
              "knowledgePoints": [
                {
                  "id": "linear_regression",
                  "name": "线性回归",
                  "category": "算法",
                  "difficulty": 2,
                  "description": "通过拟合线性关系预测连续值的监督学习基础算法",
                  "chapter": "监督学习",
                  "prerequisites": ["linear_algebra", "probability"],
                  "relatedConcepts": ["gradient_descent", "logistic_regression", "regularization"]
                },
                ...
              ]
            }
            """;

        String response = llmProvider.chat(prompt);
        if (response == null || response.isBlank()) {
            throw new RuntimeException("DeepSeek returned empty seed data");
        }
        response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();

        // Extract the "knowledgePoints" array from the wrapper object
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode pointsArray = root.get("knowledgePoints");
            if (pointsArray != null && pointsArray.isArray()) {
                return objectMapper.writeValueAsString(pointsArray);
            }
            return response; // fallback: treat as raw array
        } catch (Exception e) {
            return response; // fallback
        }
    }

    // ---- DTO for JSON parsing (flat fields, no Neo4j annotations) ----

    record SeedPoint(
        String id,
        String name,
        String category,
        int difficulty,
        String description,
        String chapter,
        List<String> prerequisites,
        List<String> relatedConcepts
    ) {}
}
