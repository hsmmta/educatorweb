# KgBuildAgent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Autonomous KG build agent that fetches GitHub repos → chunks text → embeds to Qdrant → retrieves relevant context → uses LLM to generate KG nodes → writes to Neo4j via Cypher. Supports full/incremental builds and scheduled auto-updates.

**Architecture:** Modular pipeline: `KgSource` interface → `GitHubRepoSource` (Phase 1) → `KgContentProcessor` (reuse EmbeddingService) → `KgReferenceStore` (Qdrant "kg_references" collection) → `KgNodeBuilder` (DeepSeek prompt + parse) → `KgNeo4jWriter` (raw Cypher). Orchestrated by `KgBuildAgent`, exposed via `KgBuildController` REST API + `KgBuildScheduler` cron.

**Tech Stack:** Spring Boot 3.4.3, Qdrant Java Client 1.12.0, Neo4j Driver (raw Cypher), DeepSeek LLM, `EmbeddingService` (reuse), `java.lang.Process` (git clone/pull)

---

## File Map

| File | Role |
|------|------|
| `knowledgegraph/build/config/KgBuildProperties.java` | YAML config binding |
| `knowledgegraph/build/source/KgSource.java` | Data source interface |
| `knowledgegraph/build/source/GitHubRepoSource.java` | GitHub repo parser |
| `knowledgegraph/build/processor/KgContentProcessor.java` | Text→chunk→embed |
| `knowledgegraph/build/processor/KgReferenceStore.java` | Qdrant kg_references mgmt |
| `knowledgegraph/build/builder/KgNodeBuilder.java` | LLM prompt + generation |
| `knowledgegraph/build/builder/KgNeo4jWriter.java` | Cypher writes |
| `knowledgegraph/build/KgBuildAgent.java` | Orchestrator |
| `knowledgegraph/build/KgBuildScheduler.java` | Scheduled task |
| `knowledgegraph/build/api/KgBuildController.java` | REST API |
| `src/main/resources/application.yml` | Add `kg:` config section |
| `dev-docs/kg-sources/` | Git clone target directory |

---

### Task 1: Configuration & Properties

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/config/KgBuildProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Create property binding class**

```java
package org.example.educatorweb.knowledgegraph.build.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "kg")
public class KgBuildProperties {

    private Sources sources = new Sources();
    private Build build = new Build();

    public Sources getSources() { return sources; }
    public void setSources(Sources sources) { this.sources = sources; }
    public Build getBuild() { return build; }
    public void setBuild(Build build) { this.build = build; }

    public static class Sources {
        private List<GitHubSource> github = List.of();
        private WebApi webApi = new WebApi();

        public List<GitHubSource> getGithub() { return github; }
        public void setGithub(List<GitHubSource> github) { this.github = github; }
        public WebApi getWebApi() { return webApi; }
        public void setWebApi(WebApi webApi) { this.webApi = webApi; }
    }

    public static class GitHubSource {
        private String url;
        private String name;
        private String type = "course";  // course | code-repo | awesome-list

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    public static class WebApi {
        private boolean enabled = false;
        private String provider = "tavily";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }

    public static class Build {
        private String schedule = "0 0 3 * * SUN";
        private int batchSize = 5;

        public String getSchedule() { return schedule; }
        public void setSchedule(String schedule) { this.schedule = schedule; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }
}
```

- [ ] **Step 2: Add YAML config to application.yml**

Add to `src/main/resources/application.yml` (after the `qdrant:` section):

```yaml
# --- Knowledge Graph Build Agent ---
kg:
  sources:
    github:
      - url: https://github.com/microsoft/ML-For-Beginners
        name: ml-for-beginners
        type: course
      - url: https://github.com/eriklindernoren/ML-From-Scratch
        name: ml-from-scratch
        type: code-repo
    web-api:
      enabled: false
      provider: tavily
  build:
    schedule: "0 0 3 * * SUN"
    batch-size: 5
```

- [ ] **Step 3: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/config/KgBuildProperties.java src/main/resources/application.yml
git commit -m "feat(kg-build): add KgBuildProperties and YAML config for KG build agent"
```

---

### Task 2: KgSource Interface & GitHubRepoSource

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/source/KgSource.java`
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/source/GitHubRepoSource.java`

- [ ] **Step 1: Create KgSource interface**

```java
package org.example.educatorweb.knowledgegraph.build.source;

import org.example.educatorweb.rag.model.DocumentChunk;
import java.util.List;

public interface KgSource {
    /** Human-readable source name. */
    String name();
    /** Type: course, code-repo, awesome-list. Used to pick parser strategy. */
    String type();
    /** Fetch all chunks from this source. */
    List<DocumentChunk> fetch();
}
```

- [ ] **Step 2: Create GitHubRepoSource**

```java
package org.example.educatorweb.knowledgegraph.build.source;

import org.example.educatorweb.knowledgegraph.build.config.KgBuildProperties.GitHubSource;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Clones/pulls a GitHub repo, then parses its directory structure and README files
 * into DocumentChunks. Each chunk represents one lesson/module/concept.
 */
public class GitHubRepoSource implements KgSource {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepoSource.class);
    private static final String BASE_DIR = "dev-docs/kg-sources/";

    private final String url;
    private final String name;
    private final String type;

    public GitHubRepoSource(GitHubSource config) {
        this.url = config.getUrl();
        this.name = config.getName();
        this.type = config.getType();
    }

    @Override public String name() { return name; }
    @Override public String type() { return type; }

    @Override
    public List<DocumentChunk> fetch() {
        File repoDir = syncRepo();
        if (repoDir == null || !repoDir.exists()) return List.of();
        return parseRepo(repoDir);
    }

    /** git clone or git pull the repo to local. */
    private File syncRepo() {
        File repoDir = new File(BASE_DIR + name);
        try {
            ProcessBuilder pb;
            if (repoDir.exists() && new File(repoDir, ".git").exists()) {
                log.info("GitHubRepoSource: pulling {}", name);
                pb = new ProcessBuilder("git", "pull", "origin");
                pb.directory(repoDir);
            } else {
                log.info("GitHubRepoSource: cloning {} → {}", url, repoDir);
                repoDir.getParentFile().mkdirs();
                pb = new ProcessBuilder("git", "clone", "--depth", "1", url, repoDir.getAbsolutePath());
            }
            var proc = pb.start();
            proc.waitFor(60, TimeUnit.SECONDS);
            return repoDir;
        } catch (Exception e) {
            log.error("GitHubRepoSource: git operation failed for {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** Parse repo into chunks: one chunk per sub-directory containing a README. */
    private List<DocumentChunk> parseRepo(File repoDir) {
        List<DocumentChunk> chunks = new ArrayList<>();
        File[] entries = repoDir.listFiles(File::isDirectory);
        if (entries == null) return chunks;

        for (File entry : entries) {
            String dirName = entry.getName();
            // Skip non-course directories (.git, .github, images, etc.)
            if (dirName.startsWith(".") || dirName.equals("images") || dirName.equals("docs")
                || dirName.equals("translations") || dirName.equals("pdf")
                || dirName.equals("sketchnotes") || dirName.equals("quiz-app"))
                continue;

            // Parse sub-lessons
            File[] lessons = entry.listFiles(File::isDirectory);
            if (lessons != null) {
                for (File lesson : lessons) {
                    File readme = new File(lesson, "README.md");
                    if (readme.exists()) {
                        String text = readText(readme);
                        if (!text.isBlank()) {
                            chunks.add(DocumentChunk.of(
                                name + "/" + lesson.getName(),
                                name,
                                dirName + " → " + lesson.getName(),
                                text.substring(0, Math.min(text.length(), 500)),
                                extractTopic(dirName),
                                0));
                        }
                    }
                }
            }

            // Also add parent README
            File parentReadme = new File(entry, "README.md");
            if (parentReadme.exists()) {
                String text = readText(parentReadme);
                if (!text.isBlank()) {
                    chunks.add(DocumentChunk.of(
                        name + "/" + dirName + "/overview",
                        name,
                        dirName + " overview",
                        text.substring(0, Math.min(text.length(), 500)),
                        extractTopic(dirName),
                        0));
                }
            }
        }
        log.info("GitHubRepoSource: extracted {} chunks from {}", chunks.size(), name);
        return chunks;
    }

    private String extractTopic(String dirName) {
        // "1-Introduction" → "Introduction"
        // "2-Regression" → "Regression"
        // "supervised_learning" → "Supervised Learning"
        return dirName.replaceAll("^\\d+-", "")
            .replace("_", " ")
            .replace("-", " ")
            .toLowerCase();
    }

    private String readText(File file) {
        try { return Files.readString(file.toPath()); }
        catch (IOException e) { log.warn("Cannot read {}: {}", file, e.getMessage()); return ""; }
    }
}
```

- [ ] **Step 3: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/source/
git commit -m "feat(kg-build): add KgSource interface and GitHubRepoSource parser"
```

---

### Task 3: KgReferenceStore (Qdrant management)

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/processor/KgReferenceStore.java`

- [ ] **Step 1: Create Qdrant reference store for kg_references collection**

```java
package org.example.educatorweb.knowledgegraph.build.processor;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.QueryFactory.nearest;

/**
 * Manages the "kg_references" Qdrant collection.
 * Handles chunk storage, retrieval, and status tracking.
 */
public class KgReferenceStore {

    private static final Logger log = LoggerFactory.getLogger(KgReferenceStore.class);
    static final String COLLECTION_NAME = "kg_references";
    private static final int VECTOR_DIM = 1024;

    private final QdrantClient qdrantClient;
    private volatile boolean initialized;

    public KgReferenceStore(QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    /** Ensure collection exists. */
    public boolean ensureCollection() {
        if (initialized) return true;
        try {
            if (!qdrantClient.collectionExistsAsync(COLLECTION_NAME).get()) {
                qdrantClient.createCollectionAsync(Collections.CreateCollection.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                        .setParams(Collections.VectorParams.newBuilder()
                            .setSize(VECTOR_DIM).setDistance(Collections.Distance.Cosine)
                            .build())
                        .build())
                    .build()).get();
                log.info("KgReferenceStore: created collection '{}'", COLLECTION_NAME);
            }
            initialized = true;
            return true;
        } catch (Exception e) {
            log.error("KgReferenceStore: cannot init: {}", e.getMessage());
            return false;
        }
    }

    /** Store chunks with precomputed embeddings. */
    public int store(List<DocumentChunk> chunks, List<float[]> embeddings) {
        if (!ensureCollection() || chunks.isEmpty()) return 0;
        try {
            List<Points.PointStruct> points = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk c = chunks.get(i);
                float[] vec = i < embeddings.size() ? embeddings.get(i) : new float[0];
                if (vec.length == 0) continue;

                List<Float> fltList = new ArrayList<>(vec.length);
                for (float f : vec) fltList.add(f);

                points.add(Points.PointStruct.newBuilder()
                    .setId(Points.PointId.newBuilder().setUuid(c.id().toString()).build())
                    .setVectors(Points.Vectors.newBuilder()
                        .setVector(Points.Vector.newBuilder().addAllData(fltList).build())
                        .build())
                    .putPayload("docId", JsonWithInt.Value.newBuilder().setStringValue(c.docId()).build())
                    .putPayload("source", JsonWithInt.Value.newBuilder().setStringValue(c.source()).build())
                    .putPayload("title", JsonWithInt.Value.newBuilder().setStringValue(c.title()).build())
                    .putPayload("text", JsonWithInt.Value.newBuilder().setStringValue(c.text()).build())
                    .putPayload("topic", JsonWithInt.Value.newBuilder().setStringValue(c.knowledgePoint()).build())
                    .putPayload("status", JsonWithInt.Value.newBuilder().setStringValue("new").build())
                    .build());
            }
            qdrantClient.upsertAsync(Points.UpsertPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME).addAllPoints(points).build()).get();
            log.info("KgReferenceStore: stored {} chunks", points.size());
            return points.size();
        } catch (Exception e) {
            log.error("KgReferenceStore: store failed: {}", e.getMessage());
            return 0;
        }
    }

    /** Retrieve chunks similar to the given vector, filtered by topic. */
    public List<String> retrieve(float[] queryVector, String topic, int topK) {
        if (!ensureCollection()) return List.of();
        try {
            var scored = qdrantClient.queryAsync(Points.QueryPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .setLimit(topK)
                .setQuery(nearest(queryVector))
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                .build()).get();

            List<String> texts = new ArrayList<>();
            for (var p : scored) {
                var textVal = p.getPayloadMap().getOrDefault("text",
                    JsonWithInt.Value.newBuilder().setStringValue("").build());
                String text = textVal.getStringValue();
                if (!text.isBlank()) texts.add(text);
            }
            return texts;
        } catch (Exception e) {
            log.error("KgReferenceStore: retrieve failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Mark chunks as processed to skip on incremental builds. */
    public void markProcessed(List<String> docIds) {
        if (!ensureCollection() || docIds.isEmpty()) return;
        try {
            for (String docId : docIds) {
                qdrantClient.setPayloadAsync(
                    COLLECTION_NAME,
                    java.util.Map.of("status",
                        JsonWithInt.Value.newBuilder().setStringValue("processed").build()),
                    io.qdrant.client.ConditionFactory.matchKeyword("docId", docId)
                ).get();
            }
        } catch (Exception e) {
            log.error("KgReferenceStore: mark processed failed: {}", e.getMessage());
        }
    }

    /** Count chunks with given status. */
    public long countByStatus(String status) {
        try {
            var result = qdrantClient.countAsync(COLLECTION_NAME,
                io.qdrant.client.ConditionFactory.matchKeyword("status", status)).get();
            return result.getCount();
        } catch (Exception e) { return 0; }
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/processor/KgReferenceStore.java
git commit -m "feat(kg-build): add KgReferenceStore for Qdrant kg_references collection"
```

---

### Task 4: KgContentProcessor (text→chunk→embed pipeline)

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/processor/KgContentProcessor.java`

- [ ] **Step 1: Create content processing pipeline**

```java
package org.example.educatorweb.knowledgegraph.build.processor;

import org.example.educatorweb.rag.model.DocumentChunk;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Text extraction → chunking → embedding.
 * Reuses existing EmbeddingService for vectorization.
 */
public class KgContentProcessor {

    private static final Logger log = LoggerFactory.getLogger(KgContentProcessor.class);
    private static final int CHUNK_SIZE = 500;

    private final EmbeddingService embeddingService;

    public KgContentProcessor(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * Given a list of chunks, generate embeddings and return them.
     */
    public List<float[]> embedChunks(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return List.of();
        List<String> texts = chunks.stream().map(DocumentChunk::text).toList();
        List<float[]> embeddings = embeddingService.embedBatch(texts);
        log.debug("KgContentProcessor: embedded {} chunks", embeddings.size());
        return embeddings;
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/processor/KgContentProcessor.java
git commit -m "feat(kg-build): add KgContentProcessor for chunk embedding pipeline"
```

---

### Task 5: KgNodeBuilder (LLM prompt + generation)

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/builder/KgNodeBuilder.java`

- [ ] **Step 1: Create LLM-based KG node builder**

```java
package org.example.educatorweb.knowledgegraph.build.builder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Builds KG node JSON from topic + reference context via DeepSeek LLM.
 */
public class KgNodeBuilder {

    private static final Logger log = LoggerFactory.getLogger(KgNodeBuilder.class);

    private final ModelProvider llmProvider;
    private final ObjectMapper objectMapper;

    public KgNodeBuilder(ModelProvider llmProvider) {
        this.llmProvider = llmProvider;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate 1-3 KnowledgePoint nodes for a topic, using reference context.
     * Returns a list of node maps ready for Cypher creation.
     */
    public List<Map<String, Object>> buildNodes(String topic, List<String> refTexts) {
        String refContext = String.join("\n---\n", refTexts);
        String prompt = """
            You are a ML curriculum expert. Generate 1-3 knowledge points for: %s

            Reference context:
            %s

            Output ONLY a JSON array:
            [{"id":"slug","name":"中文名","category":"算法|数学基础|概念|应用|工具",
              "difficulty":3,"description":"一句话","prerequisites":["id1"],
              "relatedConcepts":["id2"]}]
            Output ONLY the array, no markdown.
            """.formatted(topic, refContext.length() > 2000 ? refContext.substring(0, 2000) : refContext);

        String response = llmProvider.chat(prompt);
        if (response == null || response.isBlank()) return List.of();

        response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();
        try {
            if (response.startsWith("{")) {
                var root = objectMapper.readTree(response);
                if (root.isObject() && root.has("knowledgePoints"))
                    response = objectMapper.writeValueAsString(root.get("knowledgePoints"));
            }
            return objectMapper.readValue(response, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("KgNodeBuilder: parse failed for {}: {}", topic, e.getMessage());
            return List.of();
        }
    }

    /**
     * Generate Course nodes.
     */
    public List<Map<String, Object>> buildCourses(List<String> refTexts) {
        String refContext = String.join("\n---\n", refTexts);

        String prompt = """
            Generate 2-4 ML courses based on these references. Include real institution names.

            References:
            %s

            Output ONLY a JSON array:
            [{"id":"slug","name":"机器学习_浙江大学","institution":"浙江大学",
              "duration":"短期|中期|长期","type":"理论|实践","rating":4.5,
              "description":"一句话","knowledgePointIds":["kp1","kp2"],
              "prerequisiteCourseIds":[]}]
            """.formatted(refContext.length() > 2000 ? refContext.substring(0, 2000) : refContext);

        String response = llmProvider.chat(prompt);
        if (response == null || response.isBlank()) return List.of();
        response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();
        try {
            if (response.startsWith("{")) {
                var root = objectMapper.readTree(response);
                if (root.has("courses"))
                    response = objectMapper.writeValueAsString(root.get("courses"));
            }
            return objectMapper.readValue(response, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("KgNodeBuilder: course parse failed: {}", e.getMessage());
            return List.of();
        }
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/builder/KgNodeBuilder.java
git commit -m "feat(kg-build): add KgNodeBuilder for LLM-based KG node generation"
```

---

### Task 6: KgNeo4jWriter (Cypher writes)

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/builder/KgNeo4jWriter.java`

- [ ] **Step 1: Create Neo4j writer using raw Cypher**

```java
package org.example.educatorweb.knowledgegraph.build.builder;

import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Writes KnowledgePoint, Course, and LearningResource nodes to Neo4j via raw Cypher.
 * Bypasses Spring Data Neo4j transaction template (null in WebFlux).
 */
public class KgNeo4jWriter {

    private static final Logger log = LoggerFactory.getLogger(KgNeo4jWriter.class);

    private final Driver neo4jDriver;

    public KgNeo4jWriter(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /** Write KnowledgePoint nodes from builder output. */
    public int writeKnowledgePoints(List<Map<String, Object>> nodes) {
        int count = 0;
        try (var session = neo4jDriver.session()) {
            for (var n : nodes) {
                String id = (String) n.get("id");
                if (id == null || id.isBlank()) continue;
                Object difficulty = n.getOrDefault("difficulty", 3);
                session.run("""
                    MERGE (n:KnowledgePoint {id: $id})
                    SET n.name = $name, n.category = $cat, n.difficulty = $diff,
                        n.description = $desc
                    """,
                    Map.of("id", id, "name", (String) n.getOrDefault("name", id),
                        "cat", (String) n.getOrDefault("category", "概念"),
                        "diff", difficulty instanceof Number num ? num.longValue() : 3L,
                        "desc", (String) n.getOrDefault("description", "")));
                count++;
            }
        } catch (Exception e) {
            log.error("KgNeo4jWriter: write KP failed: {}", e.getMessage());
        }
        return count;
    }

    /** Link relationships between already-created KnowledgePoint nodes. */
    public int linkRelationships(List<Map<String, Object>> nodes) {
        int count = 0;
        try (var session = neo4jDriver.session()) {
            for (var n : nodes) {
                String id = (String) n.get("id");
                if (id == null) continue;

                @SuppressWarnings("unchecked")
                List<String> pre = (List<String>) n.getOrDefault("prerequisites", List.of());
                for (String pId : pre) {
                    session.run("""
                        MATCH (a:KnowledgePoint {id: $from}), (b:KnowledgePoint {id: $to})
                        MERGE (a)-[:REQUIRES]->(b)
                        """, Map.of("from", id, "to", pId));
                    count++;
                }
                @SuppressWarnings("unchecked")
                List<String> rel = (List<String>) n.getOrDefault("relatedConcepts", List.of());
                for (String rId : rel) {
                    session.run("""
                        MATCH (a:KnowledgePoint {id: $from}), (b:KnowledgePoint {id: $to})
                        MERGE (a)-[:RELATED_TO]->(b)
                        """, Map.of("from", id, "to", rId));
                    count++;
                }
            }
        } catch (Exception e) {
            log.error("KgNeo4jWriter: link relations failed: {}", e.getMessage());
        }
        return count;
    }

    /** Count existing KnowledgePoint nodes. */
    public long countKnowledgePoints() {
        try (var session = neo4jDriver.session()) {
            var r = session.run("MATCH (n:KnowledgePoint) RETURN count(n) AS c");
            return r.hasNext() ? r.single().get("c").asLong() : 0;
        } catch (Exception e) { return 0; }
    }

    /** Delete all KnowledgePoint, Course, LearningResource nodes. */
    public void clearGraph() {
        try (var session = neo4jDriver.session()) {
            session.run("MATCH (n) WHERE n:KnowledgePoint OR n:Course OR n:LearningResource DETACH DELETE n");
            log.info("KgNeo4jWriter: cleared KG nodes");
        } catch (Exception e) {
            log.error("KgNeo4jWriter: clear failed: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/builder/KgNeo4jWriter.java
git commit -m "feat(kg-build): add KgNeo4jWriter for raw Cypher KG writes"
```

---

### Task 7: KgBuildAgent (Orchestrator)

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/KgBuildAgent.java`

- [ ] **Step 1: Create orchestrator**

```java
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

/**
 * Orchestrates the full KG build pipeline:
 * syncSources → processChunks → buildFull / buildIncremental
 */
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

    /** Step 1: Fetch all configured sources → chunk → embed → store in Qdrant. */
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

    /** Step 2: Full build — generate all KG nodes from scratch. */
    public BuildResult buildFull() {
        log.info("KgBuildAgent: starting FULL build");
        writer.clearGraph();

        // Group chunks by topic
        Map<String, List<String>> topicChunks = new LinkedHashMap<>();
        // Use default topics from the 6 ML domains
        List<String> topics = List.of(
            "数学基础", "监督学习", "无监督学习", "深度学习", "集成学习与优化", "应用与工具");
        for (String topic : topics) {
            float[] vec = embedder.embed(topic);
            List<String> refs = store.retrieve(vec, topic, 3);
            topicChunks.put(topic, refs);
        }

        int totalKps = 0, totalRels = 0;
        for (var entry : topicChunks.entrySet()) {
            List<Map<String, Object>> nodes = nodeBuilder.buildNodes(entry.getKey(), entry.getValue());
            int kps = writer.writeKnowledgePoints(nodes);
            int rels = writer.linkRelationships(nodes);
            totalKps += kps;
            totalRels += rels;
        }
        log.info("KgBuildAgent: FULL build done — {} KPs, {} relationships", totalKps, totalRels);
        return new BuildResult(totalKps, totalRels, 0);
    }

    /** Step 3: Incremental build — only process new chunks. */
    public BuildResult buildIncremental() {
        long newCount = store.countByStatus("new");
        if (newCount == 0) {
            log.info("KgBuildAgent: no new chunks to process");
            return new BuildResult(0, 0, 0);
        }
        log.info("KgBuildAgent: starting INCREMENTAL build for {} new chunks", newCount);

        // Process by the same 6 topic domains
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
        log.info("KgBuildAgent: INCREMENTAL build done — {} KPs, {} relationships", totalKps, totalRels);
        return new BuildResult(totalKps, totalRels, newCount);
    }

    /** Get current KG status. */
    public Map<String, Object> getStatus() {
        return Map.of(
            "knowledgePointCount", writer.countKnowledgePoints(),
            "newChunks", store.countByStatus("new"),
            "processedChunks", store.countByStatus("processed")
        );
    }

    public record BuildResult(int knowledgePoints, int relationships, long newChunks) {}
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/KgBuildAgent.java
git commit -m "feat(kg-build): add KgBuildAgent orchestrator"
```

---

### Task 8: REST API + Scheduler

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/api/KgBuildController.java`
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/KgBuildScheduler.java`

- [ ] **Step 1: Create REST controller**

```java
package org.example.educatorweb.knowledgegraph.build.api;

import org.example.educatorweb.knowledgegraph.build.KgBuildAgent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/kg")
public class KgBuildController {

    private final KgBuildAgent agent;

    public KgBuildController(KgBuildAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/sources/sync")
    public ResponseEntity<Map<String, Object>> syncSources() {
        int chunks = agent.syncSources();
        return ResponseEntity.ok(Map.of("syncedChunks", chunks));
    }

    @PostMapping("/build")
    public ResponseEntity<Map<String, Object>> build(@RequestParam(defaultValue = "incremental") String mode) {
        KgBuildAgent.BuildResult result;
        if ("full".equalsIgnoreCase(mode)) {
            result = agent.buildFull();
        } else {
            result = agent.buildIncremental();
        }
        return ResponseEntity.ok(Map.of(
            "knowledgePoints", result.knowledgePoints(),
            "relationships", result.relationships(),
            "newChunks" , result.newChunks()
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(agent.getStatus());
    }
}
```

- [ ] **Step 2: Create scheduler**

```java
package org.example.educatorweb.knowledgegraph.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class KgBuildScheduler {

    private static final Logger log = LoggerFactory.getLogger(KgBuildScheduler.class);
    private final KgBuildAgent agent;
    private volatile boolean running;

    public KgBuildScheduler(KgBuildAgent agent) {
        this.agent = agent;
    }

    @Scheduled(cron = "${kg.build.schedule:0 0 3 * * SUN}")
    public void scheduledBuild() {
        if (running) {
            log.info("KgBuildScheduler: previous build still running, skipping");
            return;
        }
        running = true;
        try {
            log.info("KgBuildScheduler: scheduled build starting");
            agent.syncSources();
            var result = agent.buildIncremental();
            log.info("KgBuildScheduler: done — {} KPs, {} rels", result.knowledgePoints(), result.relationships());
        } catch (Exception e) {
            log.error("KgBuildScheduler: failed: {}", e.getMessage());
        } finally {
            running = false;
        }
    }
}
```

- [ ] **Step 3: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/api/KgBuildController.java src/main/java/org/example/educatorweb/knowledgegraph/build/KgBuildScheduler.java
git commit -m "feat(kg-build): add REST API controller and scheduled build task"
```

---

### Task 9: Wiring (Config class)

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/build/config/KgBuildConfig.java`

- [ ] **Step 1: Create Spring config to wire all beans**

```java
package org.example.educatorweb.knowledgegraph.build.config;

import io.qdrant.client.QdrantClient;
import org.example.educatorweb.knowledgegraph.build.builder.KgNeo4jWriter;
import org.example.educatorweb.knowledgegraph.build.builder.KgNodeBuilder;
import org.example.educatorweb.knowledgegraph.build.processor.KgContentProcessor;
import org.example.educatorweb.knowledgegraph.build.processor.KgReferenceStore;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KgBuildProperties.class)
public class KgBuildConfig {

    @Bean
    public KgReferenceStore kgReferenceStore(QdrantClient qdrantClient) {
        return new KgReferenceStore(qdrantClient);
    }

    @Bean
    public KgContentProcessor kgContentProcessor(EmbeddingService embeddingService) {
        return new KgContentProcessor(embeddingService);
    }

    @Bean
    public KgNodeBuilder kgNodeBuilder(
            @Qualifier("deepSeekProvider") ModelProvider deepSeekProvider) {
        return new KgNodeBuilder(deepSeekProvider);
    }

    @Bean
    public KgNeo4jWriter kgNeo4jWriter(Driver neo4jDriver) {
        return new KgNeo4jWriter(neo4jDriver);
    }
}
```

- [ ] **Step 2: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/config/KgBuildConfig.java
git commit -m "feat(kg-build): add KgBuildConfig wiring all builder beans"
```

---

### Task 10: Deprecate KnowledgeGraphInitializer

**Files:**
- Modify: `src/main/java/org/example/educatorweb/knowledgegraph/service/KnowledgeGraphInitializer.java`

- [ ] **Step 1: Add @Deprecated annotation and disable auto-execution**

```java
@Deprecated(since = "2026-06-15", forRemoval = true)
// Remove @EventListener or add a flag check:
// @EventListener(ApplicationReadyEvent.class) — commented out
// Replaced by KgBuildAgent
```

Actually, since the bean is created in `KnowledgeGraphConfig.java`, we can comment out the `@EventListener` or add a property flag. The simplest: remove the `@EventListener` annotation so it never fires, but keep the class for reference.

Modify `KnowledgeGraphInitializer.java`: remove `@EventListener(ApplicationReadyEvent.class)` from `seedIfEmpty()`.

- [ ] **Step 2: Remove bean from KnowledgeGraphConfig**

In `KnowledgeGraphConfig.java`, comment out the `knowledgeGraphInitializer` bean:

```java
// @Bean — commented out; replaced by KgBuildAgent
// public KnowledgeGraphInitializer knowledgeGraphInitializer(...) { ... }
```

- [ ] **Step 3: Compile and commit**

```bash
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/service/KnowledgeGraphInitializer.java src/main/java/org/example/educatorweb/knowledgegraph/config/KnowledgeGraphConfig.java
git commit -m "chore(kg-build): deprecate KnowledgeGraphInitializer, replaced by KgBuildAgent"
```

---

### Task 11: Integration Test

**Files:**
- Create: `src/test/java/org/example/educatorweb/knowledgegraph/build/KgBuildAgentTest.java`

- [ ] **Step 1: Write basic unit test for KgNodeBuilder**

```java
package org.example.educatorweb.knowledgegraph.build;

import org.example.educatorweb.knowledgegraph.build.builder.KgNodeBuilder;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KgBuildAgentTest {

    @Test
    void nodeBuilderShouldParseValidJson() {
        ModelProvider mockLlm = mock(ModelProvider.class);
        when(mockLlm.chat(anyString())).thenReturn("""
            [{"id":"svm","name":"支持向量机","category":"算法","difficulty":4,
              "description":"最大间隔分类器","prerequisites":["linear_algebra"],
              "relatedConcepts":["kernel_method"]}]
            """);

        KgNodeBuilder builder = new KgNodeBuilder(mockLlm);
        List<Map<String, Object>> nodes = builder.buildNodes("SVM", List.of("test ref"));

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).get("id")).isEqualTo("svm");
        assertThat(nodes.get(0).get("name")).isEqualTo("支持向量机");
    }

    @Test
    void nodeBuilderShouldReturnEmptyOnInvalidJson() {
        ModelProvider mockLlm = mock(ModelProvider.class);
        when(mockLlm.chat(anyString())).thenReturn("not json at all");

        KgNodeBuilder builder = new KgNodeBuilder(mockLlm);
        List<Map<String, Object>> nodes = builder.buildNodes("test", List.of());

        assertThat(nodes).isEmpty();
    }
}
```

- [ ] **Step 2: Run test**

```bash
mvn test -Dtest=KgBuildAgentTest -DfailIfNoTests=false
```
Expected: 2 tests pass

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/example/educatorweb/knowledgegraph/build/KgBuildAgentTest.java
git commit -m "test(kg-build): add KgNodeBuilder unit tests"
```

---

### Task 12: Final Verification

- [ ] **Step 1: Build entire project**

```bash
mvn clean compile -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Run all tests**

```bash
mvn test -DfailIfNoTests=false
```
Expected: 20/20 pass (18 existing + 2 new)

- [ ] **Step 3: Commit final state**

```bash
git add .
git commit -m "chore(kg-build): final integration verification"
```
