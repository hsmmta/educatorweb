# KgBuildAgent v2 — 三分离构建 + 知识点去重

> **Date**: 2026-06-18
> **Scope**: 修复 v1 生成数量少、跨层无关系的问题。课程/资源用真实数据直接写入，知识点 LLM 逐课程生成并去重。

---

## 1. 问题

v1 的 `buildFull()` 只生成 18 知识点、3 课程、3 资源，且三层完全割裂（无 `CONTAINS_KNOWLEDGE` / `HAS_RESOURCE` 关系）。根因：

- 课程和资源让 LLM 凭空编造，每次只出 1-3 个
- 知识点按抽象 topic 生成，跟具体课程/资源脱节
- 没有去重机制

---

## 2. 目标

| 指标 | v1 | v2 目标 |
|------|-----|---------|
| 知识点 | 18 | ≥100 |
| 课程 | 3 | ≥20 |
| 学习资源 | 3 | ≥30 |
| 跨层关系 | 无 | Course↔KP, KP↔Resource |
| 数据来源 | LLM 编造 | 真实仓库数据 + LLM 分析 |

---

## 3. 新构建流程（4 阶段）

### Phase 1: 课程节点（直接提取，不调 LLM）

```
遍历 Qdrant kg_references 所有 chunks
→ 按 source 字段分组（ml-for-beginners, ml-from-scratch...）
→ 每个 chunk 的 title 作为课程/模块名
→ 直接 MERGE 到 Neo4j (:Course {id, name, institution, duration, type, rating, description})
```

### Phase 2: 资源节点（直接提取 + 硬编码清单）

```
来源1: chunks 中引用的 GitHub 仓库 URL
→ 每个 chunk 的 docId 对应一个 ML 算法实现
→ MERGE (:LearningResource {id, title, type:"CODE", url, description})

来源2: 经典教材/论文/课程清单（硬编码常量，从 dev-docs/ML-KG-GitHub-Resources.md 提取）
→ 周志华《机器学习》、李航《统计学习方法》、CS229、Coursera ML...
→ MERGE (:LearningResource {id, title, type, url})

不调 LLM，所有信息都是确定的。
```

### Phase 3: 知识点 + 跨层关系（LLM，逐课程 + 去重清单）

```
维护全局 Set<String> knownPointIds ← Neo4j 已有知识点 ID

for each course in Neo4j:
  ① embed(course.name) → Qdrant 检索 topK=3 相关 chunk
  ② prompt = """
     课程: {course.name}
     已有知识点清单: {knownPointIds}
     参考文档: {chunk.text}

     请列出这门课程涉及的知识点。
     如果某知识点已在清单中，标记 "existing": true。
     对于新知识点，提供
       id, name(Chinese), category, difficulty(1-5), description,
       prerequisites(已有知识点id列表), relatedConcepts(已有知识点id列表),
       resourceIds(关联资源id列表)

     输出 JSON 数组，共 5-10 个知识点（含已有+新增）。
     """
  ③ LLM 返回 → 新增的写 Cypher MERGE → 加入 knownPointIds
  ④ 对每个知识点，写入关系:
       (kp)-[:BELONGS_TO]->(course)
       (kp)-[:HAS_RESOURCE]->(resource)
       (kp)-[:REQUIRES]->(prereq)
       (kp)-[:RELATED_TO]->(related)
```

### Phase 4: 全局校对（可选，后续实现）

- 检查所有 Course 都有 CONTAINS_KNOWLEDGE
- 检查所有 Resource 都有 HAS_RESOURCE（通过 KP）
- 生成统计报告

---

## 4. 数据源扩展

YAML 配置增加 Awesome-List 仓库：

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
      - url: https://github.com/josephmisiti/awesome-machine-learning
        name: awesome-ml
        type: awesome-list           # 新增类型
      - url: https://github.com/ChristosChristofidis/awesome-deep-learning
        name: awesome-dl
        type: awesome-list
```

Awesome-List 类型仓库解析：README 中提取 https://github.com/* 链接，每个链接作为一个资源 chunk。

---

## 5. 改动文件

| 文件 | 改动 |
|------|------|
| `KgBuildAgent.java` | 重写 `buildFull()`：Phase1/Phase2 提取课程+资源直接写；Phase3 逐课程 LLM 生成+去重 |
| `KgNodeBuilder.java` | 改 prompt：课程名 + 已知清单 + 参考 → 5-10 知识点（标注 existing/new） |
| `application.yml` | 加 2 个 awesome-list 源 |
| `GitHubRepoSource.java` | 支持 `awesome-list` 类型：README 提取 GitHub 链接 |

---

## 6. 验证

- `curl -X POST /api/kg/build?mode=full` → 返回 `knowledgePoints >= 100, courses >= 20, resources >= 30`
- Neo4j 查询 `MATCH (c:Course)-[:CONTAINS_KNOWLEDGE]->(kp) RETURN count(*)` > 0
- Neo4j 查询 `MATCH (kp)-[:HAS_RESOURCE]->(r) RETURN count(*)` > 0
- 同一个知识点 `svm` 被多门课通过 BELONGS_TO 关联，验证去重生效
