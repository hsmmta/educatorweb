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
                    sp.description());
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
            You are a Machine Learning curriculum expert. Generate a comprehensive 3-layer
            knowledge graph for a university-level ML course. The three layers are:
              1. Course — the ML course itself and its sub-modules
              2. KnowledgePoint — all concepts, algorithms, techniques (≥80)
              3. LearningResource — textbooks, references for each knowledge point

            Requirements:
            - At least 80 knowledge points covering: 数学基础, 监督学习, 无监督学习, 深度学习, 集成学习, 模型评估与优化, 应用与工具
            - At least 5 courses covering the main ML sub-domains
            - At least 30 learning resources (textbooks, online courses, papers, videos)
            - KnowledgePoint: id (English slug), name (Chinese), category, difficulty (1-5),
              description, prerequisites (ids), relatedConcepts (ids), courseId, resourceIds
            - Course: id, name, institution (e.g. 斯坦福大学), duration (短期/中期/长期),
              type (理论/实践), rating (1.0-5.0), description, prerequisiteCourseIds
            - LearningResource: id, title, type (TEXTBOOK/VIDEO/EXERCISE/CODE/PAPER), url, description

            Output ONLY valid JSON, no markdown:
            {
              "courses": [
                {
                  "id": "ml_basics",
                  "name": "机器学习基础_中科大",
                  "institution": "中国科学技术大学",
                  "duration": "长期",
                  "type": "理论",
                  "rating": 4.8,
                  "description": "机器学习核心算法与理论基础",
                  "prerequisiteCourseIds": []
                },
                ...
              ],
              "knowledgePoints": [
                {
                  "id": "linear_regression",
                  "name": "线性回归",
                  "category": "算法",
                  "difficulty": 2,
                  "description": "通过拟合线性关系预测连续值",
                  "prerequisites": ["linear_algebra", "probability"],
                  "relatedConcepts": ["gradient_descent", "logistic_regression"],
                  "courseId": "ml_basics",
                  "resourceIds": ["zhou_ml", "li_hang_stats"]
                },
                ...
              ],
              "resources": [
                {
                  "id": "zhou_ml",
                  "title": "周志华《机器学习》",
                  "type": "TEXTBOOK",
                  "url": "",
                  "description": "机器学习领域经典中文教材，俗称西瓜书"
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
        List<String> prerequisites,
        List<String> relatedConcepts
    ) {}
}
