# KgBuildAgent — 知识图谱自主构建与维护 Agent 设计

> **Date**: 2026-06-15
> **Scope**: 知识图谱模块附加功能，管理端专属，非学生用户功能
> **核心思想**: RAG-enhanced KG construction — 从 Qdrant 检索参考数据，精准喂给 LLM，分批增量构建

---

## 1. 目标

替代当前 `KnowledgeGraphInitializer` 的"一次性硬编码 prompt"模式，构建一个自主的 KG 维护 Agent：

| 能力 | 说明 |
|------|------|
| 全量构建 | 图为空时，遍历 Qdrant 参考库，逐条检索 → LLM 生成 → 写入 Neo4j |
| 增量更新 | 新增参考数据到达后，只处理新增部分，已处理的跳过 |
| 多数据源 | GitHub 仓库（直接解析）、Awesome-List（链接递归提取，预留）、Web 搜索 API（预留） |
| 手动触发 | `POST /api/kg/build?mode=full\|incremental` |
| 定时自动 | `@Scheduled` 每周日凌晨 3 点：同步源 → 增量构建 |

---

## 2. 架构

```
┌─────────────────────────────────────────────┐
│              KgBuildAgent                    │
│  (编排全流程: fetch → process → build → write)│
└──────────────────┬──────────────────────────┘
                   │
 ┌─────────────────┼─────────────────┐
 ▼                 ▼                  ▼
 KgSourceFetcher   KgContentProcessor KgReferenceStore
 (数据源拉取)       (文本→chunk→embed)  (Qdrant kg_references)
 
触发: POST /api/kg/build       手动
      @Scheduled(每周日3AM)     自动
```

### 2.1 数据源抽象

```java
interface KgSource {
    String name();
    List<DocumentChunk> fetch();
}

// Phase 1 实现
GitHubRepoSource          — 直接仓库（目录+README）
// 预留实现
AwesomeListSource         — README → 提取链接 → fetch 各链接内容
ModuleHierarchySource     — 代码仓库模块层次解析
WebSearchSource           — Tavily API 搜索
```

### 2.2 Qdrant 参考库

独立 collection: `kg_references`

```
每个 chunk:
  id, text, source (github/url), topic/domain,
  status: new | processed | updated,
  url, metadata
```

### 2.3 存储目录

```
data/kg-sources/
├── ML-For-Beginners/        # git clone
├── ML-From-Scratch/         # git clone
├── awesome-ml/              # git clone (后续)
└── fetched/                 # HTTP fetch 下载内容 (后续)
```

---

## 3. 构建流程

### 3.1 syncSources() — 同步外部数据源

```
for each configured source:
  if GitHub repo:
    git pull/clone → data/kg-sources/
    解析:
      - 课程仓库: 目录结构 → 提取 README → chunk (max 500 chars)
      - (预留) Awesome-List: README → 正则提取链接 → 逐个 HTTP fetch
      - (预留) 代码仓库: 模块层次 → 结构化描述
    → embed → Qdrant(kg_references) status: "new"
```

### 3.2 buildFull() — 全量构建

```
前提: Neo4j 中 KnowledgePoint 节点数为 0

Qdrant(kg_references) 有 N 条
→ 遍历每条或按 topic 分组
→ 每条检索 topK=2-3 相关 chunk
→ prompt: topic + 相关参考 + 输出 1-3 个知识点 JSON
→ LLM 生成
→ Cypher MERGE 写入 Neo4j
→ 标记 status: "processed"
```

每次 LLM 调用：输入 ~1000 chars，输出 1-3 个知识点

### 3.3 buildIncremental() — 增量构建

```
query Qdrant: status = "new"
→ 只处理 M 条新增 (M << N)
→ 每条:
    retrieve(topic, topK=2)
    prompt: topic + 新增内容 + 相关参考
    output: 1-3 知识点
    write Neo4j
    mark status: "processed"
```

---

## 4. API & 定时

### 4.1 REST API

| 端点 | 说明 |
|------|------|
| `POST /api/kg/build?mode=full` | 全量重建（清空图后重建） |
| `POST /api/kg/build?mode=incremental` | 增量更新（默认，只处理新增） |
| `POST /api/kg/sources/sync` | 仅同步外部源到 Qdrant |
| `GET /api/kg/status` | 返回当前 KG 节点数 + 关系数 + 待处理数 |

### 4.2 定时任务

```java
@Component
public class KgBuildScheduler {
    @Scheduled(cron = "0 0 3 * * SUN")  // 每周日凌晨 3:00
    public void scheduledBuild() {
        kgBuildAgent.syncSources();
        kgBuildAgent.buildIncremental();
    }
}
```

### 4.3 YAML 配置

```yaml
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
    batch-size: 5      # 每批处理几个 topic
```

---

## 5. 文件清单

### 新增文件（全部在 `knowledgegraph/build/` 包下）

```
knowledgegraph/build/
├── KgBuildAgent.java              ✨ 编排器 (fetch→process→build)
├── KgBuildScheduler.java          ✨ 定时任务
├── source/
│   ├── KgSource.java              ✨ 数据源接口
│   ├── GitHubRepoSource.java      ✨ GitHub 仓库解析
│   ├── AwesomeListSource.java     🔮 预留
│   └── WebSearchSource.java       🔮 预留
├── processor/
│   ├── KgContentProcessor.java    ✨ 文本提取→chunk→embed
│   └── KgReferenceStore.java      ✨ Qdrant collection 管理
├── builder/
│   ├── KgNodeBuilder.java         ✨ LLM prompt 构建 + 生成
│   └── KgNeo4jWriter.java         ✨ Cypher 写入
├── api/
│   └── KgBuildController.java     ✨ REST API
└── config/
    └── KgBuildProperties.java     ✨ 配置绑定
```

### 修改文件

| 文件 | 改动 |
|------|------|
| `application.yml` | 新增 `kg.sources` 和 `kg.build` 配置段 |
| `KnowledgeGraphInitializer.java` | 标记 `@Deprecated` 或移除，由 KgBuildAgent 替代 |

---

## 6. 与现有模块的关系

| 现有模块 | 关系 |
|----------|------|
| `KnowledgeGraphInitializer` | **废弃**，被 KgBuildAgent 替代 |
| `QdrantRagService` | 不动。KgBuildAgent 用独立的 `kg_references` collection |
| `EmbeddingService` | **复用**，chunk 向量化 |
| `LlmKnowledgeExtractor` | 不动，保留运行时 fallback |
| `KnowledgePointRepository` | 不动，但 KgBuildAgent 用原始 Cypher 写入（避 WebFlux 事务问题） |

---

## 7. 验证方案

1. **单元测试**: `GitHubRepoSourceTest` — 解析本地仓库目录，验证 chunk 生成
2. **集成测试**: Mock Qdrant → syncSources() → 验证 chunk 存入 + status
3. **全量构建测试**: 空 Neo4j → buildFull() → 验证 78+ 节点 + 关系写入
4. **增量构建测试**: 追加一条 source → buildIncremental() → 验证只处理新增
5. **定时任务测试**: 手动触发 scheduler → 验证流程完整执行
