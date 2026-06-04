# 多模态资源生成模块 — 设计规格说明书

> **项目**: 个性化多模态学习资源生成与管理系统  
> **模块**: 多智能体协同资源生成 (multi-resource-generate)  
> **负责人**: hsmmta  
> **日期**: 2026-06-04  
> **参考项目**: [OpenMAIC (THU-MAIC)](https://github.com/THU-MAIC/OpenMAIC)

---

## 1. 概述

本模块负责依据学生6维画像，通过多智能体协同，自动生成6种类型的个性化多模态学习资源。核心设计借鉴 OpenMAIC 的两阶段生成流（Outline → Scenes Expansion）和 LangGraph 状态机编排模式，在 Java 生态中以 Spring AI + 轻量自研 GraphOrchestrator 实现。

### 1.1 核心指标

| 指标 | 目标 |
|---|---|
| 资源类型 | ≥6种 |
| 文档/思维导图生成 | ≤30秒 |
| 视频/动画生成 | ≤3分钟 (V1 PPT <10秒) |
| 内容相关性 | 人工审核通过率 ≥90% |
| 流式反馈 | 生成全过程 SSE 推送进度 |
| 安全 | 4层质检，不良内容不触达用户 |

---

## 2. 技术选型

| 组件 | 选型 | 理由 |
|---|---|---|
| 后端框架 | Spring Boot 3 + WebFlux | 生态成熟，响应式支持，中文资料多 |
| LLM 接入 | Spring AI | 统一抽象，配置文件切换 DeepSeek ↔ 讯飞 (比赛要求) |
| 多 Agent 编排 | 自研 GraphOrchestrator (~500行) | 借鉴 LangGraph 核心模式，避免引入重框架 |
| 并发 | CompletableFuture + Reactor Flux | 并发生成 + SSE 流式推送 |
| 知识图谱 | Neo4j (Spring Data Neo4j) | 存储课程知识结构、依赖关系 |
| 向量检索 | Qdrant | RAG 知识增强 |
| 关系数据 | MySQL (Spring Data JPA) | 资源元数据、用户账户 |
| 缓存/检查点 | Redis | 会话状态、生成检查点、临时缓存 |
| 画像存储 | mem0 | 学生6维画像持久化 |
| 代码沙箱 | Docker (testcontainers) | 代码案例安全执行 |

### 2.1 LLM 多供应商设计

```java
// application-deepseek.yml (原型开发)
spring.ai.openai.api-key: ${DEEPSEEK_API_KEY}
spring.ai.openai.base-url: https://api.deepseek.com/v1
spring.ai.openai.chat.options.model: deepseek-chat

// application-xunfei.yml (比赛正式)
spring.ai.openai.api-key: ${XUNFEI_API_KEY}
spring.ai.openai.base-url: https://spark-api-open.xf-yun.com/v1
spring.ai.openai.chat.options.model: spark-lite
```

所有 Agent 统一使用 `ChatClient`，不直接依赖具体供应商 API。

---

## 3. 架构设计

### 3.1 分层架构

```
┌────────────────────────────────────────────┐
│  API Layer: ResourceGenerationController   │
│  POST /api/generate (SSE Flux<ProgressEvent>)│
├────────────────────────────────────────────┤
│  Orchestration: GraphOrchestrator           │
│  (GenerationGraph, AgentNode, Router)       │
├────────────────────────────────────────────┤
│  Agents (4) + Generators (6)               │
│  RequireAgent → DesignAgent →              │
│  [DocGen|MindmapGen|QuizGen|VideoGen|      │
│   CodeGen|HtmlGen] → ReviewAgent           │
├────────────────────────────────────────────┤
│  Infrastructure: Spring AI ChatClient      │
│  RAG(Qdrant) / KG(Neo4j) / Profile(mem0)  │
└────────────────────────────────────────────┘
```

### 3.2 核心抽象 — GraphOrchestrator

借鉴 OpenMAIC 的 LangGraph 设计，提炼三个核心接口：

```java
// 1. Agent节点 — 接收状态，返回新状态（不可变）
@FunctionalInterface
interface AgentNode {
    GenerationState execute(GenerationState state);
}

// 2. 条件路由器 — 决定下一节点
@FunctionalInterface
interface Router {
    String route(GenerationState state);
}

// 3. 图定义 — 声明式构建流水线
class GenerationGraph {
    Map<String, AgentNode> nodes;        // 节点注册表
    Map<String, Router> routers;         // 条件路由表
    Map<String, List<String>> edges;     // 固定边: A→B
    Map<String, List<String>> fanOuts;   // 扇出边: A→[B,C,D,E,F] 并发

    GenerationGraph node(String name, AgentNode agent) { ... }
    GenerationGraph edge(String from, String to) { ... }
    GenerationGraph fanOut(String from, List<String> toNodes) { ... }
    GenerationGraph router(String node, Router router) { ... }
    GenerationGraph build() { ... }
}
```

### 3.3 执行流程

```
GraphOrchestrator.run(initialState):
  while currentNode != "DONE" and "FALLBACK":
    if fanOuts.containsKey(currentNode):
      → CompletableFuture.allOf(并发执行所有子节点)
      → 合并结果到 state
    else:
      → nodes[currentNode].execute(state)
    → emit ProgressEvent (SSE)
    → save checkpoint to Redis
    → currentNode = route(state)
```

### 3.4 OpenMAIC 概念映射

| OpenMAIC (LangGraph) | 本模块 (Spring) | 说明 |
|---|---|---|
| StateGraph | GenerationGraph | 图定义 |
| TypedState | GenerationState (record) | 状态对象 |
| Node | AgentNode 接口 | 执行单元 |
| ConditionalEdge | Router 函数 | 条件分支 |
| Send() fan-out | CompletableFuture.allOf() | 并行扇出 |
| Checkpointer | Redis StateSnapshot | 检查点/恢复 |
| streamEvents() | Flux\<ProgressEvent\> (SSE) | 流式进度 |
| Outline → Scenes | 设计阶段 → 6路并发生成 | 两阶段模式 |

---

## 4. Agent 流水线设计

### 4.1 核心状态对象

```java
record GenerationState(
    // === 输入 ===
    String requestId,
    String studentId,
    String knowledgePoint,          // 目标知识点，如 "SVM对偶问题"
    List<ResourceType> types,       // 用户请求的资源类型

    // === 阶段1输出：设计蓝图 ===
    ResourceBlueprint blueprint,

    // === 阶段2输出：生成结果 ===
    Map<ResourceType, GeneratedResource> results,

    // === 质检 ===
    List<QualityReport> reviews,
    int reviewRetries,

    // === 进度 ===
    Progress progress,              // DESIGN / GENERATING / REVIEW / DONE
    String error
) {}
```

### 4.2 四 Agent 职责

#### 🎯 需求Agent (RequireAgent)
- **输入**: studentId, knowledgePoint, types
- **职责**: 
  1. 从 mem0 读取学生6维画像
  2. 查询 Neo4j 知识图谱（前置知识、相关概念、依赖）
  3. 查询 Qdrant RAG（检索相关教材/论文段落）
  4. 组装增强后的需求上下文
- **输出**: 更新 state，填充画像、图谱、RAG 上下文

#### 🎨 设计Agent (DesignAgent)
- **职责**:
  1. LLM 分析需求和画像
  2. 生成 `ResourceBlueprint`（统一大纲）
  3. 为每种资源类型规划子结构（章节、层级、难度分布）
- **输出**: state.blueprint

#### ⚡ 生成Agent组 (6个并行 Generator)
每个 Generator 实现 `AgentNode` 接口，接收 state，独立生成一种资源。

| Generator | 核心实现 | 后处理 | 预估耗时 |
|---|---|---|---|
| DocGenerator | LLM→Markdown，RAG 增强 | Markdown→HTML, LaTeX 渲染 | ~15s |
| MindmapGenerator | LLM→Mermaid 语法 | Mermaid→SVG | ~10s |
| QuizGenerator | LLM→结构化 JSON (题型/难度分布) | 答案校验 | ~20s |
| VideoGenerator | LLM→PPT 大纲→Apache POI (V1) | .pptx 输出 | ~5s (V1) |
| CodeGenerator | LLM→Python 代码 | Docker 沙箱执行验证 | ~25s |
| HtmlGenerator | LLM→HTML+JS | iframe 渲染+JS 错误检测 | ~20-40s |

#### ✅ 质检Agent (ReviewAgent)
4层防护：

| 层级 | 机制 | 拦截内容 |
|---|---|---|
| L1 | 关键词/正则过滤 (毫秒级) | 敏感词、违规内容 |
| L2 | LLM 独立 prompt 评审 | 准确性、相关性、完整性 |
| L3 | 执行验证 (代码沙箱/HTML渲染) | 代码可运行性、JS 错误 |
| L4 | 低置信度标记 "待审核" | 人工最终审核 |

质检失败策略：retry ≤3 次，超过降级标记低质量但返回。

### 4.3 生成图定义

```java
GenerationGraph build() {
    return new GenerationGraph()
        // 阶段1 — 串行
        .node("REQUIRE", requireAgent)
        .node("DESIGN", designAgent)
        .edge("REQUIRE", "DESIGN")

        // 阶段2 — 6路并发
        .fanOut("DESIGN", List.of(
            "GEN_DOC", "GEN_MINDMAP", "GEN_QUIZ",
            "GEN_VIDEO", "GEN_CODE", "GEN_HTML"))
        .node("GEN_DOC", docGenerator)
        // ... 其余5个同理

        // 阶段3 — 收敛到质检
        .fanIn(List.of("GEN_DOC",...,"GEN_HTML"), "REVIEW")
        .node("REVIEW", reviewAgent)

        // 条件路由
        .router("REVIEW", state -> {
            if (state.reviews().allPassed()) return "DONE";
            if (state.reviewRetries() < 3)    return "DESIGN"; // 反馈重试
            return "FALLBACK";
        })
        .build();
}
```

---

## 5. 个性化策略 (6维画像映射)

| 画像维度 | 影响生成参数 |
|---|---|
| D1 知识基础 | 内容深度和前置知识补充量。初级→解释基础概念，熟练→简洁+扩展 |
| D2 认知风格 | 知识呈现方式。理论型→公式推导多，直觉型→可视化类比多 |
| D3 错误模式 | 练习题侧重点和陷阱设计。如常犯"过拟合理解错误"→多出辨析题 |
| D4 学习节奏 | 内容分段粒度。慢速→段落更短+小结，快速→合并段落 |
| D5 内容偏好 | 优先生成的资源类型。视频型学生→首先生成视频，补充文档 |
| D6 目标导向 | 内容侧重。考试型→多练习题+考点总结，求职型→多实战项目 |

---

## 6. 模块接口

### 6.1 对外唯一入口

```java
interface ResourceGenerationService {
    Flux<ProgressEvent> generate(GenerateRequest req);
}

record GenerateRequest(
    String studentId,
    String knowledgePoint,
    List<ResourceType> types    // 用户可选，空=全部生成
) {}
```

### 6.2 依赖的外部模块接口

```java
// 画像模块 — mem0
interface ProfileService {
    StudentProfile getProfile(String studentId);
}

// 知识图谱 — Neo4j
interface KnowledgeGraphService {
    KnowledgeContext queryContext(String knowledgePoint);
    // 返回：前置知识列表、后继知识列表、相关概念、难度等级
}

// RAG模块 — Qdrant
interface RagService {
    List<DocumentSnippet> retrieve(String query, int topK);
}
```

### 6.3 输出给其他模块

```java
record GeneratedResource(
    String resourceId,
    ResourceType type,
    String knowledgePoint,
    String title,
    String content,        // Markdown/JSON/Mermaid/HTML
    Map<String, Object> metadata,
    QualityReport quality,
    Instant createdAt
) {}
```

- 学习路径模块根据知识节点拉取对应的生成资源
- 资源元数据写入 MySQL，文件内容存入文件存储

---

## 7. 安全设计

### 7.1 交互式 HTML 沙箱
- `<iframe sandbox="allow-scripts allow-same-origin">` 隔离
- Content-Security-Policy 头限制外部资源
- 质检 Agent 额外做 XSS/注入扫描
- 预设模板约束 LLM 输出结构

### 7.2 代码执行沙箱
- Docker 容器隔离 (testcontainers)
- 执行超时 30 秒
- 无网络访问权限
- 内存限制 256MB

### 7.3 内容安全
- L1 关键词过滤 + L2 LLM 评审双保险
- 所有生成内容写入前必经质检 Agent

---

## 8. 包结构

```
org.example.educatorweb
├── resourcegen                     ← 本模块
│   ├── orchestration               ← 编排引擎
│   │   ├── GraphOrchestrator.java
│   │   ├── GenerationGraph.java
│   │   ├── AgentNode.java
│   │   └── Router.java
│   ├── agents                      ← Agent实现
│   │   ├── RequireAgent.java
│   │   ├── DesignAgent.java
│   │   ├── ReviewAgent.java
│   │   └── generators/
│   │       ├── Generator.java      ← 生成器接口
│   │       ├── DocGenerator.java
│   │       ├── MindmapGenerator.java
│   │       ├── QuizGenerator.java
│   │       ├── VideoGenerator.java
│   │       ├── CodeGenerator.java
│   │       └── HtmlGenerator.java
│   ├── model                       ← 数据模型
│   │   ├── GenerationState.java
│   │   ├── ResourceBlueprint.java
│   │   ├── GeneratedResource.java
│   │   ├── QualityReport.java
│   │   ├── ProgressEvent.java
│   │   └── ResourceType.java
│   ├── api                         ← 对外接口
│   │   ├── ResourceGenerationController.java
│   │   └── ResourceGenerationService.java
│   └── config
│       └── SpringAIConfig.java
├── profile                         ← 同学A：画像模块
├── knowledgegraph                  ← 同学B：知识图谱
├── rag                             ← 同学C：RAG检索
└── common                          ← 共享：实体定义、工具类
```

---

## 9. 视频生成渐进方案

| 版本 | 方案 | 输出 | 耗时 | 比赛阶段 |
|---|---|---|---|---|
| V1 原型 | LLM→PPT大纲→Apache POI | .pptx | ~5s | 开发期 |
| V2 动画 | LLM→场景脚本→Remotion | MP4 | ~60-90s | 赛前打磨 |
| V3 完整 | 讯飞TTS+虚拟教师+白板动画 | MP4 | ~120s | 最终展示 |

---

## 10. 实施阶段

| Phase | 内容 | 优先级 |
|---|---|---|
| P0 | Spring Boot 脚手架 + Spring AI 配置 (DeepSeek) | 必须最先 |
| P1 | GraphOrchestrator 编排引擎 (核心基础设施) | 必须最先 |
| P2 | RequireAgent + DesignAgent (串行链路) | 核心 |
| P3 | DocGenerator + MindmapGenerator (文本类，简单) | 核心 |
| P4 | QuizGenerator (结构化) | 核心 |
| P5 | ReviewAgent (质检链路) | 核心 |
| P6 | CodeGenerator + HtmlGenerator (沙箱类) | 扩展 |
| P7 | VideoGenerator V1 (PPT导出) | 扩展 |
| P8 | Redis 检查点 + 容错恢复 | 增强 |
| P9 | 接入讯飞模型 | 比赛要求 |

---

## 11. 自审清单

- [x] 无 TBD / TODO 占位符
- [x] 架构描述与功能需求一致 (对应需求文档 3.3 节)
- [x] 模块范围聚焦单一职责 (资源生成，不涉及路径规划/效果评估)
- [x] 接口边界清晰 (依赖画像/知识图谱/RAG模块的明确接口)
- [x] 安全机制分层 (4层质检 + 代码沙箱 + HTML沙箱)
- [x] 个性化策略可追溯 (6维画像→生成参数显式映射)
- [x] 视频生成采用渐进策略降低风险 (V1→V2→V3)
