# 被动画像更新 (Passive Profile Update) 设计文档

> **目标**: 基于 PersonaVLM 架构思想，从 AI 辅导对话中被动、增量地更新学生 6 维学习画像，含置信度动态调节。对话不可复用。

---

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                  AiTutorServiceImpl.chat()               │
│  用户提问 → RAG→KG→Web→LLM→回答 → storeConversation()   │
│                                            ↓             │
│                          PassiveProfileUpdateService     │
│                               .checkAndTrigger(studentId) │
│                               [@Async 异步]               │
└────────────────────────────────────┬────────────────────┘
                                     │
              ┌──────────────────────┴──────────────────────┐
              │       PassiveProfileUpdateService           │
              │                                             │
              │  1. Tracker.countUnprocessed(studentId)     │
              │     → Chroma query: timestamp > cursor      │
              │     → 去重 conversationId, count            │
              │     → count < 8? return                     │
              │                                             │
              │  2. ChromaClient 拉取全部未处理对话          │
              │                                             │
              │  3. ConversationSlicer.slice()              │
              │     → 按 conversationId 分组                │
              │     → ≤20轮: 直接作为1个切片                 │
              │     → >20轮: 调LLM检测话题边界, 按边界切     │
              │                                             │
              │  4. ProfileService.getProfile(studentId)    │
              │                                             │
              │  5. PassiveProfileUpdateAgent.update()      │
              │     → 传入: 当前画像 + 切片                  │
              │     → LLM提取每维特征 + 判断 match/conflict  │
              │     → 确定性公式调节置信度 + 特征值           │
              │                                             │
              │  6. ProfileService.updateProfile()          │
              │                                             │
              │  7. Tracker.markProcessed(studentId, maxTs) │
              │     → Redis SET cursor = maxTimestamp       │
              └─────────────────────────────────────────────┘
```

---

## 2. 新增文件清单

```
src/main/java/org/example/educatorweb/profile/passive/
├── ProcessedConversationTracker.java    -- Redis游标管理
├── ConversationSlicer.java             -- 混合切片器（会话级 + LLM语义边界）
├── PassiveProfileUpdateAgent.java      -- 增量更新智能体 (Agent 1)
└── PassiveProfileUpdateService.java    -- 编排服务（@Async入口）
```

### 修改文件

| 文件 | 改动 |
|------|------|
| `AiTutorServiceImpl.java` | `chat()` 末尾加 `passiveProfileUpdateService.checkAndTrigger(studentId)` |
| `ChromaClient.java` | 新增 `getMessagesAfterCursor(userId, cursor)` 方法（where 含 `timestamp: {$gt: cursor}`） |
| 配置类 | 新增 `@EnableAsync` + `ThreadPoolTaskExecutor` bean |

---

## 3. 组件详设

### 3.1 ProcessedConversationTracker — 已处理对话追踪

基于 **Redis 时间戳游标**，确保对话不重复使用。

```
Redis Key:   profile:cursor:{studentId}
Redis Value: "2026-07-01T15:30:00.123Z"  (ISO 8601 字符串)

默认值: "1970-01-01T00:00:00.000Z" (首次使用)
```

#### 方法

| 方法 | 说明 |
|------|------|
| `countUnprocessed(studentId): int` | Chroma where: `{userId, timestamp: {$gt: cursor}}`，去重 conversationId，返回数量 |
| `getUnprocessedConversationIds(studentId): List<String>` | 同上查询，返回 distinct conversationId 列表 |
| `markProcessed(studentId, maxTimestamp)` | `redis.opsForValue().set("profile:cursor:" + studentId, maxTimestamp)` |

#### 并发安全

Redis SET 原子操作。每个学生独立 key，无跨学生竞争。同一学生的 Async 调用天然排队（Spring Async 默认线程池足够）。

---

### 3.2 ConversationSlicer — 混合切片器

#### 切片策略

```
slice(studentId, conversationIds):
  slices = []
  for each conversationId:
    messages = chromaClient.getConversationMessages(conversationId, studentId)
    rounds = messages中role="user"的数量

    if rounds <= 20:
      slices.add(new Slice(conversationId, messages, topic=null))
      // 记录该切片中所有消息的最大 timestamp
    else:
      boundaries = detectTopicBoundaries(messages)  // LLM
      for b in boundaries:
        slices.add(new Slice(conversationId, messages[b.start:b.end], b.topic))
  return slices
```

#### Slice 数据结构

```java
record Slice(
    String conversationId,
    String text,           // 拼接后的对话文本（学生: ...\n助教: ...）
    String topic,          // LLM识别的话题标签，可为 null
    String maxTimestamp    // 切片内消息的最大 timestamp
) {}
```

#### detectTopicBoundaries — LLM 话题边界检测

Prompt 结构：

```
以下是学生与AI助教的对话记录（共N轮）。请分析对话中的话题切换点，
将对话切分为语义连贯的片段。只返回JSON数组，不要其他内容。

对话记录：
[第1轮] 学生: ...
[第1轮] 助教: ...
...

输出格式：
[{"startRound":1, "endRound":12, "topic":"Python基础语法"},
 {"startRound":13, "endRound":30, "topic":"面向对象编程"}]

规则：
- 一个片段至少包含3轮对话
- 话题切换后必须切分
- 如果全篇话题一致，返回单个片段
```

---

### 3.3 PassiveProfileUpdateAgent — 增量更新智能体

#### 与现有 ProfileExtractionAgent 的区别

| | ProfileExtractionAgent (现有) | PassiveProfileUpdateAgent (新增) |
|---|---|---|
| 输入 | 对话历史 | 当前画像(含置信度) + 单个切片 |
| 输出 | 6维特征值 + 置信度 | 6维特征值 + **judgment**(match/conflict/new/insufficient) |
| 置信度 | LLM 直接给值 | LLM 只判一致性，**Java 确定性公式**计算 |
| 使用场景 | 主动对话式构建 | 被动从辅导对话中增量更新 |

#### Prompt 设计

```
你是一个学习画像分析专家。现在需要根据学生与AI助教的**新对话片段**，
**增量更新**该学生的6维学习画像。

## 学生当前画像
- knowledge（知识基础）：{value}，置信度 {confidence}
- cognitive（认知风格）：{value}，置信度 {confidence}
- error（易错偏好）：{value}，置信度 {confidence}
- pace（学习步调）：{value}，置信度 {confidence}
- preference（内容偏好）：{value}，置信度 {confidence}
- goal（目标导向）：{value}，置信度 {confidence}

## 6维画像定义
- knowledge: 入门/薄弱/一般/熟练/优秀
- cognitive: 直觉型/分析型/视觉型/言语型
- error: 字符串数组如["概念混淆","计算粗心"]
- pace: 稳扎稳打型/快速推进型/跳跃型
- preference: type字段为"视频优先"/"文档优先"/"混合学习"；ratio为{"video":0.4,...}
- goal: 求职准备/学术深造/兴趣探索/考证通关/课程考试

## 新对话片段（话题: {topic}）
{conversationText}

## 任务
对每个维度，从新对话中提取特征，并判断与当前画像的关系：
- "match": 新证据与当前画像**一致**，互证加强
- "conflict": 新证据与当前画像**矛盾**（值不同），应质疑当前画像
- "new": 该维度之前无可靠信息（置信度<0.3或值为空），新证据是首次有效信息
- "insufficient": 本片段中**无法推断**该维度

## 输出格式（纯JSON，不要markdown）
{"knowledge":{"value":"入门","judgment":"match"},"cognitive":{"value":"分析型","judgment":"conflict"},"error":{"value":["概念混淆"],"judgment":"match"},"pace":{"value":"稳扎稳打型","judgment":"insufficient"},"preference":{"type":"视频优先","ratio":{"video":0.6,"document":0.4},"judgment":"new"},"goal":{"value":"求职准备","judgment":"match"}}
```

#### 确定性置信度调节公式（Java 侧）

```java
// 对每个维度，根据 judgment 调整：
switch (judgment) {
    case "match" ->
        // 证据一致，置信度 +0.10，上限 0.95
        profile.confidence = min(profile.confidence + 0.10, 0.95);
        // 特征值不变

    case "conflict" ->
        profile.confidence = profile.confidence - 0.15;
        if (profile.confidence > 0) {
            // 置信度仍为正，保持旧特征值，仅降低信任
            // 特征值不变
        } else {
            // 置信度跌破0，翻转：采纳新特征值，置信度 = 0.15（反向加回差值）
            profile.value = newValue;
            profile.confidence = 0.15;
        }

    case "new" ->
        profile.value = newValue;
        profile.confidence = 0.55;

    case "insufficient" ->
        // 不做任何改变
}
```

#### 多切片累积效应

同一学生的多个切片会**逐个**调用 Agent，置信度累积调节：

```
切片1: knowledge match    → confidence 0.60 → 0.70
切片2: knowledge match    → confidence 0.70 → 0.80
切片3: knowledge conflict → confidence 0.80 → 0.65 (仍>0，保持原值)
切片4: knowledge conflict → confidence 0.65 → 0.50
切片5: knowledge conflict → confidence 0.50 → 0.35
切片6: knowledge conflict → confidence 0.35 → 0.20
切片7: knowledge conflict → confidence 0.20 → 0.05
切片8: knowledge conflict → confidence 0.05 → -0.10 → 翻转！新值，置信度=0.15
```

#### 输出值归一化

LLM 输出的中文值需归一化为 DB 枚举（复用 `ChatProfileService.sanitizeProfileForDb()` 的映射逻辑，可抽取为独立工具方法）。

---

### 3.4 PassiveProfileUpdateService — 编排服务

```java
@Service
public class PassiveProfileUpdateService {

    @Async
    public void checkAndTrigger(String studentId) {
        // 1. 检查阈值
        int count = tracker.countUnprocessed(studentId);
        if (count < 8) return;

        log.info("PassiveProfileUpdate: triggering for student={}, unprocessed convs={}",
            studentId, count);

        // 2. 拉取未处理对话
        List<String> convIds = tracker.getUnprocessedConversationIds(studentId);

        // 3. 切片
        List<Slice> slices = slicer.slice(studentId, convIds);

        // 4. 当前画像
        StudentProfile profile = profileService.getProfile(studentId);
        if (profile == null) {
            profile = createEmptyProfile(studentId);
        }

        // 5. 逐切片增量更新
        String maxTimestamp = null;
        for (Slice slice : slices) {
            UpdateResult result = agent.update(profile, slice);
            applyConfidenceAdjustment(profile, result);
            sanitizeProfileForDb(profile);  // 归一化DB枚举

            if (maxTimestamp == null || slice.maxTimestamp().compareTo(maxTimestamp) > 0) {
                maxTimestamp = slice.maxTimestamp();
            }
        }

        // 6. 持久化
        profileService.updateProfile(studentId, profile);

        // 7. 标记已处理
        if (maxTimestamp != null) {
            tracker.markProcessed(studentId, maxTimestamp);
        }

        log.info("PassiveProfileUpdate: completed for student={}, slices={}, maxTs={}",
            studentId, slices.size(), maxTimestamp);
    }
}
```

---

## 4. 触发流程

```
AiTutorServiceImpl.chat()
  → storeConversation()      // 存用户问题和AI回答到Chroma
  → passiveProfileUpdateService.checkAndTrigger(studentId)  // [@Async] 异步触发
      → count < 8? 静默返回
      → count ≥ 8? 执行完整更新流程
```

- 触发入口：`AiTutorServiceImpl.chat()` 末尾加一行调用
- 异步执行：`@EnableAsync` + `ThreadPoolTaskExecutor`（core=1, max=2，足够）
- 聊天响应不受影响：用户立即拿到回答，画像更新在后台进行

---

## 5. ChromaClient 新增方法

```java
/**
 * 查询指定用户在 cursor 之后的所有消息，按 conversationId 去重。
 * where: { userId, timestamp: { $gt: cursor } }
 */
public List<String> getConversationIdsAfterCursor(String userId, String cursor) {
    ChromaGetResult result = chromaGet(Map.of(
        "$and", List.of(
            Map.of("userId", userId),
            Map.of("timestamp", Map.of("$gt", cursor))
        )
    ));
    // 提取 metadata 中的 conversationId，去重返回
}
```

Chroma 原生支持 `$gt` 操作符。现有 `chromaGet` 私有方法已支持任意 `where` 条件，直接复用。

---

## 6. 配置项

```yaml
# application.yml 新增
profile:
  passive:
    threshold: 8           # 触发阈值（未处理对话数）
    long-conversation: 20  # 长对话判定阈值（轮数）
```

---

## 7. 边界情况

| 场景 | 处理 |
|------|------|
| 学生首次使用（无画像记录） | `createEmptyProfile()` 创建默认画像，所有维度 confidence=0.50 |
| 学生首次使用（无历史 cursor） | Redis 无 key，cursor 默认 `1970-01-01T00:00:00.000Z` |
| Chroma 不可用 | `countUnprocessed` 返回 0，不触发更新，log.warn |
| LLM 调用失败 | 单个切片更新失败不阻塞其他切片，log.error 后 continue |
| 置信度调节溢出 | 硬上限 0.95，硬下限 0（跌破0触发翻转） |
| 同一学生并发触发 | `@Async` 默认线程池 + 同一 studentId 的更新天然不并发（检查在入口处） |
| Chroma 无未处理对话 | `getConversationIdsAfterCursor` 返回空列表，不更新 |

---

## 8. 与现有系统的关系

```
现有系统                              新增系统
─────────                            ────────
ProfileExtractionAgent (主动)  ≠  PassiveProfileUpdateAgent (被动)
  - 对话式引导提问                      - 从辅导对话中静默提取
  - LLM 输出置信度                      - Java 确定性公式调节置信度
  - ChatProfileService 编排             - PassiveProfileUpdateService 编排

ChatProfileService (主动构建)     PassiveProfileUpdateService (被动更新)
  - POST /api/profile/chat/*           - 无 API 端点，异步后台执行
  - 实时返回提问/总结                    - 静默更新画像，无前端交互

AiTutorServiceImpl               + 加一行触发调用
  - chat() 主流程不变                   - storeConversation 后异步触发
```

两个 Profile Agent **共存不冲突**：主动构建用 `ProfileExtractionAgent`，被动更新用 `PassiveProfileUpdateAgent`，都通过 `ProfileService.updateProfile()` 写入同一张 MySQL 表。

---

## 9. 验收标准

1. 学生在 `/chat` 中与 AI 辅导对话，画像无感知地在后台更新
2. 累计 8 个新对话后，`PassiveProfileUpdateAgent` 被触发，画像各维度置信度发生变化
3. 同一学生的对话只被用于画像更新一次（Redis cursor 验证）
4. 置信度 match → 上升，多次 conflict → 跌破 0 翻转特征值
5. 个人中心 `/profile` 页面可观察到置信度和特征值的变化
6. 长对话（>20轮）被正确切片，话题边界合理
7. Chroma 或 LLM 故障时不阻塞聊天主流程
