# STATE.md — EducatorWeb 技术全景快照

> 生成日期: 2026-07-09
> 分支: `resource-push`
> 最新提交: `3025197 fix: PreGeneratedResource requestId auto-generate UUID + push 事务隔离`
> 测试状态: 33/33 全部通过
> 前端构建: `✓ built in 969ms`

---

## 1. 核心架构与技术栈

```
┌─────────────────────────────────────────────────────────┐
│  前端 (Vite + Vue 3)                                     │
│  Element Plus / marked / mermaid / katex / PIXI 6.2.2   │
├─────────────────────────────────────────────────────────┤
│  后端 (Spring Boot 3.4 + WebFlux)                        │
│  ┌──────────┬──────────┬──────────┬──────────────────┐  │
│  │ aiTutor  │ profile  │ resourcegen│ topicpush        │  │
│  │ (AI辅导)  │ (画像)   │ (资源生成) │ (话题推送)       │  │
│  ├──────────┼──────────┼──────────┼──────────────────┤  │
│  │ learningpath│ rag   │ kg       │ tutoring         │  │
│  │ (学习路径)  │ (检索) │ (知识图谱)│ (智能辅导)       │  │
│  └──────────┴──────────┴──────────┴──────────────────┘  │
├─────────────────────────────────────────────────────────┤
│  数据层                                                   │
│  MySQL 8 (JPA) │ Neo4j (SDN) │ Redis │ Qdrant │ Chroma  │
└─────────────────────────────────────────────────────────┘
```

### 模块划分

| 模块 | 包路径 | 职责 |
|------|--------|------|
| **认证** | `controller/AuthController` | 注册、登录、JWT token |
| **用户** | `entity/User`, `Service/UserService` | 用户 CRUD |
| **AI 辅导** | `aitutor/` | 对话问答、Chroma 历史、DuckDuckGo 联网搜索 |
| **画像** | `profile/` | 6维学习画像、对话式画像构建、知识熟练度追踪 |
| **知识图谱** | `knowledgegraph/` | Neo4j 知识存储、LLM 提取、GitHub 源构建 |
| **RAG** | `rag/` | Qdrant 向量检索、Embedding (siliconflow) |
| **资源生成** | `resourcegen/` | 多智能体 DAG 管道 → DOC/QUIZ/MINDMAP/CODE/HTML/PPT/VIDEO |
| **话题推送** | `topicpush/` | SSE 话题检测 → 推送触发 → 预生成资源 |
| **学习路径** | `learningpath/` | Neo4j 路径规划 + 资源推荐 |
| **智能辅导** | `tutoring/` | 语音+AI 辅导 (与 aiTutor 互补) |

### 核心依赖

| 类别 | 技术 | 版本/备注 |
|------|------|----------|
| **框架** | Spring Boot WebFlux | 3.4.x |
| **ORM** | Spring Data JPA (Hibernate 6.6) | MySQL |
| **图数据库** | Spring Data Neo4j | bolt://localhost:7687 |
| **向量库** | Qdrant (gRPC) + Chroma (HTTP) | RAG + 对话历史 |
| **缓存** | Redis | Checkpoint (资源生成断点续传) |
| **LLM** | DeepSeek (主) + SiliconFlow/OpenAI/Xunfei | 多模型路由 |
| **视频** | Seedance (Doubao) + Seedream | 视频+图片生成 |
| **前端** | Vue 3 + Vite 8 + Element Plus | SPA |
| **Live2D** | PIXI 6.2.2 + pixi-live2d-display 0.4.0 | Cubism2 模型渲染 |
| **渲染** | marked + mermaid + katex | Markdown/导图/公式 |

---

## 2. 数据库模式 (MySQL)

### 核心表

| 表名 | 关键列 | 说明 |
|------|--------|------|
| `user` | id, phone, password, nickname, avatar | 用户认证 |
| `student_profile` | student_id (PK), **JSON 列**: D1~D6 | 6维学习画像 |
| `student_knowledge_proficiency` | (student_id, concept) PK, proficiency DECIMAL | 知识点熟练度 |
| `topic_cache` | id, user_id, topic_label, qa_text, ended_at, pushed | 话题缓存(推送原料) |
| `push_result` | id, user_id, trigger_type, **resources JSON**, created_at | 推送结果(含资源元数据+preGeneratedId) |
| `pre_generated_resource` | id, request_id, user_id, topic, resource_type, title, content MEDIUMTEXT, file_path, push_type, status, error_msg | **NEW** 预生成资源内容持久化 |
| `chat_session` | session_id (PK), student_id, status | 画像对话会话 |
| `chat_message` | id, session_id, role, content, created_at | 画像对话消息 |

### JPA 配置

```yaml
spring.jpa.hibernate.ddl-auto: update
spring.jpa.open-in-view: false    # WebFlux 下必须关闭
```

### Neo4j 节点

```cypher
(:KnowledgePoint {name, id, description, difficultyLevel})
  -[:REQUIRES]-> (:KnowledgePoint)
  -[:RELATED_TO]-> (:KnowledgePoint)
```

---

## 3. 核心业务流

### 3.1 AI 辅导问答流程

```
POST /api/tutor/chat {studentId, conversationId, question}
  │
  ├─ 1. TopicDetector: cosine 相似度检测话题切换
  │      └─ 切换? → LLM 提取话题标签 → 持久化 TopicCache → checkAndPush()
  │
  ├─ 2. RAG: Embedding → Qdrant 检索 5 条相关文档
  ├─ 3. KG:  Neo4j 查 KnowledgePoint (miss → LLM fallback 提取)
  ├─ 4. History: Chroma 查最近 4 轮对话
  ├─ 5. LLM:  DeepSeek chat (system prompt + RAG + KG + history)
  ├─ 6. Web Search: DuckDuckGo (optional)
  ├─ 7. Chroma: 存储本轮问答向量
  ├─ 8. checkAndPush()  ← 独立事务 REQUIRES_NEW, try-catch 保护
  └─ 返回 ChatResponse {answer, sources[], knowledgeContext}
```

### 3.2 话题推送 + 预生成流程 (最新)

```
触发条件:
  COUNT:   TopicCache 累积 ≥3 个未推送话题 → checkAndPush()
  SCHEDULED: 每日 18:00 cron → TopicPushScheduler.scheduledPush()

executePush(userId, triggerType):
  │
  ├─ 1. PushPriorityCalculator.prioritize(topics, userId, includeWeakness)
  │      评分: proficiencyRank * 0.7 + recencyRank * 0.3
  │      SCHEDULED 额外注入薄弱点(top-3 proficiency < 0.6)
  │
  ├─ 2. For each prioritized topic:
  │     ├─ createRecords() → PreGeneratedResource ×3 (DOC/QUIZ/MINDMAP)
  │     │    状态 GENERATING, requestId 自动生成 UUID
  │     ├─ recommendByTopic() → List<RecommendedResource> (含 preGeneratedId)
  │     ├─ startGeneration() → CompletableFuture 异步生成
  │     └─ 写入 resources JSON (带 preGeneratedId)
  │
  ├─ 3. 保存 PushResult (resources JSON)
  ├─ 4. 标记 TopicCache.pushed = true
  └─ 5. SSE broadcast PushNotification → MainLayout 铃铛 badge + push-refresh 事件

异步生成完成:
  ├─ 收集 pipeline 最终 payload (ProgressEvent.payload)
  ├─ 更新 PreGeneratedResource: content / filePath / status=READY
  └─ 文件分类存储:
       generated-resources/{userId}/topic-push/{topic}/{DOC|QUIZ|MINDMAP}/
```

### 3.3 资源生成管道 (resourcegen)

```
GenerateRequest {studentId, knowledgePoint, types}
  → ResourceGenerationService.buildGraph(types)
  → GraphOrchestrator.run(graph, initialState) → Flux<ProgressEvent>

管道拓扑:
  REQUIRE (RequireAgent: profile + KG + RAG 上下文)
    → DESIGN (DesignAgent: LLM 生成 ResourceBlueprint)
      → fanOut ┬ GEN_DOC  (DocGenerator: LLM → Markdown)
               ├ GEN_QUIZ (QuizGenerator: LLM → JSON {questions[]})
               ├ GEN_MINDMAP (MindmapGenerator: LLM → Mermaid)
               ├ GEN_CODE (CodeGenerator: LLM → Python + sandbox)
               ├ GEN_HTML (HtmlGenerator: LLM → HTML + CSP)
               ├ GEN_PPT  (PptGenerator: LLM → POI PPTX)
               └ GEN_VIDEO (VideoGenerator: DeepSeek + Seedream → FFmpeg MP4)
      → REVIEW (ReviewAgent: L1关键词过滤 + L2 LLM 审查, max 3 retries → DESIGN)

结果收集: state.results() → Map<ResourceType, GeneratedResource>
最终 SSE event: progressPercent=100, payload={DOC:{resourceId,title,content,...}, QUIZ:{...}, ...}
```

---

## 4. API 端点全景

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 → JWT |
| PUT  | `/api/auth/profile` | 更新个人信息 |
| PUT  | `/api/auth/password` | 修改密码 |

### 画像
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/profile/{studentId}` | 获取6维画像 |
| GET  | `/api/profile/{studentId}/summary` | 画像摘要(不存在不报错) |
| POST | `/api/profile/chat/start` | 开始画像构建对话 |
| POST | `/api/profile/chat/message` | 发送对话消息 |
| GET  | `/api/profile/chat/history/{sessionId}` | 对话历史 |
| GET  | `/api/profile/chat/active/{studentId}` | 活跃会话 |
| POST | `/api/profile/chat/quick-update` | 快速更新画像 |
| POST | `/api/profile/build-from-form` | 表单构建画像 |

### AI 辅导
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tutor/chat` | 发送消息，返回 ChatResponse |
| GET  | `/api/tutor/conversations?studentId=` | 对话列表 |
| GET  | `/api/tutor/conversations/{id}/messages?studentId=` | 消息历史 |

### 话题推送
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/push/subscribe?studentId=` | **SSE** 订阅推送通知 |
| GET  | `/api/push/latest?studentId=` | 最新推送 |
| GET  | `/api/push/results?studentId=` | 推送历史 |
| DELETE | `/api/push/history?studentId=` | 清空推送历史 |
| GET  | `/api/push/context?studentId=&q=` | 页面上下文(最近学过+薄弱点+补全) |
| GET  | `/api/push/path/{studentId}?target=` | 学习路径规划 |
| GET  | `/api/push/recommend/{studentId}?target=` | 今日推荐 |
| POST | `/api/push/progress/{studentId}` | 更新学习进度 |

### 资源生成
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/generate` | **SSE** 流式生成资源 |
| GET  | `/api/generate/download/{requestId}/{filename}` | 下载 PPT/VIDEO |
| POST | `/api/generate/run-code` | 沙箱执行 Python |

### 预生成资源 (NEW)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/resources/{id}` | 获取完整资源内容 |
| GET  | `/api/resources/status-check/{id}` | 轻量状态检查 (polling) |
| GET  | `/api/resources/user/{userId}` | 用户所有 READY 资源 |

### 知识图谱构建
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/kg/build` | 触发 KG 构建 |
| GET  | `/api/kg/build/status` | 构建状态 |

### RAG
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rag/ingest` | 文档摄入 |
| POST | `/api/rag/search` | 向量检索 |

---

## 5. 前端路由

```javascript
MainLayout (需登录, JWT token)
├─ /home          Home.vue          DeepSeek 风格首页
├─ /chat          Chat.vue          AI 辅导对话
├─ /thinktank     ThinkTank.vue     (预留)
├─ /tutoring      Tutoring.vue      智能辅导
├─ /learning      Learning.vue      沉浸学习(资源生成+消费)
├─ /push          ResourcePush.vue  资源推送页(双卡片+底部面板)
├─ /resource/:id  ResourceView.vue  **NEW** 预生成资源查看
├─ /profile       Profile.vue       学习画像
├─ /profile/edit  EditProfile.vue   编辑画像
├─ /profile/chat  ProfileChat.vue   对话式画像构建
/login            Login.vue
/register         Register.vue
```

### 前端组件树
```
MainLayout.vue
├─ ChatSidebar.vue (对话列表)
├─ VoiceAssistant.vue (语音助手)
│   └─ Live2DCharacter.vue (Rem Live2D 模型)
├─ Stationery3D.vue (3D 文具装饰)
└─ <router-view /> (页面内容)
```

---

## 6. 关键代码快照

### 6.1 PushTriggerService — 推送触发核心

**文件:** `src/main/java/org/example/educatorweb/topicpush/service/PushTriggerService.java`
**最后修改:** `3025197`

```java
@Service
public class PushTriggerService {
    // 依赖: cacheRepo, resultRepo, calculator, recommendService, preGenerateService
    // 核心: Sinks.Many<PushNotification> notificationSink (SSE 广播)

    @Transactional(propagation = Propagation.REQUIRES_NEW)  // 独立事务,不污染对话
    public void checkAndPush(String userId) {
        long count = cacheRepo.countByUserIdAndPushedFalse(userId);
        if (count >= 3) { executePush(userId, "COUNT"); }
    }

    @Transactional
    public void executePush(String userId, String triggerType) {
        // 1. 获取未推送话题 → 优先级排序
        // 2. For each topic:
        //    a. createRecords() → PreGeneratedResource (GENERATING, 自生成UUID)
        //    b. recommendByTopic() → 带 preGeneratedId 的推荐列表
        //    c. startGeneration() → CompletableFuture 异步生成
        // 3. 序列化 resources JSON → 保存 PushResult
        // 4. markPushed(ids) → 标记已推送
        // 5. SSE broadcast → notificationSink.tryEmitNext()
    }
}
```

### 6.2 ResourcePreGenerateService — 异步预生成引擎

**文件:** `src/main/java/org/example/educatorweb/resourcegen/api/ResourcePreGenerateService.java`

```java
@Service
public class ResourcePreGenerateService {
    // 依赖: generationService (现有管道), repo, fileStorage

    // 同步创建 GENERATING 占位记录(立即返回 IDs)
    public List<PreGeneratedResource> createRecords(userId, topic, pushType)

    // 异步: 订阅 Flux<ProgressEvent>, 收集最终 payload, 保存到 DB + 文件
    public CompletableFuture<List<PreGeneratedResource>> startGeneration(records, userId, topic, pushType)

    // 文件组织:
    // generated-resources/{userId}/topic-push/{topic}/{DOC|QUIZ|MINDMAP}/{content.md|quiz.json|content.md}
}
```

### 6.3 Live2DCharacter — Live2D 渲染组件

**文件:** `frontend/src/components/Live2DCharacter.vue`
**关键依赖:** `pixi.js@6.2.2` + `pixi-live2d-display@0.4.0`

```javascript
// 核心配置常量
const RENDER_W = 380   // 固定内部渲染尺寸 — 永不 resize
const RENDER_H = 280

// 关键修复:
// 1. 固定 canvas 尺寸 → Cubism2 裁剪蒙版 framebuffer 只分配一次
// 2. autoDensity: false → 防止 DPR resize 破坏蒙版
// 3. CSS object-fit: contain → 显示层自适应, backing store 不变
// 4. waitForModelStable() → 条件轮询(贴图 baseTexture.valid),非数帧

app = new PIXI.Application({
  width: RENDER_W, height: RENDER_H,
  backgroundAlpha: 0, antialias: true,
  resolution: Math.min(window.devicePixelRatio, 2),
  autoDensity: false,
})

// 对外 API: setExpression / setMouthOpen / playRandomMotion / startLipSync / stopLipSync
```

---

## 7. 待解决问题 (TODO)

### 7.1 高优先级

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| **P0** | `pre_generated_resource` 旧表需要 DROP | 🔴 待操作 | `ddl-auto: update` 无法删除 NOT NULL / UNIQUE 约束，用户需手动执行 `DROP TABLE IF EXISTS pre_generated_resource;` 然后重启 |
| **P1** | Live2D 偶发不完整渲染 | 🟡 待验证 | PIXI 6.2.2 + 固定 canvas + waitForModelStable; 用户需 Ctrl+Shift+R 硬刷新测试 10 次 |

### 7.2 功能缺口

| # | 问题 | 说明 |
|---|------|------|
| **GAP-1** | 资源消费页独立 | 目前 DOC 在 `/learning` 和 `/resource/:id` 均能渲染,但 QUIZ/CODE/MINDMAP 的消费体验在 `/resource/:id` 需要完善(QUIZ JSON 解析/提交答案/计分) |
| **GAP-2** | 异步生成完成通知 | 预生成完成后前端无主动通知(SSE 已就绪但未对接 `pre_generated_resource` READY 事件); 当前靠手动刷新或轮询 `status-check` |
| **GAP-3** | `recommendByTopic()` 语言不匹配 | 画像偏好存中文(`"视频优先"`)但 `recommendByTopic` 检英文(`"video"`) → VIDEO/CODE 类型永不被推荐 |
| **GAP-4** | PATH_PUSH 未实现 | 学习路径推荐的预生成接入 (`pushType="PATH_PUSH"`) |
| **GAP-5** | 主题定时推送未对接 | `TopicPushScheduler` cron 每天 18:00 触发 `executePush("SCHEDULED")`,但尚未验证端到端 |
| **GAP-6** | `contextText` 参数未使用 | `recommendByTopic(userId, topicLabel, contextText)` 的第三个参数传入但方法体内未引用,QA 文本未影响推荐结果 |

### 7.3 技术债务

| # | 问题 | 说明 |
|---|------|------|
| **TD-1** | Neo4j `id()` 弃用 | Cypher 查询中用 `id()` 函数,Neo4j 5.x 起推荐 `elementId()` |
| **TD-2** | PIXI 7 兼容性已放弃 | 因 pixi-live2d-display@0.4.0 依赖 PIXI 6 旧交互 API,锁定在 6.2.2 |
| **TD-3** | `StudentProfile` 仍是 record | 有未完成的 plan 将其改为 JPA Entity (`docs/superpowers/plans/streamed-knitting-treehouse.md`) |
| **TD-4** | JDK 25 + Mockito Byte Buddy | 编译用 JDK 25,但 Mockito Byte Buddy 不支持 → test 需 `-Dnet.bytebuddy.experimental=true` |

---

## 8. 环境变量速查

| 变量 | 用途 | 默认值 |
|------|------|--------|
| `DEEPSEEK_API_KEY` | DeepSeek LLM | `sk-placeholder` |
| `MYSQL_PASSWORD` | MySQL root 密码 | `root` |
| `NEO4J_URI` | Neo4j 连接 | `bolt://localhost:7687` |
| `NEO4J_PASSWORD` | Neo4j 密码 | `password` |
| `REDIS_PASSWORD` | Redis 密码 | (空) |
| `QDRANT_HOST` | Qdrant 向量库 | `localhost` |
| `CHROMA_URL` | Chroma 向量库 | `http://localhost:8000` |
| `SEEDANCE_API_KEY` | 视频/图片生成 | (必填) |

---

## 9. 启动命令

```bash
# 后端 (先删旧表!)
mysql -u root -p -e "DROP TABLE IF EXISTS educatorweb.pre_generated_resource;"
export JAVA_HOME=/c/Users/x/.jdks/openjdk-25.0.2
mvn spring-boot:run -Dnet.bytebuddy.experimental=true

# 前端
cd frontend && npm run dev

# 测试
mvn test -Dnet.bytebuddy.experimental=true

# 构建
npm --prefix frontend run build
```
