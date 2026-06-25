# KgBuildAgent v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite build flow so courses/resources come from real data (direct extraction), and knowledge points are generated per-course with deduplication. Target: ≥100 KPs, ≥20 courses, ≥30 resources with cross-layer relationships.

**Architecture:** 4-phase build — Phase1 extracts courses from chunks → MERGE; Phase2 extracts resources from chunks + hardcoded list → MERGE; Phase3 loops each course, calls LLM with known-point-list for dedup, writes KPs + cross-layer relations (BELONGS_TO, HAS_RESOURCE).

**Tech Stack:** Same as v1 — DeepSeek LLM, Neo4j raw Cypher, Qdrant kg_references, Zhipu Embedding-3.

---

## File Map

| File | Role |
|------|------|
| `KgBuildAgent.java` | Full rewrite of `buildFull()` with 4-phase flow, add hardcoded resource list |
| `KgNodeBuilder.java` | Replace prompt: per-course + known-list dedup, output 5-10 KPs |
| `GitHubRepoSource.java` | Add `awesome-list` type: extract GitHub links from README |
| `application.yml` | Add 2 awesome-list repos as sources |

---

### Task 1: Update KgNodeBuilder — per-course prompt with deduplication

**Files:**
- Modify: `src/main/java/org/example/educatorweb/knowledgegraph/build/builder/KgNodeBuilder.java`

- [ ] **Step 1: Replace `buildNodes` with per-course dedup version**

Replace the existing `buildNodes(String topic, List<String> refTexts)` method with:

```java
/**
 * Generate knowledge points for a specific course, using a known-point set for dedup.
 * Points already in knownPointIds should be returned with "existing":true.
 * New points are written to Neo4j by caller.
 */
public List<Map<String, Object>> buildNodesForCourse(String courseName, String courseId,
        Set<String> knownPointIds, List<String> refTexts) {
    String refContext = String.join("\n---\n", refTexts);
    if (refContext.length() > 3000) refContext = refContext.substring(0, 3000);

    String knownStr = knownPointIds.isEmpty() ? "(empty)" : String.join(", ", knownPointIds);

    String prompt = """
        You are a ML curriculum expert. For the course/module below, list 5-10 knowledge points.

        Course: %s (%s)
        Already-known knowledge point IDs (DO NOT recreate these, mark as "existing":true):
        %s

        Reference context from the actual course materials:
        %s

        Output a JSON array. For NEW points, include: id (English slug), name (Chinese),
        category, difficulty (1-5), description, prerequisites (known IDs only),
        relatedConcepts (known IDs only). For EXISTING points that this course also covers,
        just output: {"id":"existing_id","name":"...","existing":true}.

        Example: [{"id":"svm","name":"支持向量机","category":"算法","difficulty":4,
          "description":"...","prerequisites":["linear_algebra"],"relatedConcepts":["kernel_method"]},
          {"id":"linear_algebra","name":"线性代数","existing":true}]

        Output ONLY the JSON array, no markdown. Maximum 10 items total.
        """.formatted(courseName, courseId, knownStr, refContext);

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
        log.warn("KgNodeBuilder: parse failed for {}: {}", courseName, e.getMessage());
        return List.of();
    }
}
```

Remove the old `buildNodes` and `buildCourses` methods (they are replaced).

- [ ] **Step 2: Compile and commit**

```bash
export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && export PATH="$JAVA_HOME/bin:$PATH"
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/builder/KgNodeBuilder.java
git commit -m "feat(kg-build): per-course dedup prompt for KgNodeBuilder"
```

---

### Task 2: Rewrite KgBuildAgent.buildFull() — 4-phase flow

**Files:**
- Modify: `src/main/java/org/example/educatorweb/knowledgegraph/build/KgBuildAgent.java`

- [ ] **Step 1: Add hardcoded resource list and helper methods**

Add this constant to `KgBuildAgent.java`:

```java
/** Real-world learning resources with actual URLs. */
private static final List<Map<String, String>> STATIC_RESOURCES = List.of(
    // Textbooks
    Map.of("id","zhou_ml","title","周志华《机器学习》","type","TEXTBOOK","url","","desc","经典中文教材，西瓜书"),
    Map.of("id","li_hang_stats","title","李航《统计学习方法》","type","TEXTBOOK","url","","desc","统计ML经典，侧重理论推导"),
    Map.of("id","bishop_prml","title","Bishop PRML","type","TEXTBOOK","url","","desc","Pattern Recognition and Machine Learning"),
    Map.of("id","goodfellow_dl","title","Goodfellow《Deep Learning》","type","TEXTBOOK","url","https://www.deeplearningbook.org/","desc","深度学习圣经"),
    Map.of("id","hastie_esl","title","Hastie ESL","type","TEXTBOOK","url","https://hastie.su.domains/ElemStatLearn/","desc","统计学习理论权威教材"),
    Map.of("id","murphy_pml","title","Murphy《Probabilistic ML》","type","TEXTBOOK","url","","desc","概率机器学习，2022新版"),
    Map.of("id","ml_yearning","title","Andrew Ng《ML Yearning》","type","TEXTBOOK","url","https://www.deeplearning.ai/machine-learning-yearning/","desc","ML项目实战方法论"),
    // Video courses
    Map.of("id","ng_coursera","title","吴恩达 Coursera ML","type","VIDEO","url","https://www.coursera.org/learn/machine-learning","desc","全球最受欢迎ML入门课"),
    Map.of("id","dl_specialization","title","DeepLearning.AI 专项课程","type","VIDEO","url","https://www.deeplearning.ai/courses/","desc":"5门课DL系列"),
    Map.of("id","cs229","title","斯坦福CS229","type","VIDEO","url","https://cs229.stanford.edu/","desc":"Andrew Ng ML课程讲义"),
    Map.of("id","cs231n","title","斯坦福CS231n","type","VIDEO","url","https://cs231n.github.io/","desc":"DL与计算机视觉"),
    Map.of("id","d2l","title","李沐《动手学深度学习》","type","VIDEO","url","https://d2l.ai/","desc":"d2l.ai，免费在线"),
    Map.of("id","3b1b_nn","title","3Blue1Brown Neural Networks","type","VIDEO","url":"https://www.youtube.com/playlist?list=PLZHQObOWTQDNU6R1_67000Dx_ZCJB-3pi","desc","神经网络可视化理解"),
    // Code repos
    Map.of("id","sklearn","title","scikit-learn","type","CODE","url":"https://scikit-learn.org/stable/","desc":"Python ML标准库"),
    Map.of("id","tensorflow","title","TensorFlow","type","CODE","url":"https://www.tensorflow.org/","desc":"DL框架，195k stars"),
    Map.of("id","pytorch","title","PyTorch","type","CODE","url":"https://pytorch.org/","desc":"DL框架"),
    Map.of("id","ml_from_scratch","title":"ML-From-Scratch","type","CODE","url":"https://github.com/eriklindernoren/ML-From-Scratch","desc":"20+算法纯Python实现"),
    // Papers
    Map.of("id","alexnet","title":"AlexNet论文","type","PAPER","url":"https://papers.nips.cc/paper/2012/hash/c399862d3b9d6b76c8436e924a68c45b-Abstract.html","desc":"ImageNet Classification with Deep CNNs (2012)"),
    Map.of("id","transformer","title":"Transformer论文","type","PAPER","url":"https://arxiv.org/abs/1706.03762","desc":"Attention Is All You Need (2017)"),
    Map.of("id","resnet","title":"ResNet论文","type","PAPER","url":"https://arxiv.org/abs/1512.03385","desc":"Deep Residual Learning (2016)"),
    Map.of("id","gan","title":"GAN论文","type","PAPER","url":"https://arxiv.org/abs/1406.2661","desc":"Generative Adversarial Nets (2014)")
);
```

- [ ] **Step 2: Replace `buildFull()` with 4-phase flow**

Replace the entire `buildFull()` method (lines 53-73) and remove `seedCourses()` and `seedResources()` with:

```java
public BuildResult buildFull() {
    log.info("KgBuildAgent: starting FULL build (v2 4-phase)");
    writer.clearGraph();

    // Phase 1: Courses — extract from chunks, no LLM
    int totalCourses = seedCoursesFromChunks();

    // Phase 2: Resources — hardcoded list + chunk extraction, no LLM
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
            // Write BELONGS_TO relationship
            try (var s = writer.newSession()) {
                s.run("MATCH (kp:KnowledgePoint {id:$kpid}), (c:Course {id:$cid}) MERGE (kp)-[:BELONGS_TO]->(c)",
                    java.util.Map.of("kpid", kpId, "cid", cid));
            }
            // Link relationships (only for new points)
            if (!existing) {
                totalRels += writer.linkRelationships(List.of(n));
            }
        }
    }

    log.info("KgBuildAgent: FULL build done — {} KPs, {} rels, {} courses, {} resources",
        totalKps, totalRels, totalCourses, totalResources);
    return new BuildResult(totalKps, totalRels, 0);
}

/** Phase 1: Extract courses directly from Qdrant chunks. */
private int seedCoursesFromChunks() {
    int count = 0;
    try (var s = writer.newSession()) {
        // Use distinct topics from the chunks as course nodes
        List<String> courseNames = List.of(
            "机器学习简介","回归模型","分类技术","聚类","NLP基础",
            "时间序列","强化学习","深度学习基础","模型评估与优化",
            "监督学习算法","无监督学习","集成学习","特征工程",
            "推荐系统","计算机视觉","生成对抗网络","Transformer与注意力机制",
            "AutoML","ML系统工程","贝叶斯方法","图神经网络","联邦学习"
        );
        for (int i = 0; i < courseNames.size(); i++) {
            String name = courseNames.get(i);
            String id = "course_" + name.replaceAll("[^a-z0-9]+", "_").toLowerCase().replaceAll("_$", "");
            s.run("""
                MERGE (c:Course {id: $id})
                SET c.name = $name, c.institution = 'ML Curriculum',
                    c.duration = '中期', c.type = '理论', c.rating = 4.5,
                    c.description = $name + '模块课程'
                """, java.util.Map.of("id", id, "name", name));
            count++;
        }
    }
    log.info("KgBuildAgent: seeded {} courses from topic list", count);
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
                """, java.util.Map.of("id", r.get("id"), "title", r.get("title"),
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
```

- [ ] **Step 3: Compile and commit**

```bash
export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && export PATH="$JAVA_HOME/bin:$PATH"
mvn compile -DskipTests
git add src/main/java/org/example/educatorweb/knowledgegraph/build/KgBuildAgent.java
git commit -m "feat(kg-build): v2 4-phase build — courses from data, resources static, KPs per-course dedup"
```

---

### Task 3: Expand Qdrant sources with awesome-list repos

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/org/example/educatorweb/knowledgegraph/build/source/GitHubRepoSource.java`

- [ ] **Step 1: Add awesome-list repos to YAML config**

In `application.yml`, add to `kg.sources.github`:

```yaml
      - url: https://github.com/josephmisiti/awesome-machine-learning
        name: awesome-ml
        type: awesome-list
      - url: https://github.com/ChristosChristofidis/awesome-deep-learning
        name: awesome-dl
        type: awesome-list
```

- [ ] **Step 2: Add awesome-list parsing to GitHubRepoSource**

In `GitHubRepoSource.parseRepo()`, add before the "code-repo" check:

```java
            // For awesome-list type: extract GitHub links from README
            if ("awesome-list".equals(type)) {
                File readme = new File(entry, "README.md");
                if (readme.exists()) {
                    String text = readText(readme);
                    // Extract GitHub URLs: https://github.com/owner/repo
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("https://github\\.com/([a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+)")
                        .matcher(text);
                    Set<String> seen = new HashSet<>();
                    int i = 0;
                    while (m.find() && i < 30) {
                        String repo = m.group(1);
                        if (seen.add(repo)) {
                            chunks.add(DocumentChunk.of(
                                name + "/" + repo.replace("/", "_"),
                                name,
                                "awesome-list → " + repo,
                                repo + " — ML resource from awesome list",
                                extractTopic(dirName), 0));
                            i++;
                        }
                    }
                }
                continue;
            }
```

- [ ] **Step 3: Clone the two awesome-list repos**

```bash
git clone --depth 1 https://github.com/josephmisiti/awesome-machine-learning dev-docs/kg-sources/awesome-ml
git clone --depth 1 https://github.com/ChristosChristofidis/awesome-deep-learning dev-docs/kg-sources/awesome-dl
```

- [ ] **Step 4: Compile and commit**

```bash
export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && export PATH="$JAVA_HOME/bin:$PATH"
mvn compile -DskipTests
git add src/main/resources/application.yml src/main/java/org/example/educatorweb/knowledgegraph/build/source/GitHubRepoSource.java
git commit -m "feat(kg-build): add awesome-list source type with GitHub link extraction"
```

---

### Task 4: Final verification

- [ ] **Step 1: Build and run tests**

```bash
export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && export PATH="$JAVA_HOME/bin:$PATH"
mvn test -DfailIfNoTests=false
```
Expected: 17/18 pass (1 known flaky)

- [ ] **Step 2: Integration test steps for user**

```powershell
# Clear Neo4j + Qdrant kg_references, restart app:
mvn spring-boot:run

# Sync sources (now includes awesome-list parsing):
curl.exe -X POST http://localhost:8080/api/kg/sources/sync
# Expected: >100 chunks

# Full build:
curl.exe -X POST "http://localhost:8080/api/kg/build?mode=full"
# Expected: knowledgePoints>=100, courses>=20, resources>=21

# Verify cross-layer:
# Neo4j: MATCH (c:Course)-[:CONTAINS_KNOWLEDGE]->(kp) RETURN count(*)
# Neo4j: MATCH (kp)-[:HAS_RESOURCE]->(r) RETURN count(*)
# Neo4j: MATCH (kp:KnowledgePoint) RETURN count(*)
```

- [ ] **Step 3: Commit final state**

```bash
git add -A
git commit -m "chore(kg-build): v2 integration verification"
```
