package org.example.educatorweb.knowledgegraph.build;

import org.example.educatorweb.knowledgegraph.build.builder.KgNeo4jWriter;
import org.example.educatorweb.knowledgegraph.build.builder.KgNodeBuilder;
import org.example.educatorweb.knowledgegraph.build.config.KgBuildProperties;
import org.example.educatorweb.knowledgegraph.build.processor.KgContentProcessor;
import org.example.educatorweb.knowledgegraph.build.processor.KgReferenceStore;
import org.example.educatorweb.knowledgegraph.build.source.GitHubRepoSource;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KgBuildAgent {

    private static final Logger log = LoggerFactory.getLogger(KgBuildAgent.class);
    private final KgBuildProperties props;
    private final KgReferenceStore store;
    private final KgContentProcessor processor;
    private final KgNodeBuilder nodeBuilder;
    private final KgNeo4jWriter writer;
    private final EmbeddingService embedder;

    /** Real-world learning resources with actual URLs. */
    private static final List<Map<String, String>> STATIC_RESOURCES = List.of(
        Map.of("id","zhou_ml","title","周志华《机器学习》","type","TEXTBOOK","url","","desc","经典中文教材，西瓜书"),
        Map.of("id","li_hang_stats","title","李航《统计学习方法》","type","TEXTBOOK","url","","desc","统计ML经典，侧重理论推导"),
        Map.of("id","bishop_prml","title","Bishop PRML","type","TEXTBOOK","url","","desc","Pattern Recognition and Machine Learning"),
        Map.of("id","goodfellow_dl","title","Goodfellow《Deep Learning》","type","TEXTBOOK","url","https://www.deeplearningbook.org/","desc","深度学习圣经"),
        Map.of("id","hastie_esl","title","Hastie ESL","type","TEXTBOOK","url","https://hastie.su.domains/ElemStatLearn/","desc","统计学习理论权威教材"),
        Map.of("id","murphy_pml","title","Murphy《Probabilistic ML》","type","TEXTBOOK","url","","desc","概率机器学习，2022新版"),
        Map.of("id","ml_yearning","title","Andrew Ng《ML Yearning》","type","TEXTBOOK","url","https://www.deeplearning.ai/machine-learning-yearning/","desc","ML项目实战方法论"),
        Map.of("id","ng_coursera","title","吴恩达 Coursera ML","type","VIDEO","url","https://www.coursera.org/learn/machine-learning","desc","全球最受欢迎ML入门课"),
        Map.of("id","dl_specialization","title","DeepLearning.AI 专项课程","type","VIDEO","url","https://www.deeplearning.ai/courses/","desc","5门课DL系列"),
        Map.of("id","cs229","title","斯坦福CS229","type","VIDEO","url","https://cs229.stanford.edu/","desc","Andrew Ng ML课程讲义"),
        Map.of("id","cs231n","title","斯坦福CS231n","type","VIDEO","url","https://cs231n.github.io/","desc","DL与计算机视觉"),
        Map.of("id","d2l","title","李沐《动手学深度学习》","type","VIDEO","url","https://d2l.ai/","desc","d2l.ai免费在线"),
        Map.of("id","sklearn","title","scikit-learn","type","CODE","url","https://scikit-learn.org/stable/","desc","Python ML标准库"),
        Map.of("id","tensorflow","title","TensorFlow","type","CODE","url","https://www.tensorflow.org/","desc","DL框架"),
        Map.of("id","pytorch","title","PyTorch","type","CODE","url","https://pytorch.org/","desc","DL框架"),
        Map.of("id","ml_from_scratch","title","ML-From-Scratch","type","CODE","url","https://github.com/eriklindernoren/ML-From-Scratch","desc","20+算法纯Python实现"),
        Map.of("id","alexnet","title","AlexNet论文","type","PAPER","url","","desc","ImageNet Classification with Deep CNNs (2012)"),
        Map.of("id","transformer","title","Transformer论文","type","PAPER","url","https://arxiv.org/abs/1706.03762","desc","Attention Is All You Need (2017)"),
        Map.of("id","resnet","title","ResNet论文","type","PAPER","url","https://arxiv.org/abs/1512.03385","desc","Deep Residual Learning (2016)"),
        Map.of("id","gan_paper","title","GAN论文","type","PAPER","url","https://arxiv.org/abs/1406.2661","desc","Generative Adversarial Nets (2014)")
    );

    public KgBuildAgent(KgBuildProperties props, KgReferenceStore store,
                        KgContentProcessor processor, KgNodeBuilder nodeBuilder,
                        KgNeo4jWriter writer, EmbeddingService embedder) {
        this.props = props;
        this.store = store;
        this.processor = processor;
        this.nodeBuilder = nodeBuilder;
        this.writer = writer;
        this.embedder = embedder;
    }

    public int syncSources() {
        int totalChunks = 0;
        for (var srcCfg : props.getSources().getGithub()) {
            GitHubRepoSource src = new GitHubRepoSource(srcCfg);
            List<DocumentChunk> chunks = src.fetch();
            if (chunks.isEmpty()) continue;
            List<float[]> embeddings = processor.embedChunks(chunks);
            int stored = store.store(chunks, embeddings);
            totalChunks += stored;
            log.info("KgBuildAgent: synced source '{}' — {} chunks", src.name(), stored);
        }
        return totalChunks;
    }

    public BuildResult buildFull() {
        log.info("KgBuildAgent: starting FULL build (v2 4-phase)");
        writer.clearGraph();

        // Phase 1: Courses — seed from topic list, no LLM
        int totalCourses = seedCoursesFromTopics();

        // Phase 2: Resources — hardcoded static list, no LLM
        int totalResources = seedResourcesFromStatic();

        // Phase 3: KnowledgePoints — LLM per-course with dedup
        int totalKps = 0, totalRels = 0;
        Set<String> knownPointIds = new HashSet<>();
        List<Map<String, String>> courses = listCourseIds();
        for (var course : courses) {
            String cid = course.get("id");
            String cname = course.get("name");
            float[] vec = embedder.embed(cname);
            List<String> refs = store.retrieve(vec, cname, 3);
            List<Map<String, Object>> nodes = nodeBuilder.buildNodesForCourse(
                cname, cid, knownPointIds, refs);

            for (var n : nodes) {
                boolean existing = Boolean.TRUE.equals(n.get("existing"));
                String kpId = (String) n.get("id");
                if (kpId == null || kpId.isBlank()) continue;

                if (!existing) {
                    writer.writeKnowledgePoints(List.of(n));
                    knownPointIds.add(kpId);
                    totalKps++;
                }
                // BELONGS_TO: knowledge point → course
                try (var s = writer.newSession()) {
                    s.run("MATCH (kp:KnowledgePoint {id:$kpid}), (c:Course {id:$cid}) MERGE (kp)-[:BELONGS_TO]->(c)",
                        Map.of("kpid", kpId, "cid", cid));
                }
                // Link REQUIRES + RELATED_TO (only new points)
                if (!existing) {
                    totalRels += writer.linkRelationships(List.of(n));
                }
            }
            log.info("KgBuildAgent: course '{}' — {} points (known total: {})",
                cname, nodes.size(), knownPointIds.size());
        }

        // Phase 4: Build CONTAINS (Course→KP) + HAS_RESOURCE (KP→Resource) edges
        int containsEdges = linkCoursesToKps();
        int resourceEdges = linkKpsToResources();
        totalRels += containsEdges + resourceEdges;

        log.info("KgBuildAgent: FULL build done — {} KPs, {} rels, {} courses, {} resources (CONTAINS={}, HAS_RESOURCE={})",
            totalKps, totalRels, totalCourses, totalResources, containsEdges, resourceEdges);
        return new BuildResult(totalKps, totalRels, 0);
    }

    // ---- Phase 4 helpers ----

    /** Build CONTAINS edges: Course → KnowledgePoint (reverse of BELONGS_TO). */
    private int linkCoursesToKps() {
        int count = 0;
        try (var s = writer.newSession()) {
            var r = s.run("MATCH (kp:KnowledgePoint)-[:BELONGS_TO]->(c:Course) " +
                          "WHERE NOT (c)-[:CONTAINS]->(kp) RETURN kp.id AS kpid, c.id AS cid");
            while (r.hasNext()) {
                var rec = r.next();
                s.run("MATCH (kp:KnowledgePoint {id:$kpid}), (c:Course {id:$cid}) " +
                      "MERGE (c)-[:CONTAINS]->(kp)",
                    Map.of("kpid", rec.get("kpid").asString(), "cid", rec.get("cid").asString()));
                count++;
            }
        } catch (Exception e) {
            log.warn("KgBuildAgent: CONTAINS link failed: {}", e.getMessage());
        }
        log.info("KgBuildAgent: created {} CONTAINS edges", count);
        return count;
    }

    /** Build HAS_RESOURCE edges: KnowledgePoint → LearningResource by keyword + category match. */
    private int linkKpsToResources() {
        int count = 0;
        // Category → resource ID prefix hints
        Map<String, String> catToResource = Map.of(
            "数学基础", "bishop_prml",
            "概念", "ng_coursera",
            "算法", "sklearn",
            "应用", "cs229",
            "工具", "pytorch"
        );
        try (var s = writer.newSession()) {
            // Get all KPs with their categories
            var kps = s.run("MATCH (kp:KnowledgePoint) " +
                "WHERE NOT (kp)-[:HAS_RESOURCE]->(:LearningResource) " +
                "RETURN kp.id AS id, kp.name AS name, kp.category AS cat");
            List<Map<String, String>> kpList = new ArrayList<>();
            while (kps.hasNext()) {
                var rec = kps.next();
                kpList.add(Map.of("id", rec.get("id").asString(),
                    "name", rec.get("name").asString(),
                    "cat", rec.get("cat").asString("概念")));
            }

            // Get all resource IDs
            var res = s.run("MATCH (r:LearningResource) RETURN r.id AS id, r.title AS title, r.type AS type, r.description AS desc");
            List<Map<String, String>> resList = new ArrayList<>();
            while (res.hasNext()) {
                var rec = res.next();
                resList.add(Map.of("id", rec.get("id").asString(),
                    "title", rec.get("title").asString(""),
                    "type", rec.get("type").asString(""),
                    "desc", rec.get("desc").asString("")));
            }

            // Match KPs to resources
            for (var kp : kpList) {
                String bestRid = null;
                // Strategy 1: keyword overlap in resource title/desc
                String kpLower = kp.get("name").toLowerCase();
                int bestScore = 0;
                for (var resource : resList) {
                    int score = 0;
                    String titleLower = resource.get("title").toLowerCase();
                    String descLower = resource.get("desc").toLowerCase();
                    // Split KP name into words and count matches
                    for (String word : kpLower.split("[\\s\\-_]+")) {
                        if (word.length() < 2) continue;
                        if (titleLower.contains(word)) score += 3;
                        if (descLower.contains(word)) score += 1;
                    }
                    // Category bonus
                    String expectedRid = catToResource.get(kp.get("cat"));
                    if (resource.get("id").equals(expectedRid)) score += 2;

                    if (score > bestScore) {
                        bestScore = score;
                        bestRid = resource.get("id");
                    }
                }
                // Strategy 2: fallback — category-based default
                if ((bestRid == null || bestScore < 2) && catToResource.containsKey(kp.get("cat"))) {
                    bestRid = catToResource.get(kp.get("cat"));
                }
                // Strategy 3: absolute fallback
                if (bestRid == null) bestRid = "ng_coursera";

                s.run("MATCH (kp:KnowledgePoint {id:$kpid}), (r:LearningResource {id:$rid}) " +
                      "MERGE (kp)-[:HAS_RESOURCE]->(r) " +
                      "SET r.updatedAt = timestamp()",
                    Map.of("kpid", kp.get("id"), "rid", bestRid));
                count++;
            }
        } catch (Exception e) {
            log.warn("KgBuildAgent: HAS_RESOURCE link failed: {}", e.getMessage());
        }
        log.info("KgBuildAgent: created {} HAS_RESOURCE edges", count);
        return count;
    }

    /** Phase 1: Seed course nodes from a topic list. */
    private int seedCoursesFromTopics() {
        int count = 0;
        List<String> courseNames = List.of(
            "机器学习简介", "回归模型", "分类技术", "聚类", "NLP基础",
            "时间序列", "强化学习", "深度学习基础", "模型评估与优化",
            "监督学习算法", "无监督学习", "集成学习", "特征工程",
            "推荐系统", "计算机视觉", "生成对抗网络", "Transformer与注意力机制",
            "AutoML", "ML系统工程", "贝叶斯方法", "图神经网络", "联邦学习"
        );
        try (var s = writer.newSession()) {
            for (String name : courseNames) {
                String id = "course_" + name.toLowerCase()
                    .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_").replaceAll("_$", "");
                s.run("""
                    MERGE (c:Course {id: $id})
                    SET c.name = $name, c.institution = 'ML Curriculum',
                        c.duration = '中期', c.type = '理论', c.rating = 4.5,
                        c.description = $desc
                    """, Map.of("id", id, "name", name, "desc", name + " 模块课程"));
                count++;
            }
        }
        log.info("KgBuildAgent: seeded {} courses", count);
        return count;
    }

    /** Phase 2: Insert static resource list directly, no LLM. */
    private int seedResourcesFromStatic() {
        int count = 0;
        try (var s = writer.newSession()) {
            for (var r : STATIC_RESOURCES) {
                s.run("""
                    MERGE (r:LearningResource {id: $id})
                    SET r.title = $title, r.type = $type, r.url = $url, r.description = $desc
                    """, Map.of("id", r.get("id"), "title", r.get("title"),
                        "type", r.get("type"), "url", r.get("url"), "desc", r.get("desc")));
                count++;
            }
        }
        log.info("KgBuildAgent: seeded {} static resources", count);
        return count;
    }

    /** Get all course IDs and names from Neo4j for Phase 3 iteration. */
    private List<Map<String, String>> listCourseIds() {
        try (var s = writer.newSession()) {
            var result = s.run("MATCH (c:Course) RETURN c.id AS id, c.name AS name");
            List<Map<String, String>> list = new ArrayList<>();
            while (result.hasNext()) {
                var record = result.next();
                list.add(Map.of("id", record.get("id").asString(), "name", record.get("name").asString()));
            }
            return list;
        } catch (Exception e) {
            log.warn("KgBuildAgent: list courses failed: {}", e.getMessage());
            return List.of();
        }
    }

    public BuildResult buildIncremental() {
        long newCount = store.countByStatus("new");
        if (newCount == 0) {
            log.info("KgBuildAgent: no new chunks to process");
            return new BuildResult(0, 0, 0);
        }
        log.info("KgBuildAgent: INCREMENTAL build — {} new chunks", newCount);
        // Re-run full for now (incremental optimization later)
        return buildFull();
    }

    public Map<String, Object> getStatus() {
        return Map.of(
            "knowledgePointCount", writer.countKnowledgePoints(),
            "newChunks", store.countByStatus("new"),
            "processedChunks", store.countByStatus("processed")
        );
    }

    /**
     * Link existing nodes only — builds CONTAINS (Course→KP) + HAS_RESOURCE (KP→Resource)
     * WITHOUT clearing the graph. Safe to call after external data imports.
     */
    public BuildResult linkExistingNodes() {
        log.info("KgBuildAgent: linking existing nodes (no-clear)");
        int containsEdges = linkCoursesToKps();
        int resourceEdges = linkKpsToResources();
        int relatedEdges = linkRelatedToFromParentSon();
        log.info("KgBuildAgent: link done — CONTAINS={}, HAS_RESOURCE={}, RELATED_TO={}",
            containsEdges, resourceEdges, relatedEdges);
        return new BuildResult(0, containsEdges + resourceEdges + relatedEdges, 0);
    }

    /** Build RELATED_TO edges from parent-son hierarchy (MOOCCube parent-son.json).
     *  Uses name-based matching since MOOCCube IDs differ from our slug IDs. */
    private int linkRelatedToFromParentSon() {
        java.nio.file.Path base = java.nio.file.Path.of("dev-docs/MOOCCube");
        java.nio.file.Path conceptFile = base.resolve("entities/concept.json");
        java.nio.file.Path parentSonFile = base.resolve("relations/parent-son.json");
        if (!java.nio.file.Files.exists(conceptFile)
            || !java.nio.file.Files.exists(parentSonFile)) {
            log.info("KgBuildAgent: MOOCCube files not found, skipping RELATED_TO import");
            return 0;
        }

        try {
            // Step 1: Build MOOCCube ID → name map from concept.json
            var idToName = new java.util.HashMap<String, String>();
            var nameToSlug = new java.util.HashMap<String, String>();
            try (var reader = java.nio.file.Files.newBufferedReader(conceptFile)) {
                String line;
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        @SuppressWarnings("unchecked")
                        var obj = mapper.readValue(line, java.util.Map.class);
                        String mid = (String) obj.get("id");
                        String mname = (String) obj.get("name");
                        if (mid != null && mname != null) idToName.put(mid, mname);
                    } catch (Exception ignored) {}
                }
            }

            // Step 2: Build Neo4j name → slug map
            try (var s = writer.newSession()) {
                var r = s.run("MATCH (kp:KnowledgePoint) RETURN kp.id AS id, kp.name AS name");
                while (r.hasNext()) {
                    var rec = r.next();
                    nameToSlug.put(rec.get("name").asString(""),
                                   rec.get("id").asString(""));
                }
            }

            // Step 3: Read parent-son.json and create RELATED_TO edges by name
            int count = 0;
            try (var s = writer.newSession();
                 var reader = java.nio.file.Files.newBufferedReader(parentSonFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t");
                    if (parts.length < 2) continue;
                    String childName = idToName.get(parts[0].trim());
                    String parentName = idToName.get(parts[1].trim());
                    if (childName == null || parentName == null) continue;
                    String childSlug = nameToSlug.get(childName);
                    String parentSlug = nameToSlug.get(parentName);
                    if (childSlug == null || parentSlug == null) continue;
                    if (childSlug.equals(parentSlug)) continue;

                    s.run("MATCH (a:KnowledgePoint {id:$child}), (b:KnowledgePoint {id:$parent}) " +
                          "MERGE (a)-[:RELATED_TO]->(b)",
                        java.util.Map.of("child", childSlug, "parent", parentSlug));
                    count++;
                }
            }
            log.info("KgBuildAgent: created {} RELATED_TO edges from parent-son.json", count);
            return count;
        } catch (Exception e) {
            log.warn("KgBuildAgent: RELATED_TO import failed: {}", e.getMessage());
            return 0;
        }
    }

    public record BuildResult(int knowledgePoints, int relationships, long newChunks) {}
}
