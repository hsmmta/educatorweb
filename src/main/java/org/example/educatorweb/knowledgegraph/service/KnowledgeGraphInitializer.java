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

        try (var session = neo4jDriver.session()) {
            String json = generateSeedData();
            List<SeedPoint> seedPoints = objectMapper.readValue(json,
                new TypeReference<List<SeedPoint>>() {});

            // Phase 1: create all nodes via raw Cypher
            for (SeedPoint sp : seedPoints) {
                session.run("""
                    MERGE (n:KnowledgePoint {id: $id})
                    SET n.name = $name, n.category = $category, n.difficulty = $difficulty,
                        n.description = $description
                    """,
                    Map.of("id", sp.id(), "name", sp.name(), "category", sp.category(),
                        "difficulty", sp.difficulty(), "description",
                        sp.description() != null ? sp.description() : ""));
            }
            log.info("KnowledgeGraphInitializer: created {} knowledge point nodes", seedPoints.size());

            // Phase 2: create relationships
            int relCount = 0;
            for (SeedPoint sp : seedPoints) {
                for (String preId : sp.prerequisites()) {
                    var result = session.run("""
                        MATCH (a:KnowledgePoint {id: $from}), (b:KnowledgePoint {id: $to})
                        MERGE (a)-[:REQUIRES]->(b)
                        RETURN count(*) AS c
                        """,
                        Map.of("from", sp.id(), "to", preId));
                    if (result.hasNext() && result.single().get("c").asInt() > 0) relCount++;
                }
                for (String relId : sp.relatedConcepts()) {
                    var result = session.run("""
                        MATCH (a:KnowledgePoint {id: $from}), (b:KnowledgePoint {id: $to})
                        MERGE (a)-[:RELATED_TO]->(b)
                        RETURN count(*) AS c
                        """,
                        Map.of("from", sp.id(), "to", relId));
                    if (result.hasNext() && result.single().get("c").asInt() > 0) relCount++;
                }
            }

            log.info("KnowledgeGraphInitializer: seed complete — {} KP nodes, {} relationships",
                seedPoints.size(), relCount);

            // Phase 3: generate Courses and link to KnowledgePoints
            seedCourses(session);

            // Phase 4: generate LearningResources and link to KnowledgePoints
            seedResources(session);

            log.info("KnowledgeGraphInitializer: full 3-layer seed complete!");

        } catch (Exception e) {
            log.error("KnowledgeGraphInitializer: seed failed — will rely on runtime fallback: {}",
                e.getMessage());
        }
    }

    private void seedCourses(org.neo4j.driver.Session session) {
        String prompt = """
            You are a ML curriculum expert. Based on the knowledge points already generated
            for a university ML course covering: 数学基础, 监督学习, 无监督学习, 深度学习,
            集成学习, 模型评估与优化, 应用与工具.

            Generate 8 courses that cover these ML domains. Reference (microsoft/ML-For-Beginners):
            %s

            Output a JSON array of courses:
            [{"id":"ml_basics","name":"机器学习基础_中科大","institution":"中国科学技术大学",
              "duration":"长期","type":"理论","rating":4.8,
              "description":"机器学习核心算法与理论基础",
              "knowledgePointIds":["linear_regression","logistic_regression","svm","decision_tree","knn"],
              "prerequisiteCourseIds":[]}]
            Output ONLY the JSON array, no markdown.
            """.formatted(loadReferenceContext());

        String response = llmProvider.chat(prompt);
        if (response == null || response.isBlank()) return;
        response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();

        try {
            if (response.startsWith("{")) {
                JsonNode root = objectMapper.readTree(response);
                if (root.has("courses")) response = objectMapper.writeValueAsString(root.get("courses"));
            }
            List<Map<String, Object>> courses = objectMapper.readValue(response,
                new TypeReference<List<Map<String, Object>>>() {});

            for (var c : courses) {
                String id = (String) c.get("id");
                String name = (String) c.getOrDefault("name", "");
                String institution = (String) c.getOrDefault("institution", "");
                String duration = (String) c.getOrDefault("duration", "短期");
                String type = (String) c.getOrDefault("type", "理论");
                double rating = c.get("rating") instanceof Number n ? n.doubleValue() : 4.0;
                String desc = (String) c.getOrDefault("description", "");

                session.run("""
                    MERGE (c:Course {id: $id})
                    SET c.name = $name, c.institution = $institution, c.duration = $duration,
                        c.type = $type, c.rating = $rating, c.description = $desc
                    """,
                    Map.of("id", id, "name", name, "institution", institution,
                        "duration", duration, "type", type, "rating", rating, "desc", desc));

                @SuppressWarnings("unchecked")
                List<String> kpIds = (List<String>) c.getOrDefault("knowledgePointIds", List.of());
                for (String kpId : kpIds) {
                    session.run("""
                        MATCH (c:Course {id: $cid}), (kp:KnowledgePoint {id: $kpid})
                        MERGE (c)-[:CONTAINS_KNOWLEDGE]->(kp)
                        """, Map.of("cid", id, "kpid", kpId));
                }

                @SuppressWarnings("unchecked")
                List<String> preCourseIds = (List<String>) c.getOrDefault("prerequisiteCourseIds", List.of());
                for (String preId : preCourseIds) {
                    session.run("""
                        MATCH (c:Course {id: $cid}), (pre:Course {id: $preId})
                        MERGE (c)-[:PREREQUISITE]->(pre)
                        """, Map.of("cid", id, "preId", preId));
                }
            }
            log.info("KnowledgeGraphInitializer: created {} course nodes", courses.size());
        } catch (Exception e) {
            log.warn("KnowledgeGraphInitializer: course seed failed: {}", e.getMessage());
        }
    }

    private void seedResources(org.neo4j.driver.Session session) {
        String prompt = """
            Generate learning resources for a university ML course. Include classic textbooks,
            online courses, papers, and code repositories. Also include the following as reference:
            %s

            Output a JSON array:
            [{"id":"zhou_ml","title":"周志华《机器学习》","type":"TEXTBOOK","url":"",
              "description":"经典中文教材，西瓜书","knowledgePointIds":["linear_regression","svm","decision_tree"]}]
            Types: TEXTBOOK, VIDEO, EXERCISE, CODE, PAPER.
            Output ONLY the JSON array, no markdown.
            """.formatted(loadReferenceContext());

        String response = llmProvider.chat(prompt);
        if (response == null || response.isBlank()) return;
        response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();

        try {
            if (response.startsWith("{")) {
                JsonNode root = objectMapper.readTree(response);
                if (root.has("resources")) response = objectMapper.writeValueAsString(root.get("resources"));
            }
            List<Map<String, Object>> resources = objectMapper.readValue(response,
                new TypeReference<List<Map<String, Object>>>() {});

            for (var r : resources) {
                String id = (String) r.get("id");
                String title = (String) r.getOrDefault("title", "");
                String type = (String) r.getOrDefault("type", "TEXTBOOK");
                String url = (String) r.getOrDefault("url", "");
                String desc = (String) r.getOrDefault("description", "");

                session.run("""
                    MERGE (r:LearningResource {id: $id})
                    SET r.title = $title, r.type = $type, r.url = $url, r.description = $desc
                    """,
                    Map.of("id", id, "title", title, "type", type, "url", url, "desc", desc));

                @SuppressWarnings("unchecked")
                List<String> kpIds = (List<String>) r.getOrDefault("knowledgePointIds", List.of());
                for (String kpId : kpIds) {
                    session.run("""
                        MATCH (r:LearningResource {id: $rid}), (kp:KnowledgePoint {id: $kpid})
                        MERGE (kp)-[:HAS_RESOURCE]->(r)
                        """, Map.of("rid", id, "kpid", kpId));
                }
            }
            log.info("KnowledgeGraphInitializer: created {} learning resource nodes", resources.size());
        } catch (Exception e) {
            log.warn("KnowledgeGraphInitializer: resource seed failed: {}", e.getMessage());
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

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record SeedPoint(
        String id,
        String name,
        String category,
        int difficulty,
        String description,
        List<String> prerequisites,
        List<String> relatedConcepts,
        String courseId,
        List<String> resourceIds
    ) {}
}
