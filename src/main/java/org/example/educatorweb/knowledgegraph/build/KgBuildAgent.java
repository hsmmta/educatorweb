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

        log.info("KgBuildAgent: FULL build done — {} KPs, {} rels, {} courses, {} resources",
            totalKps, totalRels, totalCourses, totalResources);
        return new BuildResult(totalKps, totalRels, 0);
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

    public record BuildResult(int knowledgePoints, int relationships, long newChunks) {}
}
