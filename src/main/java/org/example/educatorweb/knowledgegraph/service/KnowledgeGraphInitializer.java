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

    // @EventListener(ApplicationReadyEvent.class) — deprecated, replaced by KgBuildAgent
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
            ## REAL COURSES (use these exact names for credibility)
            - 斯坦福大学 CS229: Machine Learning (Andrew Ng) — 理论为主, 长期课程
            - 斯坦福大学 CS231n: Convolutional Neural Networks for Visual Recognition (Fei-Fei Li)
            - 麻省理工学院 6.036: Introduction to Machine Learning — 入门, 中期课程
            - DeepLearning.AI 深度学习专项课程 (Andrew Ng, Coursera) — 5门课系列
            - 台灣大學 機器學習 (李宏毅) — 中文授课, 长期课程
            - 南京大学 机器学习 (周志华) — 中文授课, 理论为主
            - microsoft/ML-For-Beginners (GitHub 12周课程) — 入门, 含代码练习
            - fast.ai Practical Deep Learning (Jeremy Howard) — 实践为主
            - 中国大学MOOC: 机器学习 (浙江大学) — 中文, 入门
            - 中国大学MOOC: 机器学习与模式识别 (中科大) — 中文, 中期

            ## REAL TEXTBOOKS (use these exact titles)
            - 周志华《机器学习》(清华大学出版社, 2016) — 经典中文教材, "西瓜书"
            - 李航《统计学习方法》(清华大学出版社, 第2版) — 侧重理论推导
            - Christopher Bishop "Pattern Recognition and Machine Learning" (Springer, 2006)
            - Trevor Hastie "The Elements of Statistical Learning" (Springer, 2nd ed)
            - Ian Goodfellow "Deep Learning" (MIT Press, 2016) — 深度学习圣经
            - Kevin Murphy "Probabilistic Machine Learning" (MIT Press, 2022)
            - Andrew Ng "Machine Learning Yearning" — 实战方法论
            - Tom Mitchell "Machine Learning" (McGraw-Hill, 1997) — 经典入门

            ## REAL CODE REPOSITORIES (use exact URLs)
            - eriklindernoren/ML-From-Scratch (31.9k stars) — 20+ algorithms in pure Python
            - scikit-learn/scikit-learn (66.3k stars) — standard ML library
            - tensorflow/tensorflow (195k stars) — deep learning framework
            - trekhleb/homemade-machine-learning (24.6k stars) — Jupyter demos
            - ashishpatel26/500-AI-ML-projects (34.5k stars) — project collection

            ## REAL VIDEO COURSES
            - 吴恩达 Machine Learning (Coursera, 2011原版) — 全球最受欢迎的ML入门课
            - 吴恩达 Machine Learning Specialization (Coursera, 2022新版)
            - DeepLearning.AI Specialization (Coursera, 5 courses)
            - 李沐《动手学深度学习》(d2l.ai, 免费在线)
            - 3Blue1Brown Neural Networks (YouTube) — 可视化理解

            ## REAL PAPERS (landmark papers for reference)
            - "ImageNet Classification with Deep CNNs" (Krizhevsky et al., 2012)
            - "Attention Is All You Need" (Vaswani et al., 2017) — Transformer
            - "Deep Residual Learning for Image Recognition" (He et al., 2016) — ResNet
            - "Generative Adversarial Nets" (Goodfellow et al., 2014) — GAN
            - "BERT" (Devlin et al., 2019) — NLP breakthrough
            - "Playing Atari with Deep RL" (Mnih et al., 2013) — DQN
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
