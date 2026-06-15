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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        String referenceContext = loadReferenceContext();

        List<String> batches = List.of(
            "数学基础（线性代数、概率论、微积分、最优化等）",
            "监督学习核心算法（线性回归、逻辑回归、决策树、SVM、KNN、朴素贝叶斯等）",
            "无监督学习（KMeans、PCA、DBSCAN、GMM、层次聚类、关联规则等）",
            "深度学习（CNN、RNN、LSTM、Transformer、GAN、反向传播、激活函数等）",
            "集成学习与模型优化（Bagging、Boosting、XGBoost、正则化、交叉验证等）",
            "应用与工具（NLP、CV、推荐系统、sklearn、PyTorch、特征工程等）"
        );

        List<String> allPointsJson = new ArrayList<>();
        for (String batch : batches) {
            String prompt = """
                You are a ML curriculum expert. Generate knowledge points for topic: %s.
                Reference (microsoft/ML-For-Beginners + ML-From-Scratch):
                %s

                Output a JSON array of 10-15 knowledge points for this topic ONLY:
                [{
                  "id":"english_slug",
                  "name":"中文名",
                  "category":"%s",
                  "difficulty":1-5,
                  "description":"一句话描述",
                  "prerequisites":["id1","id2"],
                  "relatedConcepts":["id3"],
                  "courseId":"ml_basics",
                  "resourceIds":[]
                }]
                Output ONLY the JSON array, no markdown, no explanation. Maximum 3000 characters.
                """.formatted(batch, referenceContext, guessCategory(batch));

            log.info("KnowledgeGraphInitializer: generating batch: {}", batch);
            String response = llmProvider.chat(prompt);
            if (response != null && !response.isBlank()) {
                response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();
                // Extract array from possible wrapper
                if (response.startsWith("{")) {
                    try {
                        JsonNode root = objectMapper.readTree(response);
                        if (root.isObject()) {
                            for (var field : java.util.List.of("knowledgePoints", "points", "data")) {
                                if (root.has(field)) {
                                    response = objectMapper.writeValueAsString(root.get(field));
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                allPointsJson.add(response);
                log.info("KnowledgeGraphInitializer: batch '{}' returned {} chars", batch, response.length());
            }
        }

        // Merge all batches into one array
        StringBuilder merged = new StringBuilder("[");
        boolean first = true;
        for (String json : allPointsJson) {
            if (json.startsWith("[")) json = json.substring(1);
            if (json.endsWith("]")) json = json.substring(0, json.length() - 1);
            String trimmed = json.trim();
            if (trimmed.isEmpty()) continue;
            if (!first && !trimmed.startsWith(",")) merged.append(",");
            merged.append(trimmed);
            first = false;
        }
        merged.append("]");

        String result = merged.toString();
        log.info("KnowledgeGraphInitializer: merged {} batches into {} chars", allPointsJson.size(), result.length());
        return result;
    }

    private String guessCategory(String batch) {
        if (batch.contains("数学")) return "数学基础";
        if (batch.contains("监督")) return "算法";
        if (batch.contains("无监督")) return "算法";
        if (batch.contains("深度")) return "深度学习";
        if (batch.contains("集成") || batch.contains("优化")) return "算法";
        return "应用";
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
