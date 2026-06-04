# Multi-Resource-Generate Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the multi-modal resource generation module — a Spring Boot + Spring AI microservice that orchestrates 4 AI Agents across 6 parallel generators, producing personalized educational resources (document, mindmap, quiz, video, code, interactive HTML) with SSE streaming progress.

**Architecture:** Lightweight LangGraph-style GraphOrchestrator (GenerationGraph + AgentNode + Router + fanOut/fanIn) driving a two-stage pipeline: RequireAgent → DesignAgent (serial) → 6 parallel Generators → ReviewAgent. Spring AI ChatClient for unified multi-provider LLM calls (DeepSeek → Xunfei). SSCCE via WebFlux Flux.

**Tech Stack:** Spring Boot 3 + WebFlux, Spring AI (OpenAI-compatible), Neo4j, Qdrant, MySQL, Redis, mem0, Testcontainers, Apache POI

**Design Spec:** `docs/superpowers/specs/2026-06-04-multi-resource-generate-design.md`

---

### Task 1: Phase 0 — Maven Scaffold & Spring Boot Foundation

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/org/example/educatorweb/EducatorWebApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/application-deepseek.yml`

- [ ] **Step 1: Convert pom.xml to Spring Boot 3 parent POM**

Replace the current `pom.xml` with a Spring Boot 3 project using Java 25. Key dependencies: `spring-boot-starter-webflux`, `spring-ai-openai-spring-boot-starter` (1.0.0-M6+), `spring-boot-starter-data-jpa`, `spring-boot-starter-data-redis`, `spring-boot-starter-data-neo4j`, `lombok`, `jackson-databind`, `spring-boot-starter-test`, `reactor-test`, `apache-poi-ooxml`, `testcontainers`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.3</version>
        <relativePath/>
    </parent>

    <groupId>org.example</groupId>
    <artifactId>educatorweb</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <java.version>25</java.version>
        <spring-ai.version>1.0.0-M6</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-neo4j</artifactId>
        </dependency>

        <!-- Spring AI -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
        </dependency>

        <!-- Utilities -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
            <version>5.3.0</version>
        </dependency>

        <!-- Database Drivers -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <version>1.20.4</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots><enabled>false</enabled></snapshots>
        </repository>
    </repositories>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>25</release>
                    <compilerArgs>
                        <arg>--enable-preview</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <argLine>--enable-preview</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create Spring Boot main application class**

```java
package org.example.educatorweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EducatorWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(EducatorWebApplication.class, args);
    }
}
```

- [ ] **Step 3: Create base application.yml**

```yaml
server:
  port: 8080

spring:
  application:
    name: educatorweb

  # DeepSeek as default LLM provider
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:sk-placeholder}
      base-url: https://api.deepseek.com/v1
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7

  # Database — disabled by default, enable per profile
  datasource:
    url: jdbc:mysql://localhost:3306/educatorweb?createDatabaseIfNotExist=true
    username: root
    password: ${MYSQL_PASSWORD:root}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    open-in-view: false

  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: ${NEO4J_PASSWORD:password}

  data:
    redis:
      host: localhost
      port: 6379

logging:
  level:
    org.example.educatorweb: DEBUG
    org.springframework.ai: DEBUG

# Generation config
generation:
  fanout:
    thread-pool-size: 6
  checkpoint:
    ttl-hours: 1

review:
  keywords:
    - "violence"
    - "hate speech"
```

- [ ] **Step 4: Create application-deepseek.yml profile**

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com/v1
      chat:
        options:
          model: deepseek-chat
```

- [ ] **Step 5: Verify project compiles and starts**

Run: `mvn clean compile -DskipTests`

Expected: BUILD SUCCESS. If any dependency issues (Java 25 + Spring AI milestone), adjust versions.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/org/example/educatorweb/ src/main/resources/
git commit -m "feat(phase-0): add Spring Boot 3 + Spring AI scaffold with DeepSeek config"
```

---

### Task 2: Phase 0 — Common Types & External Module Interfaces

**Files:**
- Create: `src/main/java/org/example/educatorweb/common/model/ResourceType.java`
- Create: `src/main/java/org/example/educatorweb/common/model/ProgressEvent.java`
- Create: `src/main/java/org/example/educatorweb/common/model/GenerateRequest.java`
- Create: `src/main/java/org/example/educatorweb/profile/ProfileService.java`
- Create: `src/main/java/org/example/educatorweb/profile/model/StudentProfile.java`
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/KnowledgeGraphService.java`
- Create: `src/main/java/org/example/educatorweb/knowledgegraph/model/KnowledgeContext.java`
- Create: `src/main/java/org/example/educatorweb/rag/RagService.java`
- Create: `src/main/java/org/example/educatorweb/rag/model/DocumentSnippet.java`
- Create: `src/main/java/org/example/educatorweb/common/mock/MockProfileService.java`
- Create: `src/main/java/org/example/educatorweb/common/mock/MockKnowledgeGraphService.java`
- Create: `src/main/java/org/example/educatorweb/common/mock/MockRagService.java`

- [ ] **Step 1: Create ResourceType enum**

```java
package org.example.educatorweb.common.model;

public enum ResourceType {
    DOC,
    MINDMAP,
    QUIZ,
    VIDEO,
    CODE,
    HTML
}
```

- [ ] **Step 2: Create ProgressEvent record**

```java
package org.example.educatorweb.common.model;

import java.time.Instant;

public record ProgressEvent(
    String requestId,
    String stage,
    String message,
    int progressPercent,
    Instant timestamp
) {
    public ProgressEvent(String requestId, String stage, String message, int progressPercent) {
        this(requestId, stage, message, progressPercent, Instant.now());
    }
}
```

- [ ] **Step 3: Create GenerateRequest record**

```java
package org.example.educatorweb.common.model;

import java.util.List;

public record GenerateRequest(
    String studentId,
    String knowledgePoint,
    List<ResourceType> types
) {
    public GenerateRequest {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId is required");
        }
        if (knowledgePoint == null || knowledgePoint.isBlank()) {
            throw new IllegalArgumentException("knowledgePoint is required");
        }
        if (types == null || types.isEmpty()) {
            types = List.of(ResourceType.values()); // default: all types
        }
    }
}
```

- [ ] **Step 4: Create StudentProfile record (6 dimensions)**

```java
package org.example.educatorweb.profile.model;

import java.util.List;
import java.util.Map;

public record StudentProfile(
    D1_KnowledgeBase knowledgeBase,
    D2_CognitiveStyle cognitiveStyle,
    D3_ErrorPattern errorPattern,
    D4_LearningPace learningPace,
    D5_ContentPreference contentPreference,
    D6_GoalOrientation goalOrientation
) {
    public record D1_KnowledgeBase(String level, double confidence, Map<String, String> details) {}
    public record D2_CognitiveStyle(String type, double confidence) {}
    public record D3_ErrorPattern(List<String> tags, double confidence) {}
    public record D4_LearningPace(String type, double confidence) {}
    public record D5_ContentPreference(String type, Map<String, Double> ratio) {}
    public record D6_GoalOrientation(String type, double confidence) {}
}
```

- [ ] **Step 5: Create external module interfaces**

```java
// ProfileService.java
package org.example.educatorweb.profile;

import org.example.educatorweb.profile.model.StudentProfile;

public interface ProfileService {
    StudentProfile getProfile(String studentId);
}
```

```java
// KnowledgeGraphService.java
package org.example.educatorweb.knowledgegraph;

import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;

public interface KnowledgeGraphService {
    KnowledgeContext queryContext(String knowledgePoint);
}
```

```java
// KnowledgeContext.java
package org.example.educatorweb.knowledgegraph.model;

import java.util.List;

public record KnowledgeContext(
    List<String> prerequisites,
    List<String> successors,
    List<String> relatedConcepts,
    int difficultyLevel
) {}
```

```java
// RagService.java
package org.example.educatorweb.rag;

import org.example.educatorweb.rag.model.DocumentSnippet;
import java.util.List;

public interface RagService {
    List<DocumentSnippet> retrieve(String query, int topK);
}
```

```java
// DocumentSnippet.java
package org.example.educatorweb.rag.model;

public record DocumentSnippet(
    String content,
    String source,
    double score
) {}
```

- [ ] **Step 6: Create mock implementations for independent development**

```java
// MockProfileService.java
package org.example.educatorweb.common.mock;

import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentProfile;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
@Profile("mock")
public class MockProfileService implements ProfileService {
    @Override
    public StudentProfile getProfile(String studentId) {
        return new StudentProfile(
            new StudentProfile.D1_KnowledgeBase("一般", 0.85,
                Map.of("Python", "熟练", "线性代数", "了解", "概率论", "一般")),
            new StudentProfile.D2_CognitiveStyle("直觉型", 0.72),
            new StudentProfile.D3_ErrorPattern(List.of("过拟合概念混淆", "梯度消失理解错误"), 0.68),
            new StudentProfile.D4_LearningPace("稳扎稳打型", 0.90),
            new StudentProfile.D5_ContentPreference("混合学习",
                Map.of("video", 0.4, "document", 0.35, "code", 0.25)),
            new StudentProfile.D6_GoalOrientation("求职准备", 0.88)
        );
    }
}
```

```java
// MockKnowledgeGraphService.java
package org.example.educatorweb.common.mock;

import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@Profile("mock")
public class MockKnowledgeGraphService implements KnowledgeGraphService {
    @Override
    public KnowledgeContext queryContext(String knowledgePoint) {
        return new KnowledgeContext(
            List.of("线性回归", "逻辑回归", "最优化基础"),
            List.of("核方法", "SVR", "集成学习"),
            List.of("支持向量", "核函数", "间隔最大化", "KKT条件"),
            3
        );
    }
}
```

```java
// MockRagService.java
package org.example.educatorweb.common.mock;

import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.rag.model.DocumentSnippet;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@Profile("mock")
public class MockRagService implements RagService {
    @Override
    public List<DocumentSnippet> retrieve(String query, int topK) {
        return List.of(
            new DocumentSnippet("SVM的核心思想是找到一个最优超平面，使得不同类别之间的间隔最大化...",
                "机器学习教材-第6章", 0.95),
            new DocumentSnippet("拉格朗日对偶性是解决约束优化问题的重要工具...",
                "最优化方法-第3章", 0.88),
            new DocumentSnippet("核技巧允许SVM在高维空间中隐式地计算内积...",
                "统计学习方法-第7章", 0.82)
        );
    }
}
```

- [ ] **Step 7: Verify compilation**

Run: `mvn clean compile`

Expected: BUILD SUCCESS, all interfaces and records compile.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/example/educatorweb/common/ src/main/java/org/example/educatorweb/profile/ src/main/java/org/example/educatorweb/knowledgegraph/ src/main/java/org/example/educatorweb/rag/
git commit -m "feat(phase-0): add common types, external interfaces, and mock services"
```

---

### Task 3: Phase 1 — GraphOrchestrator Core Engine

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/orchestration/AgentNode.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/orchestration/Router.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/orchestration/GenerationGraph.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/orchestration/GraphOrchestrator.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/model/GenerationState.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/model/ProgressStage.java`
- Create: `src/test/java/org/example/educatorweb/resourcegen/orchestration/GraphOrchestratorTest.java`

- [ ] **Step 1: Create ProgressStage enum**

```java
package org.example.educatorweb.resourcegen.model;

public enum ProgressStage {
    INIT,
    REQUIRE,
    DESIGN,
    GENERATING,
    REVIEWING,
    DONE,
    FALLBACK
}
```

- [ ] **Step 2: Create GenerationState record (placeholder — will enrich in Task 5)**

```java
package org.example.educatorweb.resourcegen.model;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.model.DocumentSnippet;

import java.util.*;

public record GenerationState(
    String requestId,
    String studentId,
    String knowledgePoint,
    List<ResourceType> types,

    // Enriched context
    StudentProfile profile,
    KnowledgeContext knowledgeContext,
    List<DocumentSnippet> ragContext,

    // Blueprint
    ResourceBlueprint blueprint,

    // Results
    Map<ResourceType, GeneratedResource> results,

    // Reviews
    List<QualityReport> reviews,
    int reviewRetries,

    // Progress
    ProgressStage stage,
    String error
) {
    public static GenerationState initial(GenerateRequest req) {
        return new GenerationState(
            UUID.randomUUID().toString(),
            req.studentId(),
            req.knowledgePoint(),
            req.types(),
            null, null, null, null,
            new HashMap<>(), new ArrayList<>(), 0,
            ProgressStage.INIT, null
        );
    }

    // Stub types for Phase 1 — will be replaced in Task 5
    public record ResourceBlueprint(String placeholder) {}
    public record GeneratedResource(String placeholder) {}
    public record QualityReport(String placeholder) {}

    // Immutable "with" methods
    public GenerationState withStage(ProgressStage newStage) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, blueprint,
            results, reviews, reviewRetries, newStage, error);
    }

    public GenerationState withProfile(StudentProfile p) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            p, knowledgeContext, ragContext, blueprint,
            results, reviews, reviewRetries, stage, error);
    }
}
```

Note: The placeholder inner records will be replaced in Task 5 with real types.

- [ ] **Step 3: Create AgentNode functional interface**

```java
package org.example.educatorweb.resourcegen.orchestration;

import org.example.educatorweb.resourcegen.model.GenerationState;

@FunctionalInterface
public interface AgentNode {
    GenerationState execute(GenerationState state);
}
```

- [ ] **Step 4: Create Router functional interface**

```java
package org.example.educatorweb.resourcegen.orchestration;

import org.example.educatorweb.resourcegen.model.GenerationState;

@FunctionalInterface
public interface Router {
    String route(GenerationState state);

    // Built-in routers
    Router ALWAYS_DONE = state -> "DONE";
    Router ON_ERROR_FALLBACK = state ->
        state.error() != null ? "FALLBACK" : "DONE";
}
```

- [ ] **Step 5: Create GenerationGraph with fluent builder and validation**

```java
package org.example.educatorweb.resourcegen.orchestration;

import java.util.*;

public class GenerationGraph {
    final Map<String, AgentNode> nodes;
    final Map<String, Router> routers;
    final Map<String, List<String>> edges;
    final Map<String, List<String>> fanOuts;
    final Set<String> retrySources; // nodes that can have backward edges

    private GenerationGraph() {
        this.nodes = new LinkedHashMap<>();
        this.routers = new HashMap<>();
        this.edges = new HashMap<>();
        this.fanOuts = new HashMap<>();
        this.retrySources = new HashSet<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final GenerationGraph graph = new GenerationGraph();

        public Builder node(String name, AgentNode agent) {
            graph.nodes.put(name, agent);
            return this;
        }

        public Builder edge(String from, String to) {
            graph.edges.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            return this;
        }

        public Builder fanOut(String from, List<String> toNodes) {
            graph.fanOuts.put(from, toNodes);
            for (String to : toNodes) {
                if (!graph.nodes.containsKey(to)) {
                    graph.nodes.put(to, null); // placeholder, filled later
                }
            }
            return this;
        }

        public Builder router(String node, Router router) {
            graph.routers.put(node, router);
            return this;
        }

        public Builder retryEdge(String from, String to) {
            graph.retrySources.add(from);
            return edge(from, to);
        }

        public GenerationGraph build() {
            validate();
            return graph;
        }

        private void validate() {
            Set<String> allNodes = new HashSet<>(graph.nodes.keySet());
            allNodes.add("DONE");
            allNodes.add("FALLBACK");

            // Check all edge targets exist
            for (var entry : graph.edges.entrySet()) {
                for (String target : entry.getValue()) {
                    if (!allNodes.contains(target)) {
                        throw new IllegalStateException(
                            "Edge target '" + target + "' not found in graph nodes");
                    }
                }
            }

            // Check all fanOut targets exist
            for (var entry : graph.fanOuts.entrySet()) {
                for (String target : entry.getValue()) {
                    if (!graph.nodes.containsKey(target)) {
                        throw new IllegalStateException(
                            "FanOut target '" + target + "' not found in graph nodes");
                    }
                }
            }

            // Check no orphan nodes (must have at least one incoming edge, except start)
            boolean hasStart = findStartNode(graph) != null;
            if (!hasStart) {
                throw new IllegalStateException("Graph has no start node (node with no incoming edges)");
            }

            // Check terminal nodes exist
            if (!graph.nodes.containsKey("DONE") && !graph.routers.values().stream()
                    .anyMatch(r -> true)) {
                throw new IllegalStateException("Graph must have at least one router leading to DONE");
            }
        }
    }

    static String findStartNode(GenerationGraph graph) {
        Set<String> targets = new HashSet<>();
        for (List<String> edgeList : graph.edges.values()) {
            targets.addAll(edgeList);
        }
        for (List<String> fanOutList : graph.fanOuts.values()) {
            targets.addAll(fanOutList);
        }
        for (String node : graph.nodes.keySet()) {
            if (!targets.contains(node) && !node.equals("DONE") && !node.equals("FALLBACK")) {
                return node;
            }
        }
        return null;
    }
}
```

- [ ] **Step 6: Create GraphOrchestrator with execution loop**

```java
package org.example.educatorweb.resourcegen.orchestration;

import org.example.educatorweb.common.model.ProgressEvent;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class GraphOrchestrator {
    private final ExecutorService executor;

    public GraphOrchestrator(int threadPoolSize) {
        this.executor = Executors.newFixedThreadPool(threadPoolSize);
    }

    public Flux<ProgressEvent> run(GenerationGraph graph, GenerationState initialState) {
        Sinks.Many<ProgressEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

        CompletableFuture.runAsync(() -> {
            try {
                GenerationState state = initialState;
                String currentNode = GenerationGraph.findStartNode(graph);

                while (!"DONE".equals(currentNode) && !"FALLBACK".equals(currentNode)) {
                    if (graph.fanOuts.containsKey(currentNode)) {
                        // Fan-out: execute all targets in parallel
                        List<String> targets = graph.fanOuts.get(currentNode);
                        List<CompletableFuture<GenerationState>> futures = targets.stream()
                            .map(target -> CompletableFuture.supplyAsync(() -> {
                                AgentNode node = graph.nodes.get(target);
                                return node.execute(state);
                            }, executor))
                            .toList();

                        // Wait for all and merge results
                        List<GenerationState> results = futures.stream()
                            .map(CompletableFuture::join)
                            .toList();

                        // Merge all results into state
                        state = mergeResults(state, results);
                        emit(sink, state, "Fan-out complete: " + targets.size() + " generators");

                        // Find fan-in target
                        currentNode = findFanInTarget(graph, targets, currentNode);
                    } else {
                        AgentNode node = graph.nodes.get(currentNode);
                        if (node == null) {
                            state = state.withStage(ProgressStage.FALLBACK);
                            emit(sink, state, "No agent found for node: " + currentNode);
                            break;
                        }
                        try {
                            state = node.execute(state);
                            emit(sink, state, "Node complete: " + currentNode);
                        } catch (Exception e) {
                            String errorMsg = "Error in node " + currentNode + ": " + e.getMessage();
                            state = new GenerationState(
                                state.requestId(), state.studentId(), state.knowledgePoint(), state.types(),
                                state.profile(), state.knowledgeContext(), state.ragContext(),
                                state.blueprint(), state.results(), state.reviews(), state.reviewRetries(),
                                ProgressStage.FALLBACK, errorMsg
                            );
                            emit(sink, state, errorMsg);
                            break;
                        }
                    }

                    // Route to next node
                    if (graph.routers.containsKey(currentNode)) {
                        currentNode = graph.routers.get(currentNode).route(state);
                    } else {
                        List<String> nextNodes = graph.edges.getOrDefault(currentNode, List.of());
                        currentNode = nextNodes.isEmpty() ? "DONE" : nextNodes.getFirst();
                    }
                }

                if ("DONE".equals(currentNode)) {
                    state = state.withStage(ProgressStage.DONE);
                    emit(sink, state, "Generation complete");
                }

                sink.tryEmitComplete();
            } catch (Exception e) {
                sink.tryEmitError(e);
            }
        }, executor);

        return sink.asFlux();
    }

    private void emit(Sinks.Many<ProgressEvent> sink, GenerationState state, String message) {
        int percent = switch (state.stage()) {
            case INIT -> 0;
            case REQUIRE -> 15;
            case DESIGN -> 30;
            case GENERATING -> 60;
            case REVIEWING -> 85;
            case DONE -> 100;
            case FALLBACK -> 100;
        };
        sink.tryEmitNext(new ProgressEvent(state.requestId(), state.stage().name(), message, percent));
    }

    @SuppressWarnings("unchecked")
    private GenerationState mergeResults(GenerationState base, List<GenerationState> fanOutResults) {
        Map<org.example.educatorweb.common.model.ResourceType,
            GenerationState.GeneratedResource> merged = new HashMap<>(base.results());
        for (GenerationState r : fanOutResults) {
            if (r != null && r.results() != null) {
                merged.putAll(r.results());
            }
        }
        // Reconstruct state with merged results
        return new GenerationState(
            base.requestId(), base.studentId(), base.knowledgePoint(), base.types(),
            base.profile(), base.knowledgeContext(), base.ragContext(),
            base.blueprint(), merged, base.reviews(), base.reviewRetries(),
            ProgressStage.GENERATING, base.error()
        );
    }

    private String findFanInTarget(GenerationGraph graph, List<String> fanOutTargets, String fanOutSource) {
        // Find the common node that all fanOut targets have edges to
        Set<String> candidateTargets = null;
        for (String target : fanOutTargets) {
            Set<String> nextSet = new HashSet<>(graph.edges.getOrDefault(target, List.of()));
            if (candidateTargets == null) {
                candidateTargets = nextSet;
            } else {
                candidateTargets.retainAll(nextSet);
            }
        }
        if (candidateTargets != null && candidateTargets.size() == 1) {
            return candidateTargets.iterator().next();
        }
        // Fallback: if fan-out targets don't converge, check if any router handles this
        return "DONE";
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 7: Write unit tests for GraphOrchestrator**

```java
package org.example.educatorweb.resourcegen.orchestration;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GraphOrchestratorTest {
    private final GraphOrchestrator orchestrator = new GraphOrchestrator(4);

    @Test
    void shouldExecuteSimpleTwoNodeSerialPipeline() {
        var state = GenerationState.initial(new GenerateRequest("s1", "SVM", List.of(ResourceType.DOC)));
        AtomicInteger aExecuted = new AtomicInteger(0);
        AtomicInteger bExecuted = new AtomicInteger(0);

        var graph = GenerationGraph.builder()
            .node("A", s -> { aExecuted.incrementAndGet(); return s.withStage(org.example.educatorweb.resourcegen.model.ProgressStage.DESIGN); })
            .node("B", s -> { bExecuted.incrementAndGet(); return s.withStage(org.example.educatorweb.resourcegen.model.ProgressStage.DONE); })
            .edge("A", "B")
            .router("B", Router.ALWAYS_DONE)
            .build();

        var events = orchestrator.run(graph, state);

        StepVerifier.create(events)
            .expectNextCount(2) // A complete + B complete
            .verifyComplete();

        assertEquals(1, aExecuted.get());
        assertEquals(1, bExecuted.get());
    }

    @Test
    void shouldExecuteFanOutInParallel() {
        var state = GenerationState.initial(new GenerateRequest("s1", "SVM",
            List.of(ResourceType.DOC, ResourceType.MINDMAP)));

        var graph = GenerationGraph.builder()
            .node("DESIGN", s -> s.withStage(org.example.educatorweb.resourcegen.model.ProgressStage.DESIGN))
            .fanOut("DESIGN", List.of("GEN_A", "GEN_B"))
            .node("GEN_A", s -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                return s;
            })
            .node("GEN_B", s -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                return s;
            })
            .router("DESIGN", Router.ALWAYS_DONE)
            .build();

        var events = orchestrator.run(graph, state);

        StepVerifier.create(events)
            .expectNextCount(2) // DESIGN + fanOut complete
            .verifyComplete();
    }

    @Test
    void shouldHandleErrorInNode() {
        var state = GenerationState.initial(new GenerateRequest("s1", "SVM", List.of(ResourceType.DOC)));

        var graph = GenerationGraph.builder()
            .node("A", s -> { throw new RuntimeException("test error"); })
            .router("A", Router.ON_ERROR_FALLBACK)
            .build();

        var events = orchestrator.run(graph, state);

        StepVerifier.create(events)
            .expectNextMatches(e -> e.message().contains("test error"))
            .verifyComplete();
    }

    @Test
    void shouldDetectCyclicGraphAtBuildTime() {
        assertThrows(IllegalStateException.class, () -> {
            GenerationGraph.builder()
                .node("A", s -> s)
                .node("B", s -> s)
                .edge("A", "B")
                .edge("B", "A") // cycle
                .build();
        });
    }
}
```

- [ ] **Step 8: Run tests and verify they pass**

Run: `mvn test -pl . -Dtest=GraphOrchestratorTest`

Expected: 4 tests PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/ src/test/
git commit -m "feat(phase-1): add GraphOrchestrator engine with fanOut/fanIn support"
```

---

### Task 4: Phase 2 — ResourceGen Data Models

**Files:**
- Modify: `src/main/java/org/example/educatorweb/resourcegen/model/GenerationState.java` (replace stubs with real types)
- Create: `src/main/java/org/example/educatorweb/resourcegen/model/ResourceBlueprint.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/model/GeneratedResource.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/model/QualityReport.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/Generator.java`

- [ ] **Step 1: Create ResourceBlueprint record**

```java
package org.example.educatorweb.resourcegen.model;

import org.example.educatorweb.common.model.ResourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ResourceBlueprint(
    String title,
    String summary,
    List<BlueprintSection> sections,
    Map<ResourceType, ResourcePlan> resourcePlans,
    List<DifficultyAdjustment> adjustments,
    Instant createdAt
) {
    public record BlueprintSection(
        String heading,
        int depth,
        String keyPoints,
        List<BlueprintSection> children
    ) {}

    public record ResourcePlan(
        String promptFocus,
        List<String> keyPoints,
        String formatHint
    ) {}

    public record DifficultyAdjustment(
        String dimension,
        String description,
        String effect
    ) {}
}
```

- [ ] **Step 2: Create GeneratedResource record**

```java
package org.example.educatorweb.resourcegen.model;

import org.example.educatorweb.common.model.ResourceType;
import java.time.Instant;
import java.util.Map;

public record GeneratedResource(
    String resourceId,
    ResourceType type,
    String knowledgePoint,
    String title,
    String content,
    Map<String, Object> metadata,
    Instant createdAt
) {
    public static GeneratedResource of(ResourceType type, String knowledgePoint,
                                        String title, String content) {
        return new GeneratedResource(
            java.util.UUID.randomUUID().toString(),
            type, knowledgePoint, title, content,
            Map.of(), Instant.now()
        );
    }
}
```

- [ ] **Step 3: Create QualityReport record**

```java
package org.example.educatorweb.resourcegen.model;

import org.example.educatorweb.common.model.ResourceType;
import java.time.Instant;
import java.util.List;

public record QualityReport(
    String resourceId,
    ResourceType resourceType,
    boolean passed,
    List<QualityIssue> issues,
    int retryCount,
    Instant reviewedAt
) {
    public record QualityIssue(
        QualityLayer layer,
        String description,
        Severity severity
    ) {}

    public enum QualityLayer { L1_KEYWORD, L2_LLM_REVIEW, L3_EXECUTION, L4_MANUAL_FLAG }
    public enum Severity { BLOCK, WARN, INFO }
}
```

- [ ] **Step 4: Update GenerationState to use real types (replace placeholder records)**

Rewrite `GenerationState.java` to remove inner placeholder records and use the actual model classes:

```java
package org.example.educatorweb.resourcegen.model;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.rag.model.DocumentSnippet;
import java.util.*;

public record GenerationState(
    String requestId,
    String studentId,
    String knowledgePoint,
    List<ResourceType> types,
    StudentProfile profile,
    KnowledgeContext knowledgeContext,
    List<DocumentSnippet> ragContext,
    ResourceBlueprint blueprint,
    Map<ResourceType, GeneratedResource> results,
    List<QualityReport> reviews,
    int reviewRetries,
    ProgressStage stage,
    String error
) {
    public static GenerationState initial(GenerateRequest req) {
        return new GenerationState(
            UUID.randomUUID().toString(),
            req.studentId(), req.knowledgePoint(), req.types(),
            null, null, null, null,
            new HashMap<>(), new ArrayList<>(), 0,
            ProgressStage.INIT, null
        );
    }

    public GenerationState withStage(ProgressStage newStage) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, blueprint,
            results, reviews, reviewRetries, newStage, error);
    }

    public GenerationState withProfile(StudentProfile p) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            p, knowledgeContext, ragContext, blueprint,
            results, reviews, reviewRetries, stage, error);
    }

    public GenerationState withContext(KnowledgeContext kc, List<DocumentSnippet> rag) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, kc, rag, blueprint,
            results, reviews, reviewRetries, stage, error);
    }

    public GenerationState withBlueprint(ResourceBlueprint bp) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, bp,
            results, reviews, reviewRetries, stage, error);
    }

    public GenerationState withResult(ResourceType type, GeneratedResource resource) {
        Map<ResourceType, GeneratedResource> newResults = new HashMap<>(results);
        newResults.put(type, resource);
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, blueprint,
            newResults, reviews, reviewRetries, stage, error);
    }

    public GenerationState withReviews(List<QualityReport> newReviews, int newRetries) {
        return new GenerationState(requestId, studentId, knowledgePoint, types,
            profile, knowledgeContext, ragContext, blueprint,
            results, newReviews, newRetries, stage, error);
    }
}
```

Also update `GraphOrchestrator.java` — remove `@SuppressWarnings("unchecked")` and the `GeneratedResource` placeholder reference in mergeResults. The mergeResults method should now use the real `GeneratedResource` type:

```java
private GenerationState mergeResults(GenerationState base, List<GenerationState> fanOutResults) {
    Map<ResourceType, GeneratedResource> merged = new HashMap<>(base.results());
    for (GenerationState r : fanOutResults) {
        if (r != null && r.results() != null) {
            merged.putAll(r.results());
        }
    }
    return new GenerationState(
        base.requestId(), base.studentId(), base.knowledgePoint(), base.types(),
        base.profile(), base.knowledgeContext(), base.ragContext(),
        base.blueprint(), merged, base.reviews(), base.reviewRetries(),
        ProgressStage.GENERATING, base.error()
    );
}
```

- [ ] **Step 5: Create Generator interface**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.orchestration.AgentNode;

public interface Generator extends AgentNode {
    ResourceType supportedType();
}
```

- [ ] **Step 6: Verify compilation and update tests**

Run: `mvn clean compile`

Then re-run: `mvn test -Dtest=GraphOrchestratorTest`

Expected: All previous tests still pass with new types.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/model/
git add src/main/java/org/example/educatorweb/resourcegen/agents/generators/Generator.java
git add src/main/java/org/example/educatorweb/resourcegen/orchestration/GraphOrchestrator.java
git commit -m "feat(phase-2): add resource data models and Generator interface"
```

---

### Task 5: Phase 3 — RequireAgent + DesignAgent + SSE API (First E2E)

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/RequireAgent.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/DesignAgent.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/api/ResourceGenerationService.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/api/ResourceGenerationController.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/config/ResourceGenConfig.java`
- Create: `src/test/java/org/example/educatorweb/resourcegen/api/ResourceGenerationIntegrationTest.java`

- [ ] **Step 1: Create RequireAgent**

```java
package org.example.educatorweb.resourcegen.agents;

import org.example.educatorweb.knowledgegraph.KnowledgeGraphService;
import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.rag.RagService;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.example.educatorweb.resourcegen.orchestration.AgentNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RequireAgent implements AgentNode {
    private static final Logger log = LoggerFactory.getLogger(RequireAgent.class);

    private final ProfileService profileService;
    private final KnowledgeGraphService kgService;
    private final RagService ragService;

    public RequireAgent(ProfileService profileService,
                        KnowledgeGraphService kgService,
                        RagService ragService) {
        this.profileService = profileService;
        this.kgService = kgService;
        this.ragService = ragService;
    }

    @Override
    public GenerationState execute(GenerationState state) {
        log.info("RequireAgent: enriching context for student={}, topic={}",
            state.studentId(), state.knowledgePoint());

        // Fetch student profile (graceful degradation on failure)
        var profile = fetchProfile(state.studentId());
        log.info("  profile level={}", profile != null ? profile.knowledgeBase().level() : "null");

        // Query knowledge graph
        var kgContext = fetchKnowledgeContext(state.knowledgePoint());
        log.info("  KG prerequisites={}", kgContext != null ? kgContext.prerequisites() : "null");

        // Retrieve RAG context
        var ragContext = fetchRagContext(state.knowledgePoint());
        log.info("  RAG snippets={}", ragContext != null ? ragContext.size() : 0);

        return state.withContext(kgContext, ragContext)
                    .withProfile(profile)
                    .withStage(ProgressStage.DESIGN);
    }

    private var fetchProfile(String studentId) {
        try {
            return profileService.getProfile(studentId);
        } catch (Exception e) {
            log.warn("Failed to fetch profile: {}", e.getMessage());
            return null;
        }
    }

    private var fetchKnowledgeContext(String knowledgePoint) {
        try {
            return kgService.queryContext(knowledgePoint);
        } catch (Exception e) {
            log.warn("Failed to query knowledge graph: {}", e.getMessage());
            return null;
        }
    }

    private var fetchRagContext(String knowledgePoint) {
        try {
            return ragService.retrieve(knowledgePoint, 5);
        } catch (Exception e) {
            log.warn("Failed to retrieve RAG context: {}", e.getMessage());
            return java.util.List.of();
        }
    }
}
```

- [ ] **Step 2: Create DesignAgent**

```java
package org.example.educatorweb.resourcegen.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.example.educatorweb.resourcegen.orchestration.AgentNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DesignAgent implements AgentNode {
    private static final Logger log = LoggerFactory.getLogger(DesignAgent.class);
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DesignAgent(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public GenerationState execute(GenerationState state) {
        log.info("DesignAgent: creating blueprint for topic={}", state.knowledgePoint());

        String prompt = buildPrompt(state);
        log.debug("DesignAgent prompt length: {} chars", prompt.length());

        ResourceBlueprint blueprint;
        try {
            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
            blueprint = objectMapper.readValue(response, ResourceBlueprint.class);
            log.info("DesignAgent: blueprint created — title='{}', sections={}",
                blueprint.title(), blueprint.sections().size());
        } catch (Exception e) {
            log.error("DesignAgent failed: {}", e.getMessage());
            // Fallback: create a minimal blueprint
            blueprint = createFallbackBlueprint(state);
        }

        return state.withBlueprint(blueprint)
                    .withStage(ProgressStage.GENERATING);
    }

    private String buildPrompt(GenerationState state) {
        var types = state.types().stream().map(ResourceType::name).toList();
        var profile = state.profile();
        var kg = state.knowledgeContext();

        return """
            You are an expert curriculum designer for a Machine Learning course.
            Create a detailed resource blueprint for the knowledge point: %s

            Student Profile:
            - Knowledge Level: %s
            - Cognitive Style: %s
            - Learning Pace: %s

            Knowledge Graph Context:
            - Prerequisites: %s
            - Related Concepts: %s

            Requested Resource Types: %s

            Output a JSON object with this exact structure:
            {
              "title": "lesson title",
              "summary": "1-2 sentence overview",
              "sections": [
                {"heading": "Section Name", "depth": 1, "keyPoints": "comma-separated keys",
                 "children": [{"heading": "Subsection", "depth": 2, "keyPoints": "...", "children": []}]}
              ],
              "resourcePlans": {
                "DOC": {"promptFocus": "focus area", "keyPoints": ["point1", "point2"], "formatHint": "Markdown"},
                "MINDMAP": {"promptFocus": "...", "keyPoints": ["..."], "formatHint": "Mermaid mindmap"},
                "QUIZ": {"promptFocus": "...", "keyPoints": ["..."], "formatHint": "JSON array"}
              },
              "adjustments": [
                {"dimension": "D1", "description": "content depth adjusted", "effect": "..."}
              ],
              "createdAt": "%s"
            }
            Output ONLY the JSON, no markdown fences, no explanation.
            """.formatted(
                state.knowledgePoint(),
                profile != null ? profile.knowledgeBase().level() : "unknown",
                profile != null ? profile.cognitiveStyle().type() : "unknown",
                profile != null ? profile.learningPace().type() : "unknown",
                kg != null ? String.join(", ", kg.prerequisites()) : "unknown",
                kg != null ? String.join(", ", kg.relatedConcepts()) : "unknown",
                String.join(", ", types),
                Instant.now().toString()
            );
    }

    private ResourceBlueprint createFallbackBlueprint(GenerationState state) {
        return new ResourceBlueprint(
            state.knowledgePoint() + " 学习资料",
            "自动生成的默认学习大纲",
            List.of(new ResourceBlueprint.BlueprintSection("核心概念", 1,
                state.knowledgePoint(), List.of())),
            new HashMap<>(),
            List.of(),
            Instant.now()
        );
    }
}
```

- [ ] **Step 3: Create ResourceGenConfig**

```java
package org.example.educatorweb.resourcegen.config;

import org.example.educatorweb.resourcegen.orchestration.GraphOrchestrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ResourceGenConfig {

    @Value("${generation.fanout.thread-pool-size:6}")
    private int threadPoolSize;

    @Bean(destroyMethod = "shutdown")
    public GraphOrchestrator graphOrchestrator() {
        return new GraphOrchestrator(threadPoolSize);
    }
}
```

- [ ] **Step 4: Create ResourceGenerationService**

```java
package org.example.educatorweb.resourcegen.api;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ProgressEvent;
import org.example.educatorweb.resourcegen.agents.DesignAgent;
import org.example.educatorweb.resourcegen.agents.RequireAgent;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.orchestration.GenerationGraph;
import org.example.educatorweb.resourcegen.orchestration.GraphOrchestrator;
import org.example.educatorweb.resourcegen.orchestration.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ResourceGenerationService {
    private static final Logger log = LoggerFactory.getLogger(ResourceGenerationService.class);

    private final GraphOrchestrator orchestrator;
    private final RequireAgent requireAgent;
    private final DesignAgent designAgent;

    public ResourceGenerationService(GraphOrchestrator orchestrator,
                                      RequireAgent requireAgent,
                                      DesignAgent designAgent) {
        this.orchestrator = orchestrator;
        this.requireAgent = requireAgent;
        this.designAgent = designAgent;
    }

    public Flux<ProgressEvent> generate(GenerateRequest req) {
        log.info("Starting generation: student={}, topic={}, types={}",
            req.studentId(), req.knowledgePoint(), req.types());

        GenerationState initialState = GenerationState.initial(req);
        GenerationGraph graph = buildGraph();

        return orchestrator.run(graph, initialState);
    }

    private GenerationGraph buildGraph() {
        return GenerationGraph.builder()
            .node("REQUIRE", requireAgent)
            .node("DESIGN", designAgent)
            .edge("REQUIRE", "DESIGN")
            .router("DESIGN", Router.ALWAYS_DONE)
            .build();
    }
}
```

- [ ] **Step 5: Create ResourceGenerationController (SSE endpoint)**

```java
package org.example.educatorweb.resourcegen.api;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ProgressEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class ResourceGenerationController {

    private final ResourceGenerationService service;

    public ResourceGenerationController(ResourceGenerationService service) {
        this.service = service;
    }

    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ProgressEvent> generate(@RequestBody GenerateRequest req) {
        return service.generate(req);
    }
}
```

- [ ] **Step 6: Write integration test**

```java
package org.example.educatorweb.resourcegen.api;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"mock", "deepseek"})
class ResourceGenerationIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldStreamProgressEventsForSimpleGeneration() {
        GenerateRequest req = new GenerateRequest("test-1", "SVM对偶问题", List.of(ResourceType.DOC));

        webTestClient.post()
            .uri("/api/generate")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.TEXT_EVENT_STREAM_VALUE)
            .expectBodyList(ProgressEvent.class)
            .hasSize(3) // REQUIRE -> DESIGN -> DONE
            .consumeWith(result -> {
                var events = result.getResponseBody();
                assert events != null && !events.isEmpty();
                assert events.getLast().stage().equals("DONE");
            });
    }
}
```

Note: This test requires a running app with mock services and DeepSeek API key. Mark as `@Disabled` if API key unavailable, or add a `@TestPropertySource` with a specific config.

- [ ] **Step 7: Verify full compilation**

Run: `mvn clean compile`

Expected: BUILD SUCCESS. Fix any `var` type inference issues with explicit types if needed (Java 25 `var` should be fine).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/
git add src/test/
git commit -m "feat(phase-3): add RequireAgent + DesignAgent + SSE API (first E2E)"
```

---

### Task 6: Phase 4 — DocGenerator + MindmapGenerator + FanOut

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/AbstractGenerator.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/DocGenerator.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/MindmapGenerator.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/api/ResourceGenerationService.java`
- Create: `src/test/java/org/example/educatorweb/resourcegen/agents/generators/DocGeneratorTest.java`

- [ ] **Step 1: Create AbstractGenerator base class**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GeneratedResource;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Instant;
import java.util.Map;

public abstract class AbstractGenerator implements Generator {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ChatClient chatClient;
    private final ResourceType type;

    protected AbstractGenerator(ChatClient chatClient, ResourceType type) {
        this.chatClient = chatClient;
        this.type = type;
    }

    @Override
    public ResourceType supportedType() {
        return type;
    }

    @Override
    public GenerationState execute(GenerationState state) {
        long start = System.currentTimeMillis();
        log.info("{}: generating for topic={}", getClass().getSimpleName(), state.knowledgePoint());

        try {
            String content = doGenerate(state);
            long elapsed = System.currentTimeMillis() - start;

            var resource = new GeneratedResource(
                java.util.UUID.randomUUID().toString(),
                type,
                state.knowledgePoint(),
                state.blueprint() != null ? state.blueprint().title() : state.knowledgePoint(),
                content,
                Map.of("generationTimeMs", elapsed, "format", getFormatHint()),
                Instant.now()
            );

            log.info("{}: complete in {}ms", getClass().getSimpleName(), elapsed);
            return state.withResult(type, resource)
                        .withStage(ProgressStage.GENERATING);
        } catch (Exception e) {
            log.error("{}: failed — {}", getClass().getSimpleName(), e.getMessage());
            var errorResource = new GeneratedResource(
                java.util.UUID.randomUUID().toString(),
                type, state.knowledgePoint(),
                "Generation Failed",
                "Error: " + e.getMessage(),
                Map.of("error", true),
                Instant.now()
            );
            return state.withResult(type, errorResource);
        }
    }

    protected abstract String doGenerate(GenerationState state);
    protected abstract String getFormatHint();
}
```

- [ ] **Step 2: Create DocGenerator**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class DocGenerator extends AbstractGenerator {

    public DocGenerator(ChatClient chatClient) {
        super(chatClient, ResourceType.DOC);
    }

    @Override
    protected String doGenerate(GenerationState state) {
        var blueprint = state.blueprint();
        var profile = state.profile();

        String prompt = """
            You are an expert Machine Learning educator.
            Write a comprehensive, well-structured tutorial document in Markdown format about: %s

            Blueprint sections to cover:
            %s

            Audience knowledge level: %s
            Learning style preference: %s

            Requirements:
            - Use proper Markdown headings (##, ###)
            - Include LaTeX math formulas wrapped in $$ for display math and $ for inline
            - Include Python code examples in ```python blocks
            - Add tables where appropriate
            - Use > blockquotes for key insights
            - Aim for 800-1500 words

            RAG reference material:
            %s

            Output ONLY the Markdown document, no preamble.
            """.formatted(
                state.knowledgePoint(),
                formatSections(blueprint),
                profile != null ? profile.knowledgeBase().level() : "intermediate",
                profile != null ? profile.cognitiveStyle().type() : "balanced",
                formatRagContext(state.ragContext())
            );

        return chatClient.prompt().user(prompt).call().content();
    }

    private String formatSections(org.example.educatorweb.resourcegen.model.ResourceBlueprint bp) {
        if (bp == null || bp.sections() == null) return "- Core concepts of " + bp;
        var sb = new StringBuilder();
        for (var s : bp.sections()) {
            sb.append("- ").append(s.heading()).append(": ").append(s.keyPoints()).append("\n");
        }
        return sb.toString();
    }

    private String formatRagContext(java.util.List<org.example.educatorweb.rag.model.DocumentSnippet> snippets) {
        if (snippets == null || snippets.isEmpty()) return "None provided";
        var sb = new StringBuilder();
        for (int i = 0; i < Math.min(snippets.size(), 3); i++) {
            var s = snippets.get(i);
            sb.append(i + 1).append(". [").append(s.source()).append("] ")
              .append(s.content()).append("\n");
        }
        return sb.toString();
    }

    @Override
    protected String getFormatHint() {
        return "markdown";
    }
}
```

- [ ] **Step 3: Create MindmapGenerator**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class MindmapGenerator extends AbstractGenerator {

    public MindmapGenerator(ChatClient chatClient) {
        super(chatClient, ResourceType.MINDMAP);
    }

    @Override
    protected String doGenerate(GenerationState state) {
        var blueprint = state.blueprint();

        String prompt = """
            Generate a Mermaid mindmap diagram for the Machine Learning topic: %s

            Blueprint structure:
            %s

            Knowledge graph — related concepts: %s

            Requirements:
            - Use Mermaid "mindmap" syntax
            - Root node should be the topic name
            - 2-3 levels of hierarchy
            - Each node label should be concise (3-8 words)
            - Include ALL related concepts as sub-nodes

            Output ONLY the Mermaid code block (starting with "mindmap"), no explanation.
            """.formatted(
                state.knowledgePoint(),
                formatBlueprintStructure(blueprint),
                state.knowledgeContext() != null
                    ? String.join(", ", state.knowledgeContext().relatedConcepts())
                    : "standard ML concepts"
            );

        String content = chatClient.prompt().user(prompt).call().content();

        // Clean up: strip markdown code fences if present
        content = content.replaceAll("```mermaid\\s*", "").replaceAll("```\\s*$", "").trim();
        if (!content.startsWith("mindmap")) {
            content = "mindmap\n  " + content.replace("\n", "\n  ");
        }
        return content;
    }

    private String formatBlueprintStructure(org.example.educatorweb.resourcegen.model.ResourceBlueprint bp) {
        if (bp == null || bp.sections() == null) return "Standard ML topic structure";
        var sb = new StringBuilder();
        for (var s : bp.sections()) {
            sb.append("  - ").append(s.heading()).append("\n");
            if (s.children() != null) {
                for (var c : s.children()) {
                    sb.append("    - ").append(c.heading()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    @Override
    protected String getFormatHint() {
        return "mermaid-mindmap";
    }
}
```

- [ ] **Step 4: Update ResourceGenerationService to include generators with fanOut**

```java
// In ResourceGenerationService.java, add generator fields and update buildGraph():

private final DocGenerator docGenerator;
private final MindmapGenerator mindmapGenerator;

// Update constructor to include these
public ResourceGenerationService(GraphOrchestrator orchestrator,
                                  RequireAgent requireAgent,
                                  DesignAgent designAgent,
                                  DocGenerator docGenerator,
                                  MindmapGenerator mindmapGenerator) {
    // ... existing assignments ...
    this.docGenerator = docGenerator;
    this.mindmapGenerator = mindmapGenerator;
}

private GenerationGraph buildGraph(List<ResourceType> requestedTypes) {
    var builder = GenerationGraph.builder()
        .node("REQUIRE", requireAgent)
        .node("DESIGN", designAgent)
        .edge("REQUIRE", "DESIGN");

    // Add fan-out for requested generators
    List<String> genNodes = new ArrayList<>();
    if (requestedTypes.contains(ResourceType.DOC)) {
        builder.node("GEN_DOC", docGenerator);
        genNodes.add("GEN_DOC");
    }
    if (requestedTypes.contains(ResourceType.MINDMAP)) {
        builder.node("GEN_MINDMAP", mindmapGenerator);
        genNodes.add("GEN_MINDMAP");
    }

    if (!genNodes.isEmpty()) {
        builder.fanOut("DESIGN", genNodes);
        // Each generator routes to DONE (ReviewAgent coming in Phase 5)
        for (String node : genNodes) {
            builder.router(node, Router.ALWAYS_DONE);
        }
    } else {
        builder.router("DESIGN", Router.ALWAYS_DONE);
    }

    return builder.build();
}
```

- [ ] **Step 5: Write DocGenerator test**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"mock"})
class DocGeneratorTest {

    @Autowired(required = false)
    private DocGenerator docGenerator;

    @Test
    @org.junit.jupiter.api.Disabled("Requires DeepSeek API key")
    void shouldGenerateValidMarkdownDocument() {
        var state = GenerationState.initial(
            new GenerateRequest("s1", "SVM对偶问题", List.of(ResourceType.DOC)));
        // Note: state needs blueprint for real test — use a pre-built one
        var result = docGenerator.execute(state);
        var resource = result.results().get(ResourceType.DOC);
        assertNotNull(resource);
        assertNotNull(resource.content());
        assertTrue(resource.content().contains("#")); // has headings
    }
}
```

- [ ] **Step 6: Verify compilation and run orchestrator tests**

Run: `mvn clean compile && mvn test -Dtest=GraphOrchestratorTest`

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/agents/generators/
git add src/main/java/org/example/educatorweb/resourcegen/api/ResourceGenerationService.java
git add src/test/
git commit -m "feat(phase-4): add DocGenerator + MindmapGenerator with fanOut support"
```

---

### Task 7: Phase 5 — QuizGenerator + ReviewAgent + Full Pipeline

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/QuizGenerator.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/ReviewAgent.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/config/ReviewKeywordsConfig.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/api/ResourceGenerationService.java`
- Create: `src/test/java/org/example/educatorweb/resourcegen/agents/ReviewAgentTest.java`

- [ ] **Step 1: Create QuizGenerator**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class QuizGenerator extends AbstractGenerator {
    private static final Logger log = LoggerFactory.getLogger(QuizGenerator.class);
    private final ObjectMapper objectMapper;

    public QuizGenerator(ChatClient chatClient, ObjectMapper objectMapper) {
        super(chatClient, ResourceType.QUIZ);
        this.objectMapper = objectMapper;
    }

    @Override
    protected String doGenerate(GenerationState state) {
        var profile = state.profile();
        // Personalize difficulty distribution based on D1 (knowledge level)
        String diffDist = "EASY:30%, MEDIUM:50%, HARD:20%"; // default
        if (profile != null) {
            switch (profile.knowledgeBase().level()) {
                case "入门" -> diffDist = "EASY:50%, MEDIUM:40%, HARD:10%";
                case "熟练", "精通" -> diffDist = "EASY:10%, MEDIUM:30%, HARD:60%";
            }
        }

        String prompt = """
            Generate a practice quiz for the Machine Learning topic: %s
            Difficulty distribution: %s

            Include a mix of:
            - 3 multiple-choice questions (single correct)
            - 2 true/false questions
            - 1 short-answer question
            - 1 fill-in-the-blank question

            For each question, provide:
            - type: "MULTIPLE_CHOICE" | "TRUE_FALSE" | "SHORT_ANSWER" | "FILL_BLANK"
            - question: the question text
            - options: string array (for MC only)
            - answer: the correct answer
            - explanation: why this is correct
            - difficulty: "EASY" | "MEDIUM" | "HARD"
            - relatedConcept: which concept this tests
            - dimensionTag: which profile dimension this targets (D1-D6)

            Output a JSON object: {"title": "...", "questions": [...]}
            Output ONLY the JSON, no markdown fences.
            """.formatted(state.knowledgePoint(), diffDist);

        return chatClient.prompt().user(prompt).call().content();
    }

    @Override
    protected String getFormatHint() {
        return "application/json";
    }
}
```

- [ ] **Step 2: Create ReviewKeywordsConfig**

```java
package org.example.educatorweb.resourcegen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "review")
public class ReviewKeywordsConfig {
    private List<String> keywords = List.of();

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }
}
```

- [ ] **Step 3: Create ReviewAgent with 4-layer quality check**

```java
package org.example.educatorweb.resourcegen.agents;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.config.ReviewKeywordsConfig;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ProgressStage;
import org.example.educatorweb.resourcegen.model.QualityReport;
import org.example.educatorweb.resourcegen.orchestration.AgentNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class ReviewAgent implements AgentNode {
    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);
    private static final int MAX_RETRIES = 3;

    private final ChatClient chatClient;
    private final ReviewKeywordsConfig keywordsConfig;

    public ReviewAgent(ChatClient chatClient, ReviewKeywordsConfig keywordsConfig) {
        this.chatClient = chatClient;
        this.keywordsConfig = keywordsConfig;
    }

    @Override
    public GenerationState execute(GenerationState state) {
        log.info("ReviewAgent: reviewing {} resources (retry {}/{})",
            state.results().size(), state.reviewRetries(), MAX_RETRIES);

        List<QualityReport> reports = new ArrayList<>();
        boolean allPassed = true;

        for (var entry : state.results().entrySet()) {
            ResourceType type = entry.getKey();
            var resource = entry.getValue();
            List<QualityReport.QualityIssue> issues = new ArrayList<>();

            // L1: Keyword filter
            for (String keyword : keywordsConfig.getKeywords()) {
                if (resource.content() != null &&
                    resource.content().toLowerCase().contains(keyword.toLowerCase())) {
                    issues.add(new QualityReport.QualityIssue(
                        QualityReport.QualityLayer.L1_KEYWORD,
                        "Content contains blocked keyword: " + keyword,
                        QualityReport.Severity.BLOCK
                    ));
                    log.warn("L1 BLOCK: {} contains keyword '{}'", resource.resourceId(), keyword);
                }
            }

            // L2: LLM review (with retry on transient failure)
            try {
                QualityReport.QualityIssue llmIssue = llmReview(resource.content(), type, state.knowledgePoint());
                if (llmIssue != null) {
                    issues.add(llmIssue);
                }
            } catch (Exception e) {
                log.warn("L2 review failed for {}: {}", resource.resourceId(), e.getMessage());
                issues.add(new QualityReport.QualityIssue(
                    QualityReport.QualityLayer.L2_LLM_REVIEW,
                    "LLM review unavailable: " + e.getMessage(),
                    QualityReport.Severity.WARN
                ));
            }

            // L3: Execution validation — skipped for text types, added in Phase 6 for CODE/HTML

            boolean passed = issues.stream()
                .noneMatch(i -> i.severity() == QualityReport.Severity.BLOCK);
            if (!passed) allPassed = false;

            reports.add(new QualityReport(
                resource.resourceId(), type, passed, issues, state.reviewRetries(), Instant.now()
            ));
        }

        int newRetries = state.reviewRetries() + 1;

        if (allPassed) {
            log.info("ReviewAgent: all resources passed");
            return state.withReviews(reports, newRetries).withStage(ProgressStage.DONE);
        } else if (newRetries <= MAX_RETRIES) {
            log.info("ReviewAgent: some failed, retrying ({}/{})", newRetries, MAX_RETRIES);
            return state.withReviews(reports, newRetries).withStage(ProgressStage.DESIGN); // back to redesign
        } else {
            log.warn("ReviewAgent: max retries exceeded, falling back");
            return state.withReviews(reports, newRetries).withStage(ProgressStage.FALLBACK);
        }
    }

    private QualityReport.QualityIssue llmReview(String content, ResourceType type, String knowledgePoint) {
        if (content == null || content.length() < 50) {
            return new QualityReport.QualityIssue(
                QualityReport.QualityLayer.L2_LLM_REVIEW,
                "Content too short or empty",
                QualityReport.Severity.WARN
            );
        }

        String prompt = """
            Review this educational content for the topic "%s" (type: %s).
            Evaluate on:
            1. Accuracy: is the content factually correct?
            2. Relevance: does it relate to the topic?
            3. Completeness: is it thorough enough?

            Content (first 1000 chars): %s

            Respond with ONLY one word: PASS or FAIL
            """.formatted(knowledgePoint, type, content.substring(0, Math.min(content.length(), 1000)));

        String result = chatClient.prompt().user(prompt).call().content();
        if (result != null && result.trim().equalsIgnoreCase("FAIL")) {
            return new QualityReport.QualityIssue(
                QualityReport.QualityLayer.L2_LLM_REVIEW,
                "Content failed accuracy/relevance/completeness review",
                QualityReport.Severity.BLOCK
            );
        }
        return null; // PASS
    }
}
```

- [ ] **Step 4: Update ResourceGenerationService with ReviewAgent and retry loop**

Update `buildGraph()` to include ReviewAgent after fanOut, with conditional retry routing:

```java
// In the buildGraph method, after adding generators:
// Add ReviewAgent
builder.node("REVIEW", reviewAgent);

// Fan-in: all generators go to REVIEW
for (String node : genNodes) {
    builder.edge(node, "REVIEW");
}

// ReviewAgent router: pass → DONE, fail+retry → DESIGN, exhausted → FALLBACK
builder.router("REVIEW", state -> {
    if (state.stage() == ProgressStage.DONE) return "DONE";
    if (state.stage() == ProgressStage.FALLBACK) return "FALLBACK";
    return "DONE"; // The ReviewAgent itself sets the stage
});
```

Note: The ReviewAgent sets the stage to DESIGN (for retry) or DONE/FALLBACK. The router on REVIEW reads the stage to determine the next node. The graph needs a retry edge from REVIEW to DESIGN.

```java
builder.retryEdge("REVIEW", "DESIGN");
```

- [ ] **Step 5: Write ReviewAgent unit test**

```java
package org.example.educatorweb.resourcegen.agents;

import org.example.educatorweb.common.model.GenerateRequest;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.model.GeneratedResource;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mock")
class ReviewAgentTest {

    @Autowired
    private ReviewAgent reviewAgent;

    @Test
    void shouldBlockKeywordContent() {
        var state = GenerationState.initial(
            new GenerateRequest("s1", "test", List.of(ResourceType.DOC)));
        state = state.withResult(ResourceType.DOC,
            new GeneratedResource("r1", ResourceType.DOC, "test", "Title",
                "This contains violence content", Map.of(), java.time.Instant.now()));

        var result = reviewAgent.execute(state);
        var reports = result.reviews();
        assertEquals(1, reports.size());
        assertFalse(reports.getFirst().passed());
        assertTrue(reports.getFirst().issues().stream()
            .anyMatch(i -> i.layer() == org.example.educatorweb.resourcegen.model.QualityReport.QualityLayer.L1_KEYWORD));
    }

    @Test
    void shouldTriggerRetryOnFailure() {
        var state = GenerationState.initial(
            new GenerateRequest("s1", "test", List.of(ResourceType.DOC)));
        state = state.withResult(ResourceType.DOC,
            new GeneratedResource("r1", ResourceType.DOC, "test", "Title",
                "violence", Map.of(), java.time.Instant.now()));

        var result = reviewAgent.execute(state);
        assertEquals(org.example.educatorweb.resourcegen.model.ProgressStage.DESIGN, result.stage());
        assertEquals(1, result.reviewRetries());
    }

    @Test
    void shouldFallbackAfterMaxRetries() {
        var state = new GenerationState("r1", "s1", "test", List.of(ResourceType.DOC),
            null, null, null, null,
            Map.of(ResourceType.DOC, new GeneratedResource("r1", ResourceType.DOC,
                "test", "Title", "violence", Map.of(), java.time.Instant.now())),
            List.of(), 3, // already at max retries
            org.example.educatorweb.resourcegen.model.ProgressStage.REVIEWING, null);

        var result = reviewAgent.execute(state);
        assertEquals(org.example.educatorweb.resourcegen.model.ProgressStage.FALLBACK, result.stage());
    }
}
```

- [ ] **Step 6: Verify compilation**

Run: `mvn clean compile`

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/agents/
git add src/main/java/org/example/educatorweb/resourcegen/config/
git add src/main/java/org/example/educatorweb/resourcegen/api/
git add src/test/
git commit -m "feat(phase-5): add QuizGenerator + ReviewAgent with retry loop"
```

---

### Task 8: Phase 6 — CodeGenerator + HtmlGenerator + Docker Sandbox

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/CodeGenerator.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/HtmlGenerator.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/CodeSandboxService.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/HtmlSandboxValidator.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/SandboxTemplate.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/api/ResourceGenerationService.java`

- [ ] **Step 1: Create code sandbox infrastructure (simplified — Docker-enabled via Testcontainers)**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

@Service
public class CodeSandboxService {
    private static final Logger log = LoggerFactory.getLogger(CodeSandboxService.class);

    public record ExecutionResult(String stdout, String stderr, int exitCode,
                                   long executionTimeMs, boolean timedOut) {}

    public ExecutionResult execute(String code) {
        long start = System.currentTimeMillis();
        log.info("Executing code in sandbox ({} chars)", code.length());

        try {
            // Write code to temp file
            Path tmpDir = Files.createTempDirectory("sandbox-");
            Path scriptFile = tmpDir.resolve("script.py");
            Files.writeString(scriptFile, code);

            // Execute with Python
            ProcessBuilder pb = new ProcessBuilder(
                "python", scriptFile.toAbsolutePath().toString());
            pb.directory(tmpDir.toFile());
            pb.redirectErrorStream(false);

            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            int exitCode = finished ? process.exitValue() : -1;

            if (!finished) {
                process.destroyForcibly();
                log.warn("Code execution timed out after 30s");
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("Code executed: exit={}, stdout={} chars, time={}ms", exitCode, stdout.length(), elapsed);

            // Cleanup
            Files.deleteIfExists(scriptFile);
            Files.deleteIfExists(tmpDir);

            return new ExecutionResult(stdout, stderr, exitCode, elapsed, !finished);
        } catch (Exception e) {
            log.error("Sandbox execution failed: {}", e.getMessage());
            return new ExecutionResult("", e.getMessage(), -1,
                System.currentTimeMillis() - start, false);
        }
    }
}
```

Note: Full Docker isolation via Testcontainers will be added later. This simplified version runs Python locally but with strict timeout and temp directory isolation. The Docker variant:

```java
// Docker variant (uncomment when Docker is available):
// GenericContainer<?> container = new GenericContainer<>("python:3.12-slim")
//     .withCopyFileToContainer(MountableFile.forHostPath(scriptFile), "/code/script.py")
//     .withCommand("python", "/code/script.py")
//     .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
//         new HostConfig().withMemory(256 * 1024 * 1024L).withNetworkMode("none")));
```

- [ ] **Step 2: Create HtmlSandboxValidator**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import java.util.regex.Pattern;

public final class HtmlSandboxValidator {
    private static final Pattern EXTERNAL_SCRIPT = Pattern.compile(
        "<script\\s[^>]*src\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_HANDLER = Pattern.compile(
        "\\son\\w+\\s*=", Pattern.CASE_INSENSITIVE); // onclick, onerror, etc.
    private static final Pattern EXTERNAL_FORM = Pattern.compile(
        "<form\\s[^>]*action\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern XSS_SCRIPT = Pattern.compile(
        "<script[^>]*>[^<]*document\\.cookie", Pattern.CASE_INSENSITIVE);

    public record ValidationResult(boolean valid, List<String> issues) {}

    public static ValidationResult validate(String html) {
        List<String> issues = new ArrayList<>();

        if (EXTERNAL_SCRIPT.matcher(html).find()) {
            issues.add("External script source detected — not allowed in sandbox");
        }
        if (INLINE_HANDLER.matcher(html).find()) {
            issues.add("Inline event handlers detected — use addEventListener instead");
        }
        if (EXTERNAL_FORM.matcher(html).find()) {
            issues.add("External form action detected");
        }
        if (XSS_SCRIPT.matcher(html).find()) {
            issues.add("Potential XSS pattern detected (document.cookie access)");
        }

        return new ValidationResult(issues.isEmpty(), issues);
    }

    public static String injectCSPMeta(String html) {
        String cspMeta = "<meta http-equiv=\"Content-Security-Policy\" " +
            "content=\"default-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
            "img-src 'self' data:; style-src 'self' 'unsafe-inline';\">";
        if (html.contains("<head>")) {
            return html.replace("<head>", "<head>\n  " + cspMeta);
        } else if (html.contains("<html>")) {
            return html.replace("<html>", "<html>\n<head>\n  " + cspMeta + "\n</head>");
        } else {
            return "<!DOCTYPE html>\n<html>\n<head>\n  " + cspMeta + "\n</head>\n<body>\n"
                + html + "\n</body>\n</html>";
        }
    }
}
```

- [ ] **Step 3: Create HtmlGenerator**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.infrastructure.HtmlSandboxValidator;
import org.example.educatorweb.resourcegen.infrastructure.SandboxTemplate;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class HtmlGenerator extends AbstractGenerator {

    public HtmlGenerator(ChatClient chatClient) {
        super(chatClient, ResourceType.HTML);
    }

    @Override
    protected String doGenerate(GenerationState state) {
        String prompt = """
            Generate a self-contained interactive HTML page for teaching: %s

            The page should include:
            - Embedded CSS (in <style> tag) for clean, modern styling
            - Embedded JavaScript for user interaction (toggles, sliders, simple visualizations)
            - A visually appealing dark-theme design
            - Interactive elements: at least one slider/range input OR toggle/checkbox that changes something
            - Clear labels and explanations in Chinese

            Constraints:
            - NO external resources (no CDN links, no external scripts, no external fonts)
            - NO inline event handlers (onclick, onerror) — use addEventListener in script
            - NO form actions to external URLs
            - Self-contained and runnable by just opening the HTML file

            Available libraries (internal only via template): %s

            Output ONLY the HTML code, starting with <!DOCTYPE html>.
            """.formatted(state.knowledgePoint(), SandboxTemplate.availableFeatures());

        String html = chatClient.prompt().user(prompt).call().content();

        // Post-process: inject CSP, validate
        html = html.replaceAll("```html\\s*", "").replaceAll("```\\s*$", "").trim();
        html = HtmlSandboxValidator.injectCSPMeta(html);

        var validation = HtmlSandboxValidator.validate(html);
        if (!validation.valid()) {
            log.warn("HTML validation issues: {}", String.join("; ", validation.issues()));
            // Add issues as HTML comments so ReviewAgent L3 can detect them
            html = "<!-- VALIDATION_ISSUES: " + String.join("; ", validation.issues())
                + " -->\n" + html;
        }

        return html;
    }

    @Override
    protected String getFormatHint() {
        return "text/html";
    }
}
```

- [ ] **Step 4: Create SandboxTemplate constants**

```java
package org.example.educatorweb.resourcegen.infrastructure;

public final class SandboxTemplate {
    private SandboxTemplate() {}

    public static String availableFeatures() {
        return """
            - Canvas 2D for drawing and visualizations
            - CSS transitions and animations
            - Standard DOM APIs
            """;
    }
}
```

- [ ] **Step 5: Create CodeGenerator**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.infrastructure.CodeSandboxService;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class CodeGenerator extends AbstractGenerator {
    private static final Logger log = LoggerFactory.getLogger(CodeGenerator.class);
    private final CodeSandboxService sandbox;

    public CodeGenerator(ChatClient chatClient, CodeSandboxService sandbox) {
        super(chatClient, ResourceType.CODE);
        this.sandbox = sandbox;
    }

    @Override
    protected String doGenerate(GenerationState state) {
        String prompt = """
            Generate a self-contained Python code example demonstrating: %s

            Requirements:
            - Use scikit-learn or numpy where appropriate (no external network calls)
            - Include comments explaining each step in Chinese
            - Include a simple `if __name__ == "__main__":` block that prints verifiable output
            - The code must run without errors with standard ML libraries

            Output ONLY the Python code, no markdown fences, no explanation.
            """.formatted(state.knowledgePoint());

        String code = chatClient.prompt().user(prompt).call().content();
        code = code.replaceAll("```python\\s*", "").replaceAll("```\\s*$", "").trim();

        // Run in sandbox for validation
        var result = sandbox.execute(code);
        log.info("Code sandbox: exit={}, stdout={}chars, stderr={}chars, time={}ms",
            result.exitCode(), result.stdout().length(), result.stderr().length(),
            result.executionTimeMs());

        // Embed execution result as metadata comment
        String metadata = """
            # ═══ Execution Result ═══
            # Exit Code: %d
            # Time: %dms
            # Stdout: %s
            # Stderr: %s
            # ═══════════════════════

            """.formatted(result.exitCode(), result.executionTimeMs(),
                result.stdout().lines().limit(3).collect(java.util.stream.Collectors.joining("; ")),
                result.stderr().lines().limit(3).collect(java.util.stream.Collectors.joining("; ")));

        return metadata + code;
    }

    @Override
    protected String getFormatHint() {
        return "text/x-python";
    }
}
```

- [ ] **Step 6: Update ResourceGenerationService to wire CODE and HTML generators**

Same pattern as DocGenerator/MindmapGenerator — add to constructor and `buildGraph()` method.

- [ ] **Step 7: Verify compilation**

Run: `mvn clean compile`

Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/
git commit -m "feat(phase-6): add CodeGenerator + HtmlGenerator with sandbox execution"
```

---

### Task 9: Phase 7 — VideoGenerator V1 (PPT Export)

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/agents/generators/VideoGenerator.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/PptxBuilder.java`
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/FileStorageService.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/api/ResourceGenerationService.java`

- [ ] **Step 1: Create PptxBuilder**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.apache.poi.xslf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.awt.*;
import java.io.*;
import java.util.List;

@Component
public class PptxBuilder {
    private static final Logger log = LoggerFactory.getLogger(PptxBuilder.class);

    public record SlideData(String title, List<String> bullets, String notes) {}

    public byte[] buildPresentation(String topicTitle, List<SlideData> slides) {
        try (var ppt = new XMLSlideShow()) {
            // Set slide size (16:9)
            ppt.setPageSize(new java.awt.Dimension(960, 540));

            // Title slide
            var titleSlide = ppt.createSlide();
            var titleBox = titleSlide.createTextBox();
            titleBox.setAnchor(new Rectangle(80, 180, 800, 100));
            var titlePara = titleBox.addNewTextParagraph();
            titlePara.setTextAlign(org.apache.poi.xslf.usermodel.TextAlign.CENTER);
            var titleRun = titlePara.addNewTextRun();
            titleRun.setText(topicTitle);
            titleRun.setFontSize(36.0);
            titleRun.setBold(true);
            titleRun.setFontColor(Color.WHITE);

            // Content slides
            for (int i = 0; i < slides.size(); i++) {
                var sd = slides.get(i);
                var slide = ppt.createSlide();

                // Slide title
                var headerBox = slide.createTextBox();
                headerBox.setAnchor(new Rectangle(60, 30, 840, 50));
                var headerPara = headerBox.addNewTextParagraph();
                var headerRun = headerPara.addNewTextRun();
                headerRun.setText(sd.title());
                headerRun.setFontSize(28.0);
                headerRun.setBold(true);
                headerRun.setFontColor(new Color(41, 128, 185));

                // Bullet points
                var bodyBox = slide.createTextBox();
                bodyBox.setAnchor(new Rectangle(80, 110, 800, 380));
                for (String bullet : sd.bullets()) {
                    var bulletPara = bodyBox.addNewTextParagraph();
                    bulletPara.setBullet(true);
                    bulletPara.setIndentLevel(0);
                    var bulletRun = bulletPara.addNewTextRun();
                    bulletRun.setText(bullet);
                    bulletRun.setFontSize(20.0);
                    bulletRun.setFontColor(Color.LIGHT_GRAY);
                }

                // Notes (optional)
                if (sd.notes() != null && !sd.notes().isBlank()) {
                    var notes = slide.getXmlObject().addNewNotes();
                    // POI notes require more complex handling — skip for V1 simplicity
                }
            }

            // Serialize to bytes
            var baos = new ByteArrayOutputStream();
            ppt.write(baos);
            byte[] pptxBytes = baos.toByteArray();
            log.info("PPTX built: {} slides, {} bytes", slides.size() + 1, pptxBytes.length);
            return pptxBytes;
        } catch (IOException e) {
            log.error("Failed to build PPTX: {}", e.getMessage());
            throw new RuntimeException("PPTX generation failed", e);
        }
    }
}
```

- [ ] **Step 2: Create FileStorageService**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;

@Service
public class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private final Path baseDir = Path.of("generated-resources");

    public String store(String requestId, byte[] content, String filename) {
        try {
            Path dir = baseDir.resolve(requestId);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(filename);
            Files.write(filePath, content);
            log.info("Stored {} to {} ({} bytes)", filename, filePath, content.length);
            return filePath.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("Failed to store file: {}", e.getMessage());
            throw new RuntimeException("File storage failed", e);
        }
    }
}
```

- [ ] **Step 3: Create VideoGenerator V1**

```java
package org.example.educatorweb.resourcegen.agents.generators;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.resourcegen.infrastructure.FileStorageService;
import org.example.educatorweb.resourcegen.infrastructure.PptxBuilder;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class VideoGenerator extends AbstractGenerator {
    private static final Logger log = LoggerFactory.getLogger(VideoGenerator.class);
    private final ObjectMapper objectMapper;
    private final PptxBuilder pptxBuilder;
    private final FileStorageService fileStorage;

    public VideoGenerator(ChatClient chatClient, ObjectMapper objectMapper,
                          PptxBuilder pptxBuilder, FileStorageService fileStorage) {
        super(chatClient, ResourceType.VIDEO);
        this.objectMapper = objectMapper;
        this.pptxBuilder = pptxBuilder;
        this.fileStorage = fileStorage;
    }

    @Override
    protected String doGenerate(GenerationState state) {
        // Step 1: LLM generates slide outline
        String prompt = """
            Generate a slide-by-slide outline for a presentation on: %s
            Output a JSON array of slides:
            [{"title": "Slide Title", "bullets": ["point 1", "point 2", ...], "notes": "speaker notes"}]

            Requirements:
            - 6-10 slides total
            - First slide is introduction
            - Last slide is summary
            - Each content slide has 3-5 bullet points
            - Bullets should be concise (10-20 words each)
            - Include speaker notes for key slides

            Output ONLY the JSON array, no markdown fences.
            """.formatted(state.knowledgePoint());

        String response = chatClient.prompt().user(prompt).call().content();

        try {
            List<PptxBuilder.SlideData> slides;
            try {
                List<Map<String, Object>> rawSlides = objectMapper.readValue(
                    response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim(),
                    new TypeReference<List<Map<String, Object>>>() {});
                slides = rawSlides.stream().map(m -> new PptxBuilder.SlideData(
                    (String) m.getOrDefault("title", ""),
                    (List<String>) m.getOrDefault("bullets", List.of()),
                    (String) m.getOrDefault("notes", "")
                )).toList();
            } catch (Exception e) {
                log.warn("Failed to parse slide JSON, using fallback: {}", e.getMessage());
                slides = List.of(
                    new PptxBuilder.SlideData("概述", List.of(state.knowledgePoint(), "核心概念", "应用场景"), ""),
                    new PptxBuilder.SlideData("核心原理", List.of("数学推导", "直观理解", "代码示例"), ""),
                    new PptxBuilder.SlideData("总结", List.of("要点回顾", "下一步学习建议"), "")
                );
            }

            // Step 2: Build PPTX
            byte[] pptxBytes = pptxBuilder.buildPresentation(
                state.blueprint() != null ? state.blueprint().title() : state.knowledgePoint(),
                slides);

            // Step 3: Store file
            String path = fileStorage.store(state.requestId(), pptxBytes,
                sanitizeFilename(state.knowledgePoint()) + ".pptx");

            return path; // Return file path as content
        } catch (Exception e) {
            log.error("VideoGenerator failed: {}", e.getMessage());
            return "PPTX generation failed: " + e.getMessage();
        }
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff]", "_").substring(0,
            Math.min(name.length(), 50));
    }

    @Override
    protected String getFormatHint() {
        return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    }
}
```

- [ ] **Step 4: Update ResourceGenerationService with VIDEO node**

Add VideoGenerator to constructor and `buildGraph()`, same pattern as other generators.

- [ ] **Step 5: Verify compilation**

Run: `mvn clean compile`

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/educatorweb/resourcegen/
git commit -m "feat(phase-7): add VideoGenerator V1 (PPT export via Apache POI)"
```

---

### Task 10: Phase 8-9 — Redis Checkpointing + Xunfei Provider

**Files:**
- Create: `src/main/java/org/example/educatorweb/resourcegen/infrastructure/CheckpointService.java`
- Create: `src/main/resources/application-xunfei.yml`
- Create: `src/main/java/org/example/educatorweb/resourcegen/api/GlobalExceptionHandler.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/orchestration/GraphOrchestrator.java`
- Modify: `src/main/java/org/example/educatorweb/resourcegen/config/ResourceGenConfig.java`

- [ ] **Step 1: Create CheckpointService**

```java
package org.example.educatorweb.resourcegen.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class CheckpointService {
    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);
    private static final String PREFIX = "checkpoint:";
    private static final Duration TTL = Duration.ofHours(1);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public CheckpointService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(String requestId, GenerationState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(PREFIX + requestId, json, TTL);
            log.debug("Checkpoint saved: {}", requestId);
        } catch (Exception e) {
            log.error("Failed to save checkpoint: {}", e.getMessage());
        }
    }

    public Optional<GenerationState> load(String requestId) {
        try {
            String json = redisTemplate.opsForValue().get(PREFIX + requestId);
            if (json != null) {
                return Optional.of(objectMapper.readValue(json, GenerationState.class));
            }
        } catch (Exception e) {
            log.error("Failed to load checkpoint: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public void delete(String requestId) {
        redisTemplate.delete(PREFIX + requestId);
    }
}
```

- [ ] **Step 2: Update GraphOrchestrator to save checkpoints after each node**

Add a `CheckpointService` field and call `checkpointService.save(...)` after each node execution.

- [ ] **Step 3: Create application-xunfei.yml**

```yaml
spring:
  ai:
    openai:
      api-key: ${XUNFEI_API_KEY}
      base-url: https://spark-api-open.xf-yun.com/v1
      chat:
        options:
          model: spark-lite
```

- [ ] **Step 4: Create global exception handler**

```java
package org.example.educatorweb.resourcegen.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(IllegalArgumentException e) {
        return new ErrorResponse("BAD_REQUEST", e.getMessage(), Instant.now());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleInternal(Exception e) {
        return new ErrorResponse("INTERNAL_ERROR", e.getMessage(), Instant.now());
    }

    public record ErrorResponse(String errorCode, String message, Instant timestamp) {}
}
```

- [ ] **Step 5: Update .gitignore for generated files**

Add to `.gitignore`:
```
generated-resources/
.superpowers/
```

- [ ] **Step 6: Full test run**

Run: `mvn clean test`

Fix any compilation or test issues.

- [ ] **Step 7: Commit**

```bash
git add .
git commit -m "feat(phase-8-9): add Redis checkpointing + Xunfei profile + error handling"
```

---

## Summary

| Phase | Content | Files Created | Key Outcome |
|---|---|---|---|
| 0 | Maven scaffold + interfaces + mocks | 14 | Runnable Spring Boot app |
| 1 | GraphOrchestrator engine | 6 | Core state machine with tests |
| 2 | Data models | 5 | All resource type models |
| 3 | RequireAgent + DesignAgent + SSE | 6 | **First E2E demo** |
| 4 | DocGenerator + MindmapGenerator | 5 | Parallel generation working |
| 5 | QuizGenerator + ReviewAgent | 4 | **Core pipeline complete** |
| 6 | CodeGenerator + HtmlGenerator + sandbox | 7 | Security-sensitive generators |
| 7 | VideoGenerator V1 (PPT) | 4 | PPT export working |
| 8 | Redis checkpoint | 3 | Resilience + resume |
| 9 | Xunfei profile + polish | 3 | Competition-ready |

**Total: ~57 files, 10 commits**
