# Knowledge Graph + RAG — 知识图谱 & 向量检索设计

> **Date**: 2026-06-10
> **Scope**: 资源生成上下文（Phase 1）— Neo4j 知识图谱 + Qdrant 向量检索（RAG）
> **Isolation**: 所有改动严格限定在 `knowledgegraph/` 和 `rag/` 包，不修改任何外部文件
> **原则**: KG 与 RAG 完全并行、各自独立、通过统一入口合并结果

---

## 1. 目标

构建双通道检索增强生成（RAG）体系，为多模态资源生成提供可靠的知识上下文：

| 通道 | 存储 | 数据类型 | 检索方式 |
|------|------|----------|----------|
| **KG** (Neo4j) | 知识点节点 + 关系 | 结构化知识点关系 | Cypher 图遍历 |
| **RAG** (Qdrant) | 文档 chunk 向量 | 非结构化文本 | 语义相似度搜索 |

两者并行查询 → 合并结果 → 写入 Prompt `#知识图谱上下文` + `#参考资料` → DeepSeek 生成。

### 非目标（本阶段不做）

- 学习路径规划
- 前端知识图谱可视化
- LightRAG 的 global/local/hybrid 多模式查询

---

## 2. 数据模型

### 2.1 节点：`KnowledgePoint`

```java
@Node("KnowledgePoint")
public class KnowledgePoint {
    @Id
    String id;           // "svm", "linear_regression"
    String name;         // "支持向量机"
    String category;     // 数学基础 | 概念 | 算法 | 应用 | 工具
    int difficulty;      // 1-5
    String description;  // 一句话简介
    String chapter;      // "监督学习"
}
```

### 2.2 关系

| 关系类型 | 方向 | 语义 |
|---------|------|------|
| `REQUIRES` | A → B | 学 A 之前必须先学 B |
| `RELATED_TO` | A ↔ B | 相关概念（双向） |

### 2.3 `KnowledgeContext`（已存在，不动）

```java
public record KnowledgeContext(
    List<String> prerequisites,      // REQUIRES 出边
    List<String> successors,         // REQUIRES 入边
    List<String> relatedConcepts,    // RELATED_TO 双向
    int difficultyLevel
) {}
```

---

## 3. 架构 & 隔离

```
                    KnowledgeGraphService (接口, 已存在, 签名不变)
                           ▲               ▲
                           │               │
        @Profile("mock")   │               │  @Primary (mock profile 不激活时)
                           │               │
          MockKnowledgeGraphService   Neo4jKnowledgeGraphService
          (已存在, 不动)              (新增, knowledgegraph/service/)
                                           │
                    ┌──────────────────────┼──────────────────────┐
                    │                      │                      │
         KnowledgePointRepository   LlmKnowledgeExtractor   KnowledgeGraphInitializer
         (新增, SDN Cypher 查询)     (新增, DeepSeek fallback)  (新增, 种子数据)
```

- **外部零改动**：`PptGenerator`、`DesignAgent` 等通过 `KnowledgeGraphService` 接口注入，不感知底层实现切换
- **Profile 隔离**：`mock` profile 走旧 `MockKnowledgeGraphService`，非 mock 走新 `Neo4jKnowledgeGraphService`

---

## 4. 新增文件清单

```
src/main/java/org/example/educatorweb/knowledgegraph/
├── model/
│   └── KnowledgePoint.java             ✨ Neo4j 节点实体
├── repository/
│   └── KnowledgePointRepository.java   ✨ SDN Repository (带自定义 Cypher)
├── service/
│   ├── Neo4jKnowledgeGraphService.java ✨ @Primary 真实实现
│   ├── KnowledgeGraphInitializer.java  ✨ 种子数据初始化 (ApplicationReadyEvent)
│   └── LlmKnowledgeExtractor.java      ✨ DeepSeek fallback 抽取
├── config/
│   └── KnowledgeGraphConfig.java       ✨ Neo4j 配置绑定 (ModelRegistry bean 复用)
```

---

## 5. 查询流程

### 5.1 正常路径（Neo4j 命中）

```java
Neo4jKnowledgeGraphService.queryContext("svm") {
    KnowledgePoint node = repo.findById("svm");
    List<KnowledgePoint> prerequisites = repo.findPrerequisites("svm");   // Cypher: MATCH (n)-[:REQUIRES]->(p)
    List<KnowledgePoint> successors = repo.findSuccessors("svm");         // Cypher: MATCH (n)<-[:REQUIRES]-(s)
    List<KnowledgePoint> related = repo.findRelated("svm");              // Cypher: MATCH (n)-[:RELATED_TO]-(r)

    return new KnowledgeContext(
        prerequisites.stream().map(KnowledgePoint::getName).toList(),
        successors.stream().map(KnowledgePoint::getName).toList(),
        related.stream().map(KnowledgePoint::getName).toList(),
        node.getDifficulty()
    );
}
```

### 5.2 Fallback 路径（Neo4j 未命中）

```
queryContext("multi_head_attention")
  → repo.findById("multi_head_attention") → null
  → llmExtractor.extract("multi_head_attention")
      → DeepSeek.chat("multi_head_attention 的前置知识/后继知识/相关概念/难度？返回 JSON")
      → 解析为 KnowledgeContext
      → repo.save(newNode); repo.saveRelations(...)  // 自动写入 Neo4j
      → log.info("Fallback: auto-added 'multi_head_attention' to knowledge graph")
  → 返回 KnowledgeContext
```

### 5.3 种子数据初始化

```java
@EventListener(ApplicationReadyEvent.class)
void seedIfEmpty() {
    if (repo.count() > 0) return;   // 已有数据，跳过

    String json = deepSeek.chat("""
        你是《机器学习》课程专家。请生成该课程的知识图谱 JSON。
        要求：≥80 个知识点，覆盖数学基础、监督学习、无监督学习、
              深度学习、集成学习、模型评估等模块。
        输出格式: { "knowledgePoints": [{ ... }] }
        每个知识点包含: id, name, category, difficulty, description,
                       chapter, prerequisites (id列表), relatedConcepts (id列表)
    """);

    List<KnowledgePoint> points = objectMapper.readValue(json, ...);
    repo.saveAll(points);  // 节点 + 关系批量写入

    // 同时保存 JSON 到 resource 目录，方便人工审核
    log.info("Knowledge graph seeded: {} points, {} relations", ...);
}
```

---

## 6. 接口契约（不变）

```java
public interface KnowledgeGraphService {
    /**
     * @param knowledgePoint 知识点名称或 ID
     * @return 该知识点的图上下文（前驱/后继/相关概念/难度）
     */
    KnowledgeContext queryContext(String knowledgePoint);
}
```

调用方（`DesignAgent`, `PptGenerator`, 等）**完全不受影响**——接口签名和返回值均不变。

---

## 7. 错误处理

| 场景 | 策略 |
|------|------|
| Neo4j 连接失败 | `queryContext` 降级为纯 LLM fallback，日志 WARN |
| DeepSeek 超时 | Fallback 返回空 `KnowledgeContext`，不阻塞主流程 |
| 种子数据生成失败 | 日志 ERROR + 应用正常启动，查询时走单点 fallback |
| LLM 返回 JSON 解析失败 | 返回空 `KnowledgeContext` + 日志 WARN |

---

## 8. 验证方案

1. **单元测试**：`Neo4jKnowledgeGraphServiceTest` — 使用 `@DataNeo4jTest` + Testcontainers Neo4j
2. **Fallback 测试**：Mock Neo4j 返回 null，验证 LLM 抽取 + 自动写入
3. **集成测试**：启动应用，调 `/generate` API，观察 Prompt 日志中 `#知识图谱上下文` 段是否出现真实知识点名
4. **人工审核**：检查生成的 `knowledge-graph-seed.json`，确认知识点质量

---

## 9. 未来扩展点（本阶段不做）

- 学习路径规划算法（BFS/最短路径遍历 REQUIRES 图）
- 知识点按难度/类别过滤
- 图可视化 API（前端 d3.js 渲染）
- RAG 向量与图谱节点交叉引用
- LightRAG 多模式查询（local/global/hybrid/mix）

---

## 10. RAG 通道 — Qdrant 向量检索

### 10.1 依赖

`pom.xml` 新增：

```xml
<!-- Qdrant Java client -->
<dependency>
    <groupId>io.qdrant</groupId>
    <artifactId>client</artifactId>
    <version>1.12.0</version>
</dependency>
```

### 10.2 数据模型

```
Qdrant Collection: "ml_documents"
  每个 Point = 一个 chunk:
    ┌─────────────────────────────────────┐
    │ id: UUID                            │
    │ vector: float[] (embedding)         │
    │ payload:                            │
    │   ├── docId: "lecture-03.pdf"       │
    │   ├── source: "周志华《机器学习》"    │
    │   ├── title: "第6章 支持向量机"       │
    │   ├── text: "SVM的基本思想是..."      │
    │   ├── knowledgePoint: "svm"         │ ← 关联知识点
    │   └── page: 132                     │
    └─────────────────────────────────────┘
```

### 10.3 架构 & 隔离

```
                    RagService (接口, 新增 rag/ 包)
                           ▲
                           │
        @Profile("mock")   │  @Primary
                           │
          MockRagService         QdrantRagService
          (已存在, 不动)          (新增 rag/service/)
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
           QdrantClient       EmbeddingService    DocumentIngester
           (Qdrant SDK)       (向量化)             (文档入库)
```

与 KG 完全平行、独立，两个包互不依赖。

### 10.4 新增文件清单

```
src/main/java/org/example/educatorweb/rag/
├── RagService.java                     ✨ 接口（如不存在则新建）
├── model/
│   ├── DocumentSnippet.java            ← 已存在，不动
│   └── DocumentChunk.java              ✨ chunk 数据模型
├── service/
│   ├── QdrantRagService.java           ✨ @Primary 真实实现
│   ├── EmbeddingService.java           ✨ DeepSeek embedding API
│   └── DocumentIngester.java           ✨ 文档导入管线
└── config/
    └── RagConfig.java                  ✨ Qdrant 客户端配置
```

### 10.5 查询流程

```
RagService.search("支持向量机的数学推导", topK=5)
  → EmbeddingService.embed("支持向量机的数学推导")
  → QdrantClient.search(collection, vector, topK=5, filter: knowledgePoint="svm")
  → List<DocumentSnippet>
      [{ text: "SVM 通过引入核函数...", source: "周志华《机器学习》p.132", score: 0.93 }, ...]
```

### 10.6 文档入库流程

```
DocumentIngester.ingest(File pdf)
  → 提取文本 → 分块(512 tokens, overlap 50)
  → EmbeddingService.embedBatch(chunks)
  → QdrantClient.upsert(collection, points)
  → log.info("Ingested: {} chunks from {}", chunks.size(), pdf.getName())
```

### 10.7 接口契约

```java
public interface RagService {
    /**
     * @param query 查询文本
     * @param knowledgePoint 可选，限定知识点范围
     * @param topK 返回数量
     * @return 相关文档片段（含来源标注）
     */
    List<DocumentSnippet> search(String query, String knowledgePoint, int topK);

    /**
     * 批量导入文档
     * @param file 文档文件
     * @return 生成的 chunk 数量
     */
    int ingest(File file);
}
```

调用方（`PptGenerator`, `DocGenerator` 等）通过 `RagService` 接口注入，不感知底层实现。

### 10.8 Embedding 模型选择

利用你已有的 DeepSeek API Key，直接调 DeepSeek embedding 接口（与 chat 接口同 base URL）：

```
POST https://api.deepseek.com/embeddings
Authorization: Bearer ${DEEPSEEK_API_KEY}
Body: { "model": "deepseek-embedding", "input": "SVM的基本思想..." }
→ { "data": [{ "embedding": [0.01, -0.02, ...] }], "dimension": 1024 }
```

无需额外申请 API Key，与现有基础设施一致。

---

## 11. 双通道合并

资源生成时，KG 和 RAG 并行查询，合并为统一上下文：

```
PptGenerator.generate(state)
  │
  ├─→ KnowledgeGraphService.queryContext("svm")   ← Neo4j Cypher
  │       → KnowledgeContext(prerequisites, successors, related, difficulty)
  │
  ├─→ RagService.search("支持向量机", "svm", 3)    ← Qdrant 向量
  │       → List<DocumentSnippet> (相关文本片段 + 来源)
  │
  └─→ 合并写入 state.knowledgeContext() + state.ragContext()
       → DeepSeek PPT prompt:
           ## 知识图谱上下文            ← 已存在
           前置知识: 线性代数, 逻辑回归
           后续知识: 核方法, SVR
           相关概念: 核函数, KKT条件
           
           ## 参考资料                  ← RAG 通道新增
           1. [周志华《机器学习》p.132] SVM 通过引入核函数...
           2. [李航《统计学习方法》p.95] 支持向量机的基本想法...
```

---

## 12. 错误处理

| 场景 | 策略 |
|------|------|
| Neo4j 连接失败 | `queryContext` 降级为纯 LLM fallback，日志 WARN |
| Qdrant 连接失败 | `RagService.search()` 返回空列表，日志 WARN，不阻塞 |
| DeepSeek embedding 超时 | 返回空列表 + 日志 WARN |
| 文档入库失败 | 日志 ERROR，不影响已有数据 |
| KG 和 RAG 都不可用 | 空 KnowledgeContext + 空 ragContext，裸 LLM 生成 |

**核心原则**: 两个通道都是"增强"手段，坏了只是降级回到纯 LLM 模式，不影响系统可用性。

---

## 13. 完整文件变更清单

```
pom.xml                                  ← 新增 qdrant-java-client 依赖

knowledgegraph/                         ← 知识图谱通道（全新）
├── model/KnowledgePoint.java
├── repository/KnowledgePointRepository.java
├── service/Neo4jKnowledgeGraphService.java
├── service/KnowledgeGraphInitializer.java
├── service/LlmKnowledgeExtractor.java
└── config/KnowledgeGraphConfig.java

rag/                                    ← RAG 通道（全新）
├── model/DocumentChunk.java
├── service/QdrantRagService.java
├── service/EmbeddingService.java
├── service/DocumentIngester.java
└── config/RagConfig.java

common/model/RagService.java            ← 新建接口（与 KnowledgeGraphService 平行）

application.yml                         ← 新增 qdrant 配置段（1行）
.env.example                            ← Qdrant 环境变量已存在，不动
```

**不碰的文件**: 所有 `resourcegen/` 下的 Generator、Agent、Orchestrator 均不动。

---

## 14. 验证方案

1. **KG 单元测试**: `Neo4jKnowledgeGraphServiceTest` — `@DataNeo4jTest` + Testcontainers
2. **RAG 单元测试**: `QdrantRagServiceTest` — 内存 Qdrant / Testcontainers
3. **Embedding 连通性**: 发送单条文本到 DeepSeek embedding API，验证返回 1024 维向量
4. **双通道集成**: 启动应用 → 调 `/generate` → 检查 Prompt 日志中两段上下文均非空
5. **降级测试**: 停掉 Neo4j/Qdrant → 验证生成仍正常完成（裸 LLM 模式）
6. **人工审核**: 检查种子数据 `knowledge-graph-seed.json` 知识点质量
