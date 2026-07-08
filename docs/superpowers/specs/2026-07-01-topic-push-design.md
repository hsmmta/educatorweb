# 基于话题缓存的智能资源推送 设计文档

> **日期:** 2026-07-01
> **分支:** resource-push

## 1. 目标

在现有手动资源推送基础上，增加基于"话题转变缓存"的自动推送机制，减少推送频率，提升推送相关性。

## 2. 核心机制

```
用户提问
  ↓
EmbeddingService.embed(question)  → 当前嵌入（2048-d，Zhipu Embedding-3）
  ↓
与上一条消息嵌入做 cosine 相似度
  ↓
相似度 < 0.4  →  话题转变
  ↓
LLM 给旧话题打标签（1-3 字）→ 旧 Q&A 原文 + 标签存入 topic_cache
  ↓
检查 topic_cache 未推送条数 ≥ 3 → 触发"数量推送"（Case 1）
  ↓
同时，每天 18:00 定时检查 → 触发"定时推送"（Case 2）
```

- **数量触发**：缓存积攒 ≥ 3 个话题 → 立即推送
- **定时兜底**：每天 18:00（`cron: 0 0 18 * * ?`），有缓存走缓存+薄弱点，无缓存纯薄弱点
- 推送完成后清空已推送的缓存记录

## 3. 话题检测

### 3.1 嵌入相似度判断

- 复用 `EmbeddingService`（Zhipu Embedding-3，2048 维）
- 每条消息在存入 Chroma 时已生成 embedding，话题检测用同一份
- 计算：`cosine(当前问题embedding, 上一条消息embedding)`
- 阈值：`0.4`（可配置 `topic.push.similarity-threshold`）

### 3.2 话题标签生成

判定话题转变后，将旧话题的最后一组 Q&A 发给 LLM：

```
prompt: "用1-3个词（中文）总结这段对话的核心话题，只输出话题名称：\nQ:{question}\nA:{answer}"
```

返回标签如 "梯度下降"、"SVM推导"、"Python装饰器"。

## 4. 缓存模型

### 4.1 数据库表 `topic_cache`

```sql
CREATE TABLE topic_cache (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         VARCHAR(64)   NOT NULL,
  topic_label     VARCHAR(100)  NOT NULL,
  qa_text         TEXT          NOT NULL,
  conversation_id VARCHAR(64),
  ended_at        DATETIME      NOT NULL,
  pushed          BOOLEAN       DEFAULT FALSE,
  created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_pushed (user_id, pushed),
  INDEX idx_user_ended  (user_id, ended_at)
);
```

### 4.2 JPA Entity

```java
@Entity
@Table(name = "topic_cache")
public class TopicCache {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userId;
    private String topicLabel;
    @Column(columnDefinition = "TEXT")
    private String qaText;
    private String conversationId;
    private LocalDateTime endedAt;
    private Boolean pushed = false;
    private LocalDateTime createdAt;
}
```

## 5. 优先级排序

推送时给每个话题计算优先级分数，综合两个维度：

| 维度 | 规则 | 数据来源 |
|------|------|---------|
| 掌握度 | `proficiency` 越低 → 排名越靠前（rank 值越小） | `StudentProfile.knowledgeDetails` 中匹配该话题对应知识点 |
| 时间久远度 | `ended_at` 越久远 → 排名越靠前（rank 值越小） | `topic_cache.ended_at` |

### 5.1 Case 1：数量触发

```
score = proficiency_rank × 0.7 + recency_rank × 0.3
最终按 score 升序排列（分数越低，优先级越高）
```

- `proficiency_rank`：按熟练度升序排的位次（最差的 = 1）
- `recency_rank`：按 `ended_at` 升序排的位次（最久远的 = 1）
- 如果话题标签无法在 `knowledgeDetails` 中匹配到对应知识点，`proficiency_rank` 取中位数

### 5.2 Case 2：定时触发

1. **薄弱点置顶**：从 `StudentProfile.errorPatternTags` 和 `knowledgeDetails` 中熟练度 < 0.6 的知识点生成 1-3 条薄弱点专题推荐
2. 缓存中的话题按 Case 1 规则排序，跟在薄弱点后面
3. 如果缓存为空 → 纯薄弱点推送

## 6. 推送执行

### 6.1 推送内容

对排序后的每个话题/薄弱点，调用现有的 `ResourceRecommendService` 生成推荐资源（DOC/QUIZ/CODE/MINDMAP），合并去重，整体返回给前端。

### 6.2 推送结果持久化

```sql
CREATE TABLE push_result (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         VARCHAR(64)   NOT NULL,
  trigger_type    VARCHAR(20)   NOT NULL,   -- 'COUNT' | 'SCHEDULED'
  resources       JSON          NOT NULL,   -- 推送的资源列表 JSON
  created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_created (user_id, created_at)
);
```

### 6.3 推送后清理

推送完成后，将对应 `topic_cache` 记录的 `pushed` 设为 `TRUE`（或直接 DELETE）。

## 7. 通知机制

### 7.1 SSE 端点

新增 `GET /api/push/subscribe?studentId=xxx`，SSE 格式。推送完成后服务端发送事件：

```json
{"type": "push_notification", "data": {"triggerType": "COUNT", "resourceCount": 5}}
```

### 7.2 前端通知

- `MainLayout.vue` 重新加入 `<el-badge>` 铃铛图标
- 导航栏头像区域增加通知铃铛，收到 SSE 事件后显示红点 + 弹出 `ElNotification`
- 点击铃铛跳转到 `/push` 页面

## 8. 前端 ResourcePush.vue 改造

### 8.1 页面结构

现有两栏布局（学习路径 + 推荐资源）改为三区块：

1. **顶部导航栏**：Tab 切换「手动规划」（保留现有功能）和「自动推送」（新增）
2. **手动规划 Tab**：保留现有输入框 + 生成路径按钮 + 两栏展示
3. **自动推送 Tab**：
   - 左侧：推送历史列表（从 `GET /api/push/results?studentId=xxx` 加载），按日期分组
   - 右侧：选中推送的详情 — 资源卡片列表 + 学习路径，点击资源跳转到 `/learning`
4. **通知铃铛**：`MainLayout.vue` 导航栏增加铃铛，SSE 收到事件后显示未读红点

### 8.2 配色

沿用现有设计体系：
- 白底圆角卡片 + 浅紫渐变主题（#667eea / #764ba2）
- 与 Profile、Home、Chat 页面和谐统一

## 9. 涉及文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `TopicCache.java` | **新增** | JPA Entity，topic_cache 表 |
| `TopicCacheRepository.java` | **新增** | Spring Data JPA Repository |
| `PushResult.java` | **新增** | JPA Entity，push_result 表 |
| `PushResultRepository.java` | **新增** | Spring Data JPA Repository |
| `TopicDetector.java` | **新增** | 嵌入相似度判断 + LLM 打标签 |
| `TopicPushScheduler.java` | **新增** | `@Scheduled` 定时推送 |
| `PushTriggerService.java` | **新增** | 编排推送流程（数触 + 定时） |
| `PushPriorityCalculator.java` | **新增** | 优先级排序逻辑 |
| `PushNotifyController.java` | **新增** | SSE 通知端点 |
| `PushResultController.java` | **新增** | 推送历史查询端点 |
| `AiTutorServiceImpl.java` | **修改** | 每次 chat 后调用 TopicDetector |
| `ResourceRecommendService.java` | **修改** | 增加基于话题标签的推荐方法 |
| `LearningPathService.java` | **修改** | 增加基于多话题的路径规划 |
| `MainLayout.vue` | **修改** | 恢复通知铃铛 + SSE 监听 |
| `ResourcePush.vue` | **修改** | Tab 结构 + 推送历史展示 |
| `api/index.js` | **修改** | 新增推送相关 API 调用 |
| `application.yml` | **修改** | 推送配置（阈值、cron） |

## 10. 配置项

```yaml
topic:
  push:
    similarity-threshold: 0.4    # 话题转变相似度阈值
    count-threshold: 3            # 数量触发临界值
    scheduler-cron: "0 0 18 * * ?"  # 每天 18:00
```

## 11. 错误处理

- **LLM 标签生成失败**：降级为取问题前 10 个字符作为标签
- **嵌入生成失败**：跳过本次话题检测（不影响正常聊天）
- **推送生成失败**：记录日志，不影响缓存积累
- **SSE 连接断线**：前端每隔 30 秒自动重连

## 12. 自检清单

- [x] 无 TBD/TODO
- [x] 所有表有明确字段定义
- [x] 优先级规则有明确公式
- [x] 两种触发方式覆盖所有场景（有缓存/无缓存）
- [x] 前后端数据流完整
- [x] 错误降级方案明确
- [x] 配置文件明确
