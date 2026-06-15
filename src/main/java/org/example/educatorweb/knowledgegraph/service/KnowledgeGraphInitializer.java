package org.example.educatorweb.knowledgegraph.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.neo4j.driver.Driver;
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
    private final Driver neo4jDriver;
    private final ModelProvider llmProvider;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphInitializer(KnowledgePointRepository repo,
                                      Driver neo4jDriver,
                                      ModelProvider llmProvider) {
        this.repo = repo;
        this.neo4jDriver = neo4jDriver;
        this.llmProvider = llmProvider;
        this.objectMapper = new ObjectMapper();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        long count;
        try (var session = neo4jDriver.session()) {
            var result = session.run("MATCH (n:KnowledgePoint) RETURN count(n) AS c");
            count = result.single().get("c").asLong();
        } catch (Exception e) {
            log.warn("KnowledgeGraphInitializer: Neo4j not available, skipping seed: {}", e.getMessage());
            return;
        }

        if (count > 0) {
            log.info("KnowledgeGraphInitializer: graph already has {} points, skipping seed", count);
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
        // Load reference JSON extracted from GitHub repos as context
        String referenceContext = loadReferenceContext();

        String prompt = """
            You are a Machine Learning curriculum expert. Generate a comprehensive 3-layer
            knowledge graph for a university-level ML course. The three layers are:
              1. Course — the ML course itself and its sub-modules
              2. KnowledgePoint — all concepts, algorithms, techniques (≥80)
              3. LearningResource — textbooks, references for each knowledge point

            ## Reference Data (from real-world ML courses)
            Below is structured data extracted from microsoft/ML-For-Beginners (26 lessons)
            and eriklindernoren/ML-From-Scratch (20+ pure Python implementations).
            Use this as your primary reference. All knowledge point names MUST be in Chinese.

            %s

            ## Requirements
            - At least 80 knowledge points covering: 数学基础, 监督学习, 无监督学习, 深度学习, 集成学习, 模型评估与优化, 应用与工具
            - At least 8 courses covering the main ML sub-domains (use the reference course structure, add Chinese name + institution)
            - At least 40 learning resources (include the reference code repos + classic textbooks)
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
        log.info("KnowledgeGraphInitializer: DeepSeek responded with {} chars", response.length());

        // Extract the "knowledgePoints" array from the wrapper object
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.isObject() && root.has("knowledgePoints")) {
                return objectMapper.writeValueAsString(root.get("knowledgePoints"));
            }
            // If response is already an array, return as-is
            if (root.isArray()) {
                return response;
            }
            return response;
        } catch (Exception e) {
            log.warn("KnowledgeGraphInitializer: JSON parse failed, trying raw: {}", e.getMessage());
            // Last resort: wrap in brackets if it looks like JSON
            String stripped = response.strip();
            if (!stripped.startsWith("[") && stripped.contains("\"id\"")) {
                stripped = "[" + stripped + "]";
            }
            return stripped;
        }
    }

    /**
     * Compact reference data extracted from GitHub repos.
     * Provides real-world ML course structure as context for DeepSeek.
     */
    private String loadReferenceContext() {
        return """
            ## Reference Course Structure (microsoft/ML-For-Beginners, 86.9k Stars)
            9 weekly modules: Introduction → Regression → Web-App → Classification
            → Clustering → NLP → TimeSeries → Reinforcement → Real-World
            Each module has 2-4 lessons covering one ML concept.

            ## Reference Knowledge Points (26 lessons extracted)
            intro_to_ml, history_of_ml, fairness, techniques_of_ml (Introduction)
            tools, data, linear, logistic (Regression)
            web_app (Web-App)
            introduction, classifiers_1, classifiers_2, applied (Classification)
            visualize, k_means (Clustering)
            intro_to_nlp, tasks, translation_sentiment, hotel_reviews (NLP)
            introduction, arima, svr (TimeSeries)
            qlearning, gym (Reinforcement)
            applications, debugging_ml_models (Real-World)

            ## Reference Resources (eriklindernoren/ML-From-Scratch, 31.9k Stars + classics)
            Code (20+): adaboost, bayesian_regression, decision_tree, gradient_boosting,
            k_nearest_neighbors, logistic_regression, multilayer_perceptron, naive_bayes,
            perceptron, random_forest, regression, support_vector_machine, xgboost,
            k_means, dbscan, gaussian_mixture_model, principal_component_analysis,
            apriori, autoencoder, generative_adversarial_network, neural_network
            Textbooks: 周志华《机器学习》, 李航《统计学习方法》, CS229 notes,
            Goodfellow《Deep Learning》, Bishop PRML, Hastie ESL
            Videos: 吴恩达 Coursera ML, DeepLearning.AI specialization
            """;
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
