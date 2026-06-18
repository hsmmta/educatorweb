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
        log.info("KgBuildAgent: starting FULL build");
        writer.clearGraph();
        List<String> topics = List.of(
            "数学基础", "监督学习", "无监督学习", "深度学习", "集成学习与优化", "应用与工具");
        int totalKps = 0, totalRels = 0;
        for (String topic : topics) {
            float[] vec = embedder.embed(topic);
            List<String> refs = store.retrieve(vec, topic, 3);
            List<Map<String, Object>> nodes = nodeBuilder.buildNodes(topic, refs);
            totalKps += writer.writeKnowledgePoints(nodes);
            totalRels += writer.linkRelationships(nodes);
        }
        // Generate Courses and LearningResources on top of KPs
        int totalCourses = seedCourses();
        int totalResources = seedResources();

        log.info("KgBuildAgent: FULL build done — {} KPs, {} rels, {} courses, {} resources",
            totalKps, totalRels, totalCourses, totalResources);
        return new BuildResult(totalKps, totalRels, 0);
    }

    private int seedCourses() {
        float[] vec = embedder.embed("机器学习课程");
        List<String> refs = store.retrieve(vec, "课程", 5);
        List<Map<String, Object>> courses = nodeBuilder.buildCourses(refs);
        int count = 0;
        try (var session = writer.newSession()) {
            for (var c : courses) {
                String id = (String) c.get("id");
                if (id == null) continue;
                session.run("""
                    MERGE (c:Course {id: $id})
                    SET c.name = $name, c.institution = $inst, c.duration = $dur,
                        c.type = $type, c.rating = $rating, c.description = $desc
                    """,
                    java.util.Map.of("id", id, "name", (String) c.getOrDefault("name", id),
                        "inst", (String) c.getOrDefault("institution", ""),
                        "dur", (String) c.getOrDefault("duration", "短期"),
                        "type", (String) c.getOrDefault("type", "理论"),
                        "rating", c.get("rating") instanceof Number n ? n.doubleValue() : 4.0,
                        "desc", (String) c.getOrDefault("description", "")));
                count++;
            }
        }
        log.info("KgBuildAgent: seeded {} courses", count);
        return count;
    }

    private int seedResources() {
        float[] vec = embedder.embed("机器学习教材");
        List<String> refs = store.retrieve(vec, "资源", 5);
        List<Map<String, Object>> resources = nodeBuilder.buildCourses(refs);
        int count = 0;
        try (var session = writer.newSession()) {
            for (var r : resources) {
                String id = (String) r.get("id");
                if (id == null) continue;
                session.run("""
                    MERGE (r:LearningResource {id: $id})
                    SET r.title = $title, r.type = $type, r.url = $url, r.description = $desc
                    """,
                    java.util.Map.of("id", id, "title", (String) r.getOrDefault("title", ""),
                        "type", (String) r.getOrDefault("type", "TEXTBOOK"),
                        "url", (String) r.getOrDefault("url", ""),
                        "desc", (String) r.getOrDefault("description", "")));
                count++;
            }
        }
        log.info("KgBuildAgent: seeded {} learning resources", count);
        return count;
    }

    public BuildResult buildIncremental() {
        long newCount = store.countByStatus("new");
        if (newCount == 0) {
            log.info("KgBuildAgent: no new chunks to process");
            return new BuildResult(0, 0, 0);
        }
        log.info("KgBuildAgent: INCREMENTAL build — {} new chunks", newCount);
        List<String> topics = List.of(
            "数学基础", "监督学习", "无监督学习", "深度学习", "集成学习与优化", "应用与工具");
        int totalKps = 0, totalRels = 0;
        for (String topic : topics) {
            float[] vec = embedder.embed(topic);
            List<String> refs = store.retrieve(vec, topic, 2);
            if (refs.isEmpty()) continue;
            List<Map<String, Object>> nodes = nodeBuilder.buildNodes(topic, refs);
            totalKps += writer.writeKnowledgePoints(nodes);
            totalRels += writer.linkRelationships(nodes);
        }
        int totalCourses = seedCourses();
        int totalResources = seedResources();
        log.info("KgBuildAgent: INCREMENTAL build done — {} KPs, {} rels, {} courses, {} resources",
            totalKps, totalRels, totalCourses, totalResources);
        return new BuildResult(totalKps, totalRels, newCount);
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
