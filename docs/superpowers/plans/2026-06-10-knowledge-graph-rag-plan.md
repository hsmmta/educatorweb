# Knowledge Graph + RAG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build dual-channel retrieval for resource generation: Neo4j knowledge graph (structured concept relations) + Qdrant vector search (semantic document retrieval), replacing mock implementations with real ones.

**Architecture:** Two independent Java-only channels — KG (`knowledgegraph/` package) and RAG (`rag/` package) — both implementing existing interfaces (`KnowledgeGraphService`, `RagService`). Spring profiles (`mock` vs default) auto-switch implementations. Zero changes to `resourcegen/` package.

**Tech Stack:** Spring Boot 3.4.3, Spring Data Neo4j, Qdrant Java Client 1.12.0, DeepSeek API (chat + embedding), Apache POI (for PDF text extraction)

---

## File Map

| File | Role |
|------|------|
| `pom.xml` | Add `qdrant-java-client` + `pdf-box` dependency |
| `knowledgegraph/model/KnowledgePoint.java` | Neo4j `@Node` entity |
| `knowledgegraph/repository/KnowledgePointRepository.java` | SDN repository with Cypher |
| `knowledgegraph/service/LlmKnowledgeExtractor.java` | DeepSeek fallback extractor |
| `knowledgegraph/service/KnowledgeGraphInitializer.java` | Seed data on startup |
| `knowledgegraph/service/Neo4jKnowledgeGraphService.java` | `@Primary` implementation |
| `knowledgegraph/config/KnowledgeGraphConfig.java` | Spring config (if needed) |
| `rag/model/DocumentChunk.java` | Qdrant point data model |
| `rag/service/EmbeddingService.java` | DeepSeek embedding HTTP client |
| `rag/service/QdrantRagService.java` | `@Primary` implementation of `RagService` |
| `rag/service/DocumentIngester.java` | Document → chunks → Qdrant pipeline |
| `rag/config/RagConfig.java` | Qdrant client + beans config |
| `application.yml` | Add qdrant connection config |
| Test files (2) | `Neo4jKnowledgeGraphServiceTest`, `QdrantRagServiceTest` |

---

### Task 1: Add Dependencies to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add Qdrant Java client and PDF text extraction dependencies**

The `qdrant-java-client` dependency was already added. Add `pdf-box` for document text extraction:

```xml
<!-- Add after the qdrant-java-client dependency -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.3</version>
</dependency>
```

- [ ] **Step 2: Build to verify dependencies resolve**

Run: `mvn dependency:resolve -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat: add qdrant-java-client and pdfbox dependencies"
```

---

### Task 2: KG — KnowledgePoint Neo4j Entity

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/model/KnowledgePoint.java`

- [ ] **Step 1: Create the Neo4j node entity**

```java
package org.example.educatorweb.knowledgegraph.model;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

@Node("KnowledgePoint")
public class KnowledgePoint {

    @Id
    private String id;           // "svm", "linear_regression"

    private String name;         // "支持向量机"
    private String category;     // 数学基础 | 概念 | 算法 | 应用 | 工具
    private int difficulty;      // 1-5
    private String description;  // one-sentence summary
    private String chapter;      // "监督学习"

    @Relationship(type = "REQUIRES", direction = Relationship.Direction.OUTGOING)
    private Set<KnowledgePoint> prerequisites = new HashSet<>();

    @Relationship(type = "RELATED_TO", direction = Relationship.Direction.OUTGOING)
    private Set<KnowledgePoint> relatedConcepts = new HashSet<>();

    public KnowledgePoint() {}

    public KnowledgePoint(String id, String name, String category, int difficulty,
                          String description, String chapter) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.difficulty = difficulty;
        this.description = description;
        this.chapter = chapter;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getDifficulty() { return difficulty; }
    public void setDifficulty(int difficulty) { this.difficulty = difficulty; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getChapter() { return chapter; }
    public void setChapter(String chapter) { this.chapter = chapter; }
    public Set<KnowledgePoint> getPrerequisites() { return prerequisites; }
    public void setPrerequisites(Set<KnowledgePoint> prerequisites) { this.prerequisites = prerequisites; }
    public Set<KnowledgePoint> getRelatedConcepts() { return relatedConcepts; }
    public void setRelatedConcepts(Set<KnowledgePoint> relatedConcepts) { this.relatedConcepts = relatedConcepts; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KnowledgePoint that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/knowledgegraph/model/KnowledgePoint.java
git commit -m "feat(kg): add KnowledgePoint Neo4j node entity"
```

---

### Task 3: KG — KnowledgePointRepository

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/repository/KnowledgePointRepository.java`

- [ ] **Step 1: Create Spring Data Neo4j repository with custom Cypher queries**

```java
package org.example.educatorweb.knowledgegraph.repository;

import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KnowledgePointRepository extends Neo4jRepository<KnowledgePoint, String> {

    /**
     * Find by ID or name (name is often what generators pass, e.g. "支持向量机").
     */
    Optional<KnowledgePoint> findByName(String name);

    /**
     * Prerequisites: MATCH (n)-[:REQUIRES]->(p) for the given knowledge point id.
     */
    @Query("MATCH (n:KnowledgePoint {id: $id})-[:REQUIRES]->(prereq:KnowledgePoint) RETURN prereq")
    List<KnowledgePoint> findPrerequisites(@Param("id") String id);

    /**
     * Successors: MATCH (n)<-[:REQUIRES]-(succ) for the given knowledge point id.
     * (nodes that REQUIRE this knowledge point, i.e., this is a prerequisite FOR them)
     */
    @Query("MATCH (n:KnowledgePoint {id: $id})<-[:REQUIRES]-(succ:KnowledgePoint) RETURN succ")
    List<KnowledgePoint> findSuccessors(@Param("id") String id);

    /**
     * Related concepts (bidirectional).
     */
    @Query("MATCH (n:KnowledgePoint {id: $id})-[:RELATED_TO]-(related:KnowledgePoint) RETURN related")
    List<KnowledgePoint> findRelated(@Param("id") String id);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/knowledgegraph/repository/KnowledgePointRepository.java
git commit -m "feat(kg): add KnowledgePointRepository with Cypher queries"
```

---

### Task 4: KG — LlmKnowledgeExtractor

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/service/LlmKnowledgeExtractor.java`

- [ ] **Step 1: Create the DeepSeek-powered knowledge extractor for fallback queries**

This component calls DeepSeek's chat API when a knowledge point is not found in Neo4j. It uses the existing `ModelProvider` interface (DeepSeekProvider bean).

```java
package org.example.educatorweb.knowledgegraph.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uses DeepSeek LLM to extract knowledge context for unknown knowledge points.
 * Results are written back to Neo4j so the same query never needs LLM twice.
 */
public class LlmKnowledgeExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmKnowledgeExtractor.class);

    private final ModelProvider llmProvider;
    private final KnowledgePointRepository repo;
    private final ObjectMapper objectMapper;

    public LlmKnowledgeExtractor(ModelProvider llmProvider, KnowledgePointRepository repo) {
        this.llmProvider = llmProvider;
        this.repo = repo;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Ask DeepSeek about a knowledge point, parse the result, and persist to Neo4j.
     */
    public KnowledgeContext extract(String knowledgePointName) {
        log.info("LlmKnowledgeExtractor: asking DeepSeek about '{}'", knowledgePointName);

        String prompt = buildExtractionPrompt(knowledgePointName);
        String response = llmProvider.chat(prompt);

        if (response == null || response.isBlank()) {
            log.warn("LlmKnowledgeExtractor: empty response for '{}'", knowledgePointName);
            return emptyContext();
        }

        try {
            response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();
            Map<String, Object> data = objectMapper.readValue(response,
                new TypeReference<Map<String, Object>>() {});

            String id = slugify(knowledgePointName);
            String name = (String) data.getOrDefault("name", knowledgePointName);
            String category = (String) data.getOrDefault("category", "概念");
            int difficulty = data.containsKey("difficulty")
                ? ((Number) data.get("difficulty")).intValue() : 3;
            String description = (String) data.getOrDefault("description", "");
            String chapter = (String) data.getOrDefault("chapter", "");

            @SuppressWarnings("unchecked")
            List<String> prerequisiteNames = (List<String>) data.getOrDefault("prerequisites", List.of());
            @SuppressWarnings("unchecked")
            List<String> relatedNames = (List<String>) data.getOrDefault("relatedConcepts", List.of());

            // Persist node
            KnowledgePoint node = new KnowledgePoint(id, name, category, difficulty, description, chapter);
            repo.save(node);

            // Link prerequisites
            for (String preName : prerequisiteNames) {
                KnowledgePoint prereq = repo.findByName(preName)
                    .orElseGet(() -> repo.save(
                        new KnowledgePoint(slugify(preName), preName, "概念", 3, "", "")));
                node.getPrerequisites().add(prereq);
            }

            // Link related concepts
            for (String relName : relatedNames) {
                KnowledgePoint related = repo.findByName(relName)
                    .orElseGet(() -> repo.save(
                        new KnowledgePoint(slugify(relName), relName, "概念", 3, "", "")));
                node.getRelatedConcepts().add(related);
            }

            repo.save(node);
            log.info("LlmKnowledgeExtractor: persisted '{}' (id={}) with {} prerequisites, {} related",
                name, id, prerequisiteNames.size(), relatedNames.size());

            return new KnowledgeContext(prerequisiteNames, List.of(), relatedNames, difficulty);

        } catch (Exception e) {
            log.warn("LlmKnowledgeExtractor: failed to parse response for '{}': {}",
                knowledgePointName, e.getMessage());
            return emptyContext();
        }
    }

    private String buildExtractionPrompt(String knowledgePoint) {
        return """
            You are a Machine Learning curriculum expert. For the given knowledge point, provide:
            - name: display name
            - category: one of 数学基础, 概念, 算法, 应用, 工具
            - difficulty: 1-5
            - description: one-sentence summary
            - chapter: which ML chapter it belongs to (e.g. 监督学习, 深度学习)
            - prerequisites: list of concept names that should be learned first
            - relatedConcepts: list of related concept names

            Knowledge point: %s

            Output ONLY valid JSON, no markdown:
            {"name":"...","category":"...","difficulty":3,"description":"...","chapter":"...","prerequisites":["..."],"relatedConcepts":["..."]}
            """.formatted(knowledgePoint);
    }

    private KnowledgeContext emptyContext() {
        return new KnowledgeContext(List.of(), List.of(), List.of(), 0);
    }

    private String slugify(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_")
            .replaceAll("^_|_$", "");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/knowledgegraph/service/LlmKnowledgeExtractor.java
git commit -m "feat(kg): add LlmKnowledgeExtractor for DeepSeek fallback"
```

---

### Task 5: KG — KnowledgeGraphInitializer

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/service/KnowledgeGraphInitializer.java`

- [ ] **Step 1: Create the seed data initializer**

This runs on application startup (non-mock profile), checks if Neo4j is empty, and if so, calls DeepSeek to generate ~80 knowledge points and their relationships, then batch-inserts them.

```java
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
            Map<String, KnowledgePoint> nodeMap = new java.util.HashMap<>();
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/knowledgegraph/service/KnowledgeGraphInitializer.java
git commit -m "feat(kg): add KnowledgeGraphInitializer for seed data generation"
```

---

### Task 6: KG — Neo4jKnowledgeGraphService

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/service/Neo4jKnowledgeGraphService.java`

- [ ] **Step 1: Create the @Primary implementation of KnowledgeGraphService**

This replaces `MockKnowledgeGraphService` when NOT running with the `mock` profile.

```java
package org.example.educatorweb.knowledgegraph.service;

import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Neo4j-backed implementation of KnowledgeGraphService.
 * Resolves knowledge context via Cypher graph traversal, with DeepSeek LLM fallback
 * for knowledge points not yet in the graph.
 */
@Service
@Primary
public class Neo4jKnowledgeGraphService implements KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jKnowledgeGraphService.class);

    private final KnowledgePointRepository repo;
    private final LlmKnowledgeExtractor extractor;

    public Neo4jKnowledgeGraphService(KnowledgePointRepository repo,
                                       LlmKnowledgeExtractor extractor) {
        this.repo = repo;
        this.extractor = extractor;
    }

    @Override
    public KnowledgeContext queryContext(String knowledgePoint) {
        // Try exact ID match first, then name match
        Optional<KnowledgePoint> nodeOpt = repo.findById(knowledgePoint);
        if (nodeOpt.isEmpty()) {
            nodeOpt = repo.findByName(knowledgePoint);
        }

        if (nodeOpt.isPresent()) {
            KnowledgePoint node = nodeOpt.get();
            try {
                List<String> prerequisites = repo.findPrerequisites(node.getId()).stream()
                    .map(KnowledgePoint::getName).toList();
                List<String> successors = repo.findSuccessors(node.getId()).stream()
                    .map(KnowledgePoint::getName).toList();
                List<String> related = repo.findRelated(node.getId()).stream()
                    .map(KnowledgePoint::getName).toList();

                return new KnowledgeContext(prerequisites, successors, related, node.getDifficulty());
            } catch (Exception e) {
                log.warn("Neo4j query failed for '{}', falling back to LLM: {}",
                    knowledgePoint, e.getMessage());
                return extractor.extract(knowledgePoint);
            }
        }

        // Not in graph — use LLM fallback (which also persists to Neo4j)
        log.info("Knowledge point '{}' not in Neo4j, using LLM fallback", knowledgePoint);
        return extractor.extract(knowledgePoint);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/knowledgegraph/service/Neo4jKnowledgeGraphService.java
git commit -m "feat(kg): add Neo4jKnowledgeGraphService with LLM fallback"
```

---

### Task 7: KG — KnowledgeGraphConfig

**Files:**
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/config/KnowledgeGraphConfig.java`

- [ ] **Step 1: Create config class that wires KG beans**

```java
package org.example.educatorweb.knowledgegraph.config;

import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.knowledgegraph.service.KnowledgeGraphInitializer;
import org.example.educatorweb.knowledgegraph.service.LlmKnowledgeExtractor;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeGraphConfig {

    @Bean
    public LlmKnowledgeExtractor llmKnowledgeExtractor(
            @Qualifier("deepSeekProvider") ModelProvider deepSeekProvider,
            KnowledgePointRepository repo) {
        return new LlmKnowledgeExtractor(deepSeekProvider, repo);
    }

    @Bean
    public KnowledgeGraphInitializer knowledgeGraphInitializer(
            KnowledgePointRepository repo,
            @Qualifier("deepSeekProvider") ModelProvider deepSeekProvider) {
        return new KnowledgeGraphInitializer(repo, deepSeekProvider);
    }
}
```

Note: `@Qualifier("deepSeekProvider")` is needed because `ResourceGenConfig` creates multiple `ModelProvider` beans (deepSeekProvider, openAiProvider, etc.).

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/knowledgegraph/config/KnowledgeGraphConfig.java
git commit -m "feat(kg): add KnowledgeGraphConfig wiring KG beans"
```

---

### Task 8: KG — Unit Test

**Files:**
- Create: `src/test/java/org/example/educatorweb/knowledgegraph/service/Neo4jKnowledgeGraphServiceTest.java`

- [ ] **Step 1: Write test using Testcontainers Neo4j**

```java
package org.example.educatorweb.knowledgegraph.service;

import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataNeo4jTest
@Testcontainers
@Import({KnowledgeGraphConfig.class})
class Neo4jKnowledgeGraphServiceTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5")
        .withAdminPassword("secret123");

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", () -> "secret123");
    }

    @Autowired
    private KnowledgePointRepository repo;

    private Neo4jKnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        // Create a real service without LLM extractor (unit test, not integration)
        service = new Neo4jKnowledgeGraphService(repo, null);

        // Seed test data
        KnowledgePoint la = new KnowledgePoint("linear_algebra", "线性代数", "数学基础", 3, "", "数学基础");
        KnowledgePoint lr = new KnowledgePoint("linear_regression", "线性回归", "算法", 2, "", "监督学习");
        KnowledgePoint gd = new KnowledgePoint("gradient_descent", "梯度下降", "算法", 3, "", "优化方法");
        KnowledgePoint svm = new KnowledgePoint("svm", "支持向量机", "算法", 4, "", "监督学习");

        lr.getPrerequisites().add(la);
        lr.getPrerequisites().add(gd);
        lr.getRelatedConcepts().add(svm);

        repo.save(la);
        repo.save(gd);
        repo.save(lr);
        repo.save(svm);
    }

    @Test
    void shouldFindPreRequisites() {
        KnowledgeContext ctx = service.queryContext("linear_regression");

        assertThat(ctx.prerequisites()).contains("线性代数", "梯度下降");
        assertThat(ctx.relatedConcepts()).contains("支持向量机");
        assertThat(ctx.difficultyLevel()).isEqualTo(2);
    }

    @Test
    void shouldFindByName() {
        KnowledgeContext ctx = service.queryContext("支持向量机");

        assertThat(ctx.difficultyLevel()).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Run test**

Run: `mvn test -pl . -Dtest=Neo4jKnowledgeGraphServiceTest -DfailIfNoTests=false`
Expected: Tests pass (2/2)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/example/educatorweb/knowledgegraph/service/Neo4jKnowledgeGraphServiceTest.java
git commit -m "test(kg): add Neo4jKnowledgeGraphService unit test"
```

---

### Task 9: RAG — DocumentChunk Model

**Files:**
- Create: `src/main/java/org/example/educatorweb/rag/model/DocumentChunk.java`

- [ ] **Step 1: Create the Qdrant-compatible chunk model**

```java
package org.example.educatorweb.rag.model;

import java.util.Map;
import java.util.UUID;

/**
 * Represents a document chunk stored in Qdrant.
 * The vector field is kept separate (managed by EmbeddingService + QdrantClient).
 */
public record DocumentChunk(
    UUID id,
    String docId,
    String source,
    String title,
    String text,
    String knowledgePoint,
    int page,
    float[] embedding
) {
    public static DocumentChunk of(String docId, String source, String title,
                                    String text, String knowledgePoint, int page) {
        return new DocumentChunk(UUID.randomUUID(), docId, source, title,
            text, knowledgePoint, page, null);
    }

    public DocumentChunk withEmbedding(float[] embedding) {
        return new DocumentChunk(id, docId, source, title, text,
            knowledgePoint, page, embedding);
    }

    /**
     * Convert to Qdrant payload (everything except id and vector).
     */
    public Map<String, Object> toPayload() {
        return Map.of(
            "docId", docId,
            "source", source,
            "title", title,
            "text", text,
            "knowledgePoint", knowledgePoint,
            "page", page
        );
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/rag/model/DocumentChunk.java
git commit -m "feat(rag): add DocumentChunk Qdrant point model"
```

---

### Task 10: RAG — EmbeddingService

**Files:**
- Create: `src/main/java/org/example/educatorweb/rag/service/EmbeddingService.java`

- [ ] **Step 1: Create the DeepSeek embedding API client**

```java
package org.example.educatorweb.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls DeepSeek's embedding API to convert text to vectors.
 * Uses the same DEEPSEEK_API_KEY as the chat provider.
 */
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final String EMBEDDING_URL = "https://api.deepseek.com/v1/embeddings";
    private static final String EMBEDDING_MODEL = "deepseek-chat"; // DeepSeek uses chat model for embeddings

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmbeddingService(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate embedding vector for a single text.
     */
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    /**
     * Generate embedding vectors for multiple texts in one API call.
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", EMBEDDING_MODEL,
                "input", texts
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EMBEDDING_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Embedding API returned {}: {}", response.statusCode(), response.body());
                return texts.stream().map(t -> new float[0]).toList();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.get("data");
            List<float[]> embeddings = new ArrayList<>();

            for (JsonNode item : data) {
                JsonNode embArray = item.get("embedding");
                float[] vec = new float[embArray.size()];
                for (int i = 0; i < embArray.size(); i++) {
                    vec[i] = embArray.get(i).floatValue();
                }
                embeddings.add(vec);
            }

            log.debug("EmbeddingService: generated {} embeddings, dimension={}",
                embeddings.size(), embeddings.isEmpty() ? 0 : embeddings.get(0).length);
            return embeddings;

        } catch (IOException | InterruptedException e) {
            log.error("EmbeddingService: API call failed: {}", e.getMessage());
            return texts.stream().map(t -> new float[0]).toList();
        }
    }

    // Java 9+ Map.of doesn't handle null values, using simple map creation
    private static <K, V> java.util.Map<K, V> MapOf(K k1, V v1, K k2, V v2) {
        java.util.Map<K, V> map = new java.util.HashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
```

Wait — the `Map.of` with 4 args works fine in Java 9+. Let me fix that:

```java
package org.example.educatorweb.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls DeepSeek's embedding API to convert text to vectors.
 * Uses the same DEEPSEEK_API_KEY as the chat provider.
 */
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final String EMBEDDING_URL = "https://api.deepseek.com/v1/embeddings";
    private static final String EMBEDDING_MODEL = "deepseek-chat";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmbeddingService(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate embedding vector for a single text.
     * @return float array of embedding dimensions, or empty array on failure
     */
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    /**
     * Generate embedding vectors for multiple texts in one API call.
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        try {
            Map<String, Object> body = Map.of(
                "model", EMBEDDING_MODEL,
                "input", texts
            );
            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EMBEDDING_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Embedding API returned {}: {}", response.statusCode(), response.body());
                return texts.stream().map(t -> new float[0]).toList();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.get("data");
            List<float[]> embeddings = new ArrayList<>();

            for (JsonNode item : data) {
                JsonNode embArray = item.get("embedding");
                float[] vec = new float[embArray.size()];
                for (int i = 0; i < embArray.size(); i++) {
                    vec[i] = embArray.get(i).floatValue();
                }
                embeddings.add(vec);
            }

            log.debug("EmbeddingService: generated {} embeddings, dimension={}",
                embeddings.size(), embeddings.isEmpty() ? 0 : embeddings.get(0).length);
            return embeddings;

        } catch (IOException | InterruptedException e) {
            log.error("EmbeddingService: API call failed: {}", e.getMessage());
            return texts.stream().map(t -> new float[0]).toList();
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/rag/service/EmbeddingService.java
git commit -m "feat(rag): add EmbeddingService for DeepSeek embedding API"
```

---

### Task 11: RAG — QdrantRagService

**Files:**
- Create: `src/main/java/org/example/educatorweb/rag/service/QdrantRagService.java`

- [ ] **Step 1: Create the @Primary implementation of RagService**

```java
package org.example.educatorweb.rag.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Collections;
import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.QueryFactory.nearest;

/**
 * Qdrant-backed implementation of RagService.
 * Stores document chunk vectors and retrieves them via semantic similarity.
 */
@Service
@Primary
public class QdrantRagService implements RagService {

    private static final Logger log = LoggerFactory.getLogger(QdrantRagService.class);

    private static final String COLLECTION_NAME = "ml_documents";
    private static final int VECTOR_DIMENSION = 1024; // DeepSeek embedding dimension

    private final QdrantClient qdrantClient;
    private final EmbeddingService embeddingService;
    private volatile boolean collectionInitialized = false;

    public QdrantRagService(QdrantClient qdrantClient, EmbeddingService embeddingService) {
        this.qdrantClient = qdrantClient;
        this.embeddingService = embeddingService;
    }

    @Override
    public List<DocumentSnippet> retrieve(String query, int topK) {
        if (!ensureCollection()) {
            log.warn("QdrantRagService: collection not available, returning empty");
            return List.of();
        }

        try {
            float[] queryVector = embeddingService.embed(query);
            if (queryVector.length == 0) {
                log.warn("QdrantRagService: empty embedding, returning empty");
                return List.of();
            }

            List<Points.ScoredPoint> points = qdrantClient.queryAsync(
                Points.QueryPoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .setLimit(topK)
                    .setQuery(nearest(queryVector))
                    .setWithPayload(Points.WithPayloadSelector.newBuilder()
                        .setEnable(true).build())
                    .build()
            ).get();

            List<DocumentSnippet> results = new ArrayList<>();
            for (Points.ScoredPoint point : points) {
                var payload = point.getPayloadMap();
                String text = payload.getOrDefault("text",
                    com.google.protobuf.Value.newBuilder().setStringValue("").build()).getStringValue();
                String source = payload.getOrDefault("source",
                    com.google.protobuf.Value.newBuilder().setStringValue("").build()).getStringValue();
                double score = 1.0 - point.getScore(); // Qdrant returns distance, convert to similarity

                if (!text.isBlank()) {
                    results.add(new DocumentSnippet(text, source, Math.max(0.0, score)));
                }
            }

            return results;

        } catch (InterruptedException | ExecutionException e) {
            log.error("QdrantRagService: query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Store document chunks in Qdrant.
     */
    public int store(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return 0;
        if (!ensureCollection()) return 0;

        try {
            List<float[]> embeddings = embeddingService.embedBatch(
                chunks.stream().map(DocumentChunk::text).toList());

            Points.PointStruct.Builder pointBuilder = Points.PointStruct.newBuilder();
            List<Points.PointStruct> points = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                float[] vector = i < embeddings.size() ? embeddings.get(i) : new float[0];
                if (vector.length == 0) continue;

                // Build payload map
                var payloadBuilder = com.google.protobuf.Struct.newBuilder();
                payloadBuilder.putFields("docId",
                    com.google.protobuf.Value.newBuilder().setStringValue(chunk.docId()).build());
                payloadBuilder.putFields("source",
                    com.google.protobuf.Value.newBuilder().setStringValue(chunk.source()).build());
                payloadBuilder.putFields("title",
                    com.google.protobuf.Value.newBuilder().setStringValue(chunk.title()).build());
                payloadBuilder.putFields("text",
                    com.google.protobuf.Value.newBuilder().setStringValue(chunk.text()).build());
                payloadBuilder.putFields("knowledgePoint",
                    com.google.protobuf.Value.newBuilder().setStringValue(chunk.knowledgePoint()).build());
                payloadBuilder.putFields("page",
                    com.google.protobuf.Value.newBuilder().setNumberValue(chunk.page()).build());

                Points.PointStruct point = Points.PointStruct.newBuilder()
                    .setId(Points.PointId.newBuilder().setUuid(chunk.id().toString()).build())
                    .setVector(Points.Vector.newBuilder().addAllData(
                        floatList(vector)).build())
                    .setPayload(payloadBuilder.build())
                    .build();
                points.add(point);
            }

            qdrantClient.upsertAsync(
                Points.UpsertPoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .addAllPoints(points)
                    .build()
            ).get();

            log.info("QdrantRagService: stored {} chunks", points.size());
            return points.size();

        } catch (InterruptedException | ExecutionException e) {
            log.error("QdrantRagService: store failed: {}", e.getMessage());
            return 0;
        }
    }

    private boolean ensureCollection() {
        if (collectionInitialized) return true;

        try {
            // Check if collection exists
            boolean exists = qdrantClient.collectionExistsAsync(COLLECTION_NAME).get();
            if (!exists) {
                log.info("QdrantRagService: creating collection '{}'", COLLECTION_NAME);
                qdrantClient.createCollectionAsync(
                    Collections.CreateCollection.newBuilder()
                        .setCollectionName(COLLECTION_NAME)
                        .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                            .setParams(Collections.VectorParams.newBuilder()
                                .setSize(VECTOR_DIMENSION)
                                .setDistance(Collections.Distance.Cosine)
                                .build())
                            .build())
                        .build()
                ).get();
            }
            collectionInitialized = true;
            return true;
        } catch (Exception e) {
            log.error("QdrantRagService: cannot initialize collection: {}", e.getMessage());
            return false;
        }
    }

    private static List<Float> floatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) list.add(f);
        return list;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/rag/service/QdrantRagService.java
git commit -m "feat(rag): add QdrantRagService real implementation"
```

---

### Task 12: RAG — DocumentIngester

**Files:**
- Create: `src/main/java/org/example/educatorweb/rag/service/DocumentIngester.java`

- [ ] **Step 1: Create the document ingestion pipeline**

```java
package org.example.educatorweb.rag.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Ingests documents (PDF, plain text) into the Qdrant vector store.
 * Pipeline: extract text → chunk → embed → store in Qdrant.
 */
public class DocumentIngester {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngester.class);

    private static final int CHUNK_SIZE_CHARS = 500;
    private static final int CHUNK_OVERLAP_CHARS = 50;

    private final QdrantRagService ragService;

    public DocumentIngester(QdrantRagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Ingest a document file and return the number of chunks created.
     */
    public int ingest(File file) {
        String fileName = file.getName();
        log.info("DocumentIngester: ingesting {}", fileName);

        String text = extractText(file);
        if (text == null || text.isBlank()) {
            log.warn("DocumentIngester: no text extracted from {}", fileName);
            return 0;
        }

        String source = fileName.replaceFirst("\\.[^.]+$", "");
        List<DocumentChunk> chunks = chunkText(text, source);

        return ragService.store(chunks);
    }

    /**
     * Ingests raw text directly (e.g., from API or manual input).
     */
    public int ingestText(String text, String source, String knowledgePoint) {
        List<DocumentChunk> chunks = chunkText(text, source);
        return ragService.store(chunks);
    }

    /**
     * Extract raw text from a file. Supports PDF and plain text.
     */
    private String extractText(File file) {
        String name = file.getName().toLowerCase();

        if (name.endsWith(".pdf")) {
            return extractPdfText(file);
        }

        // Plain text / Markdown
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            log.error("DocumentIngester: failed to read {}: {}", name, e.getMessage());
            return null;
        }
    }

    private String extractPdfText(File file) {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (IOException e) {
            log.error("DocumentIngester: PDF extraction failed for {}: {}",
                file.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Split text into overlapping chunks.
     */
    private List<DocumentChunk> chunkText(String text, String source) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE_CHARS, text.length());
            String chunkText = text.substring(start, end).trim();

            if (!chunkText.isEmpty()) {
                chunks.add(DocumentChunk.of(
                    source + "-chunk-" + chunkIndex,
                    source,
                    source,
                    chunkText,
                    "",
                    chunkIndex + 1
                ));
                chunkIndex++;
            }

            start += (CHUNK_SIZE_CHARS - CHUNK_OVERLAP_CHARS);
        }

        log.debug("DocumentIngester: split '{}' into {} chunks", source, chunks.size());
        return chunks;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/rag/service/DocumentIngester.java
git commit -m "feat(rag): add DocumentIngester for PDF/text import pipeline"
```

---

### Task 13: RAG — RagConfig

**Files:**
- Create: `src/main/java/org/example/educatorweb/rag/config/RagConfig.java`
- Modify: `src/main/resources/application.yml` (add qdrant config section)

- [ ] **Step 1: Create Qdrant client and RAG bean configuration**

```java
package org.example.educatorweb.rag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.example.educatorweb.rag.service.DocumentIngester;
import org.example.educatorweb.rag.service.EmbeddingService;
import org.example.educatorweb.rag.service.QdrantRagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Value("${qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${qdrant.port:6334}")
    private int qdrantPort;

    @Value("${qdrant.grpc-port:6334}")
    private int qdrantGrpcPort;

    @Bean
    public QdrantClient qdrantClient() {
        return new QdrantClient(
            QdrantGrpcClient.newBuilder(qdrantHost, qdrantGrpcPort, false).build()
        );
    }

    @Bean
    public EmbeddingService embeddingService() {
        String apiKey = System.getProperty("DEEPSEEK_API_KEY",
            System.getenv().getOrDefault("DEEPSEEK_API_KEY", ""));
        return new EmbeddingService(apiKey);
    }

    @Bean
    public DocumentIngester documentIngester(QdrantRagService ragService) {
        return new DocumentIngester(ragService);
    }
}
```

- [ ] **Step 2: Add Qdrant config to application.yml**

Add the following to `src/main/resources/application.yml` (before the existing `model-routing:` section):

```yaml
# --- Qdrant Vector Database (RAG) ---
qdrant:
  host: ${QDRANT_HOST:localhost}
  port: 6333
  grpc-port: 6334
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/rag/config/RagConfig.java
git add src/main/resources/application.yml
git commit -m "feat(rag): add RagConfig and Qdrant application config"
```

---

### Task 14: RAG — Unit Test

**Files:**
- Create: `src/test/java/org/example/educatorweb/rag/service/QdrantRagServiceTest.java`

- [ ] **Step 1: Write test using Testcontainers Qdrant**

```java
package org.example.educatorweb.rag.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class QdrantRagServiceTest {

    @Container
    static GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:latest")
        .withExposedPorts(6333, 6334);

    private static QdrantRagService service;

    @BeforeAll
    static void setUp() {
        String host = qdrant.getHost();
        int grpcPort = qdrant.getMappedPort(6334);

        QdrantClient client = new QdrantClient(
            QdrantGrpcClient.newBuilder(host, grpcPort, false).build());

        // Use a mock embedding service that returns fixed vectors
        EmbeddingService mockEmbedding = mock(EmbeddingService.class);
        when(mockEmbedding.embed("test query"))
            .thenReturn(new float[1024]); // zero vector for testing
        when(mockEmbedding.embedBatch(org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(List.of(new float[1024]));

        service = new QdrantRagService(client, mockEmbedding);
    }

    @Test
    void shouldStoreAndRetrieveChunks() {
        DocumentChunk chunk = DocumentChunk.of(
            "test-doc", "测试教材", "第1章",
            "SVM通过寻找最大间隔超平面来实现分类", "svm", 1
        ).withEmbedding(new float[1024]);

        int stored = service.store(List.of(chunk));
        assertThat(stored).isEqualTo(1);

        List<DocumentSnippet> results = service.retrieve("SVM 分类", 3);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).content()).contains("SVM");
    }
}
```

- [ ] **Step 2: Run test**

Run: `mvn test -pl . -Dtest=QdrantRagServiceTest -DfailIfNoTests=false`
Expected: Tests pass

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/example/educatorweb/rag/service/QdrantRagServiceTest.java
git commit -m "test(rag): add QdrantRagService integration test"
```

---

### Task 15: Integration Verification

- [ ] **Step 1: Build the project**

```bash
mvn clean compile -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Run all existing tests to verify no regression**

```bash
mvn test -DfailIfNoTests=false
```
Expected: All previously passing tests (17/18) still pass. New KG/RAG tests pass.

- [ ] **Step 3: Verify mock profile still works**

```bash
mvn test -Dspring.profiles.active=mock -Dtest=RequireAgentTest -DfailIfNoTests=false
```
Expected: MockKnowledgeGraphService and MockRagService are used, tests pass.

- [ ] **Step 4: Commit final state**

```bash
git add .
git commit -m "chore: final integration verification for KG+RAG channels"
```

---

## Verification Checklist

After all tasks:

1. [ ] `mvn clean compile` succeeds
2. [ ] `mvn test` passes all tests (including new KG/RAG tests)
3. [ ] `mock` profile still uses old Mock implementations (verified by test)
4. [ ] No files in `resourcegen/` package were modified
5. [ ] git status shows only new files in `knowledgegraph/` and `rag/` packages, plus pom.xml changes
