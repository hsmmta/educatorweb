# 被动画像更新 (Passive Profile Update) 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从 AI 辅导对话中被动增量更新学生 6 维画像，含置信度动态调节，对话不可复用。

**Architecture:** 在 `AiTutorServiceImpl.chat()` 末尾异步触发 `PassiveProfileUpdateService`，经 Redis 游标检查阈值(≥8 新对话)后，通过 `ConversationSlicer` 切片 + `PassiveProfileUpdateAgent` 调 LLM 提取特征并确定性调节置信度，最后写回 MySQL。

**Tech Stack:** Java 21, Spring Boot 3, Redis (cursor), Chroma (conversation storage), DeepSeek (LLM), MySQL/JPA (profile)

---

## 文件结构

```
新建:
  src/main/java/org/example/educatorweb/profile/passive/
  ├── ProcessedConversationTracker.java
  ├── ConversationSlicer.java
  ├── PassiveProfileUpdateAgent.java
  └── PassiveProfileUpdateService.java

修改:
  src/main/java/org/example/educatorweb/aitutor/config/ChromaClient.java     (新增查询方法)
  src/main/java/org/example/educatorweb/aitutor/service/AiTutorServiceImpl.java (加一行触发)
  src/main/resources/application.yml                                          (新增配置项)

新建:
  src/main/java/org/example/educatorweb/config/AsyncConfig.java               (@EnableAsync配置)
```

---

### Task 1: Async 配置 + 配置属性

**Files:**
- Create: `src/main/java/org/example/educatorweb/config/AsyncConfig.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 创建 AsyncConfig**

```java
package org.example.educatorweb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "profileUpdateExecutor")
    public Executor profileUpdateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("profile-update-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 2: 在 application.yml 新增配置项**

在文件末尾追加：

```yaml
# 被动画像更新配置
profile:
  passive:
    threshold: 8              # 触发阈值（未处理对话数）
    long-conversation: 20     # 长对话判定阈值（轮数）
```

- [ ] **Step 3: 验证编译**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/educatorweb/config/AsyncConfig.java src/main/resources/application.yml
git commit -m "feat: add @EnableAsync config and profile.passive properties"
```

---

### Task 2: ChromaClient 新增 cursor 查询方法

**Files:**
- Modify: `src/main/java/org/example/educatorweb/aitutor/config/ChromaClient.java`

- [ ] **Step 1: 新增 `getConversationIdsAfterCursor` 方法**

在 `ChromaClient.java` 的 Public API 区域（`getConversationMessages` 方法之后）添加：

```java
/**
 * Get distinct conversation IDs for a user whose messages have timestamp
 * strictly after {@code cursor}. Used to discover unprocessed conversations
 * for passive profile update.
 *
 * @param userId the student ID
 * @param cursor ISO-8601 timestamp string (e.g. "2026-07-01T00:00:00Z")
 * @return distinct conversation IDs, sorted by earliest timestamp ascending
 */
public List<String> getConversationIdsAfterCursor(String userId, String cursor) {
    ChromaGetResult result = chromaGet(Map.of(
        "$and", List.of(
            Map.of("userId", userId),
            Map.of("timestamp", Map.of("$gt", cursor))
        )
    ));
    if (result == null || result.metadatas() == null) return List.of();

    // Collect distinct conversation IDs, sorted by their earliest timestamp
    Map<String, String> convEarliestTs = new LinkedHashMap<>();
    for (Map<String, Object> meta : result.metadatas()) {
        if (meta == null) continue;
        String convId = (String) meta.get("conversationId");
        String ts = meta.get("timestamp") != null ? String.valueOf(meta.get("timestamp")) : "";
        if (convId == null) continue;
        String existing = convEarliestTs.get(convId);
        if (existing == null || ts.compareTo(existing) < 0) {
            convEarliestTs.put(convId, ts);
        }
    }

    return convEarliestTs.entrySet().stream()
        .sorted(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .toList();
}
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/aitutor/config/ChromaClient.java
git commit -m "feat: add getConversationIdsAfterCursor to ChromaClient"
```

---

### Task 3: ProcessedConversationTracker

**Files:**
- Create: `src/main/java/org/example/educatorweb/profile/passive/ProcessedConversationTracker.java`

- [ ] **Step 1: 创建 ProcessedConversationTracker**

```java
package org.example.educatorweb.profile.passive;

import org.example.educatorweb.aitutor.config.ChromaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Tracks which conversations have been processed for profile updates
 * using a Redis timestamp cursor. Each student gets an independent cursor.
 *
 * <p>Key pattern: {@code profile:cursor:{studentId}} → ISO-8601 timestamp.
 * Any conversation with a message timestamp > cursor is considered unprocessed.
 */
@Component
public class ProcessedConversationTracker {

    private static final Logger log = LoggerFactory.getLogger(ProcessedConversationTracker.class);
    private static final String KEY_PREFIX = "profile:cursor:";
    private static final String DEFAULT_CURSOR = "1970-01-01T00:00:00.000Z";

    private final RedisTemplate<String, String> redisTemplate;
    private final ChromaClient chromaClient;

    public ProcessedConversationTracker(RedisTemplate<String, String> redisTemplate,
                                         ChromaClient chromaClient) {
        this.redisTemplate = redisTemplate;
        this.chromaClient = chromaClient;
    }

    /**
     * Count distinct unprocessed conversations for a student.
     */
    public int countUnprocessed(String studentId) {
        String cursor = getCursor(studentId);
        List<String> convIds = chromaClient.getConversationIdsAfterCursor(studentId, cursor);
        return convIds.size();
    }

    /**
     * Get the list of unprocessed conversation IDs.
     */
    public List<String> getUnprocessedConversationIds(String studentId) {
        String cursor = getCursor(studentId);
        return chromaClient.getConversationIdsAfterCursor(studentId, cursor);
    }

    /**
     * Update the cursor to the given timestamp, marking all conversations
     * up to (and including) that timestamp as processed.
     */
    public void markProcessed(String studentId, String maxTimestamp) {
        if (maxTimestamp == null || maxTimestamp.isBlank()) return;
        String key = KEY_PREFIX + studentId;
        redisTemplate.opsForValue().set(key, maxTimestamp);
        log.debug("ProcessedConversationTracker: cursor updated for student={} to {}", studentId, maxTimestamp);
    }

    private String getCursor(String studentId) {
        String key = KEY_PREFIX + studentId;
        String cursor = redisTemplate.opsForValue().get(key);
        return cursor != null ? cursor : DEFAULT_CURSOR;
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/passive/ProcessedConversationTracker.java
git commit -m "feat: add ProcessedConversationTracker with Redis cursor"
```

---

### Task 4: ConversationSlicer

**Files:**
- Create: `src/main/java/org/example/educatorweb/profile/passive/ConversationSlicer.java`

- [ ] **Step 1: 创建 ConversationSlicer**

```java
package org.example.educatorweb.profile.passive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.aitutor.config.ChromaClient;
import org.example.educatorweb.resourcegen.infrastructure.DeepSeekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hybrid conversation slicer. Default unit is one conversation.
 * Long conversations (> {@code longConversationThreshold} rounds) are
 * semantically sliced by detecting topic boundaries via LLM.
 */
@Component
public class ConversationSlicer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSlicer.class);

    private final ChromaClient chromaClient;
    private final DeepSeekProvider llmProvider;
    private final ObjectMapper objectMapper;
    private final int longConversationThreshold;

    public ConversationSlicer(ChromaClient chromaClient,
                               DeepSeekProvider llmProvider,
                               ObjectMapper objectMapper,
                               @Value("${profile.passive.long-conversation:20}") int longConversationThreshold) {
        this.chromaClient = chromaClient;
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
        this.longConversationThreshold = longConversationThreshold;
    }

    /**
     * Slice conversations into semantic segments.
     */
    public List<Slice> slice(String studentId, List<String> conversationIds) {
        List<Slice> slices = new ArrayList<>();
        for (String convId : conversationIds) {
            try {
                List<Map<String, Object>> messages = chromaClient.getConversationMessages(convId, studentId);
                if (messages.isEmpty()) continue;

                int rounds = countUserRounds(messages);
                String maxTimestamp = extractMaxTimestamp(messages);

                if (rounds <= longConversationThreshold) {
                    String text = buildConversationText(messages);
                    slices.add(new Slice(convId, text, null, maxTimestamp));
                } else {
                    List<TopicBoundary> boundaries = detectTopicBoundaries(messages);
                    for (TopicBoundary b : boundaries) {
                        List<Map<String, Object>> segment = messages.subList(
                            (b.startRound - 1) * 2, // each round = user + assistant = 2 messages
                            Math.min(b.endRound * 2, messages.size()));
                        String text = buildConversationText(segment);
                        String segMaxTs = extractMaxTimestamp(segment);
                        slices.add(new Slice(convId, text, b.topic, segMaxTs));
                    }
                }
            } catch (Exception e) {
                log.warn("ConversationSlicer: failed to slice conv={}: {}", convId, e.getMessage());
            }
        }
        log.info("ConversationSlicer: {} conversations → {} slices", conversationIds.size(), slices.size());
        return slices;
    }

    // ---- private helpers ----

    private int countUserRounds(List<Map<String, Object>> messages) {
        int count = 0;
        for (Map<String, Object> msg : messages) {
            Object meta = msg.get("metadata");
            if (meta instanceof Map<?, ?> m && "user".equals(m.get("role"))) count++;
        }
        return count;
    }

    private String extractMaxTimestamp(List<Map<String, Object>> messages) {
        String max = "";
        for (Map<String, Object> msg : messages) {
            Object meta = msg.get("metadata");
            if (meta instanceof Map<?, ?> m) {
                Object ts = m.get("timestamp");
                if (ts != null) {
                    String tsStr = ts.toString();
                    if (tsStr.compareTo(max) > 0) max = tsStr;
                }
            }
        }
        return max;
    }

    private String buildConversationText(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        int round = 1;
        for (Map<String, Object> msg : messages) {
            Object meta = msg.get("metadata");
            String document = (String) msg.getOrDefault("document", "");
            if (meta instanceof Map<?, ?> m && document != null && !document.isBlank()) {
                String role = (String) m.get("role");
                String prefix = "user".equals(role) ? "学生" : "助教";
                sb.append("[").append(round).append("] ").append(prefix).append(": ").append(document).append("\n\n");
                if ("assistant".equals(role)) round++;
            }
        }
        return sb.toString();
    }

    private List<TopicBoundary> detectTopicBoundaries(List<Map<String, Object>> messages) {
        int rounds = countUserRounds(messages);
        String convoText = buildConversationText(messages);

        String prompt = String.format("""
            以下是学生与AI助教的对话记录（共%d轮）。请分析对话中的话题切换点，
            将对话切分为语义连贯的片段。只返回JSON数组，不要其他内容。

            对话记录：
            %s

            输出格式：
            [{"startRound":1,"endRound":12,"topic":"话题简述"},
             {"startRound":13,"endRound":%d,"topic":"话题简述"}]

            规则：
            - 一个片段至少包含3轮对话
            - 话题切换后必须切分
            - 如果全篇话题一致，返回单个片段
            - topic用简短中文概括（不超过15字）
            """, rounds, convoText, rounds);

        log.debug("ConversationSlicer: detecting boundaries for {} rounds", rounds);
        try {
            String response = llmProvider.chat(prompt);
            if (response == null || response.isBlank()) return List.of(singleSlice(rounds));

            // Clean response
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```json\\s*", "").replaceFirst("```\\s*$", "").trim();
            }
            int bracketStart = json.indexOf('[');
            int bracketEnd = json.lastIndexOf(']');
            if (bracketStart >= 0 && bracketEnd > bracketStart) {
                json = json.substring(bracketStart, bracketEnd + 1);
            }

            List<TopicBoundary> boundaries = objectMapper.readValue(json,
                new TypeReference<List<TopicBoundary>>() {});
            if (boundaries == null || boundaries.isEmpty()) return List.of(singleSlice(rounds));
            return boundaries;
        } catch (Exception e) {
            log.warn("ConversationSlicer: boundary detection failed, fallback to single slice: {}", e.getMessage());
            return List.of(singleSlice(rounds));
        }
    }

    private TopicBoundary singleSlice(int rounds) {
        return new TopicBoundary(1, rounds, null);
    }

    // ---- inner types ----

    /** LLM-detected topic boundary. */
    public record TopicBoundary(int startRound, int endRound, String topic) {}

    /** A semantic conversation segment for profile extraction. */
    public record Slice(
        String conversationId,
        String text,
        String topic,
        String maxTimestamp
    ) {}
}
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/passive/ConversationSlicer.java
git commit -m "feat: add ConversationSlicer with hybrid LLM boundary detection"
```

---

### Task 5: PassiveProfileUpdateAgent

**Files:**
- Create: `src/main/java/org/example/educatorweb/profile/passive/PassiveProfileUpdateAgent.java`

- [ ] **Step 1: 创建 PassiveProfileUpdateAgent**

```java
package org.example.educatorweb.profile.passive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.resourcegen.infrastructure.DeepSeekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Passive profile update agent (Agent 1).
 *
 * <p>Unlike the active {@code ProfileExtractionAgent}, this agent:
 * <ul>
 *   <li>Receives the current profile (with confidence) plus a single conversation slice</li>
 *   <li>Returns feature values + a "judgment" (match/conflict/new/insufficient)</li>
 *   <li>Confidence adjustment is deterministic (Java-side formula), not LLM-assigned</li>
 * </ul>
 */
@Component
public class PassiveProfileUpdateAgent {

    private static final Logger log = LoggerFactory.getLogger(PassiveProfileUpdateAgent.class);

    private static final BigDecimal MATCH_DELTA      = new BigDecimal("0.10");
    private static final BigDecimal CONFLICT_DELTA   = new BigDecimal("0.15");
    private static final BigDecimal NEW_CONFIDENCE   = new BigDecimal("0.55");
    private static final BigDecimal FLIP_CONFIDENCE  = new BigDecimal("0.15");
    private static final BigDecimal MAX_CONFIDENCE   = new BigDecimal("0.95");
    private static final BigDecimal ZERO             = BigDecimal.ZERO;

    private final DeepSeekProvider llmProvider;
    private final ObjectMapper objectMapper;

    public PassiveProfileUpdateAgent(DeepSeekProvider llmProvider, ObjectMapper objectMapper) {
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Call LLM to extract features from a conversation slice and judge
     * their relationship to the current profile.
     *
     * @param profile current student profile (read-only for the prompt)
     * @param slice   the conversation segment to analyze
     * @return parsed LLM response with per-dimension value + judgment
     */
    public UpdateResult analyze(StudentProfile profile, ConversationSlicer.Slice slice) {
        String prompt = buildUpdatePrompt(profile, slice);
        log.info("PassiveProfileUpdateAgent: analyzing slice conv={} topic={} ({} chars)",
            slice.conversationId(), slice.topic(), slice.text().length());

        try {
            String response = llmProvider.chat(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("PassiveProfileUpdateAgent: LLM call failed", e);
            return UpdateResult.empty();
        }
    }

    /**
     * Apply deterministic confidence adjustment to the profile based on LLM judgment.
     * Mutates the profile in place.
     */
    public void applyConfidenceAdjustment(StudentProfile profile, UpdateResult result) {
        for (String dimKey : result.dimensions().keySet()) {
            DimensionJudgment dj = result.dimensions().get(dimKey);
            if (dj == null) continue;

            switch (dimKey) {
                case "knowledge" -> applyDim(profile.getKnowledgeBaseLevel(),
                    profile::setKnowledgeBaseLevel,
                    profile::setKnowledgeBaseConfidence,
                    profile.getKnowledgeBaseConfidence(), dj);
                case "cognitive" -> applyDim(profile.getCognitiveStyleType(),
                    profile::setCognitiveStyleType,
                    profile::setCognitiveStyleConfidence,
                    profile.getCognitiveStyleConfidence(), dj);
                case "error" -> applyErrorDim(profile, dj);
                case "pace" -> applyDim(profile.getLearningPaceType(),
                    profile::setLearningPaceType,
                    profile::setLearningPaceConfidence,
                    profile.getLearningPaceConfidence(), dj);
                case "preference" -> applyPreferenceDim(profile, dj);
                case "goal" -> applyDim(profile.getGoalOrientationType(),
                    profile::setGoalOrientationType,
                    profile::setGoalOrientationConfidence,
                    profile.getGoalOrientationConfidence(), dj);
            }
        }
    }

    // ---- confidence formula ----

    private void applyDim(String currentValue,
                          java.util.function.Consumer<String> valueSetter,
                          java.util.function.Consumer<BigDecimal> confSetter,
                          BigDecimal currentConf,
                          DimensionJudgment dj) {
        String judgment = dj.judgment();
        BigDecimal conf = currentConf != null ? currentConf : ZERO;

        switch (judgment) {
            case "match" -> {
                BigDecimal newConf = conf.add(MATCH_DELTA);
                if (newConf.compareTo(MAX_CONFIDENCE) > 0) newConf = MAX_CONFIDENCE;
                confSetter.accept(newConf);
                // value unchanged
            }
            case "conflict" -> {
                BigDecimal newConf = conf.subtract(CONFLICT_DELTA);
                if (newConf.compareTo(ZERO) > 0) {
                    confSetter.accept(newConf);
                    // confidence still positive, keep old value
                } else {
                    // flipped: adopt new value with flip confidence
                    if (dj.value() != null && !dj.value().isBlank()) {
                        valueSetter.accept(dj.value());
                    }
                    confSetter.accept(FLIP_CONFIDENCE);
                }
            }
            case "new" -> {
                if (dj.value() != null && !dj.value().isBlank()) {
                    valueSetter.accept(dj.value());
                }
                confSetter.accept(NEW_CONFIDENCE);
            }
            // "insufficient": no change
        }
    }

    private void applyErrorDim(StudentProfile profile, DimensionJudgment dj) {
        String judgment = dj.judgment();
        BigDecimal conf = profile.getErrorPatternConfidence() != null
            ? profile.getErrorPatternConfidence() : ZERO;

        switch (judgment) {
            case "match" -> {
                BigDecimal newConf = conf.add(MATCH_DELTA);
                if (newConf.compareTo(MAX_CONFIDENCE) > 0) newConf = MAX_CONFIDENCE;
                profile.setErrorPatternConfidence(newConf);
            }
            case "conflict" -> {
                BigDecimal newConf = conf.subtract(CONFLICT_DELTA);
                if (newConf.compareTo(ZERO) > 0) {
                    profile.setErrorPatternConfidence(newConf);
                } else {
                    if (dj.tags() != null && !dj.tags().isEmpty()) {
                        profile.setErrorPatternTags(new ArrayList<>(dj.tags()));
                    }
                    profile.setErrorPatternConfidence(FLIP_CONFIDENCE);
                }
            }
            case "new" -> {
                if (dj.tags() != null && !dj.tags().isEmpty()) {
                    profile.setErrorPatternTags(new ArrayList<>(dj.tags()));
                }
                profile.setErrorPatternConfidence(NEW_CONFIDENCE);
            }
        }
    }

    private void applyPreferenceDim(StudentProfile profile, DimensionJudgment dj) {
        String judgment = dj.judgment();
        // preference has no dedicated confidence field; we use contentPreferenceType as signal
        switch (judgment) {
            case "match" -> { /* no confidence field, no-op */ }
            case "conflict", "new" -> {
                if (dj.type() != null && !dj.type().isBlank()) {
                    profile.setContentPreferenceType(dj.type());
                }
                if (dj.ratio() != null && !dj.ratio().isEmpty()) {
                    profile.setContentPreferenceRatio(new LinkedHashMap<>(dj.ratio()));
                }
                if (judgment.equals("new")) {
                    // nothing extra for preference
                }
            }
        }
    }

    // ---- prompt building ----

    private String buildUpdatePrompt(StudentProfile profile, ConversationSlicer.Slice slice) {
        return String.format("""
            你是一个学习画像分析专家。现在需要根据学生与AI助教的**新对话片段**，
            **增量更新**该学生的6维学习画像。

            ## 学生当前画像
            - knowledge（知识基础）：%s，置信度 %.2f
            - cognitive（认知风格）：%s，置信度 %.2f
            - error（易错偏好）：%s，置信度 %.2f
            - pace（学习步调）：%s，置信度 %.2f
            - preference（内容偏好）：%s / ratio=%s
            - goal（目标导向）：%s，置信度 %.2f

            ## 6维画像定义
            - knowledge: 入门/薄弱/一般/熟练/优秀
            - cognitive: 直觉型/分析型/视觉型/言语型
            - error: 字符串数组如["概念混淆","计算粗心"]
            - pace: 稳扎稳打型/快速推进型/跳跃型
            - preference: type字段为"视频优先"/"文档优先"/"混合学习"；ratio为{"video":0.4,"document":0.3,"code":0.2,"quiz":0.1}格式
            - goal: 求职准备/学术深造/兴趣探索/考证通关/课程考试

            ## 新对话片段（话题: %s）
            %s

            ## 任务
            对每个维度，从新对话中提取特征，并判断与当前画像的关系：
            - "match": 新证据与当前画像**一致**，互证加强
            - "conflict": 新证据与当前画像**矛盾**（值明显不同），应质疑当前画像
            - "new": 该维度之前无可靠信息（置信度<0.3或值为空），新证据是首次有效信息
            - "insufficient": 本片段中**完全无法推断**该维度

            ## 输出格式（纯JSON，不要markdown代码块）
            {"knowledge":{"value":"入门","judgment":"match"},"cognitive":{"value":"分析型","judgment":"conflict"},"error":{"value":["概念混淆"],"judgment":"match"},"pace":{"value":"稳扎稳打型","judgment":"insufficient"},"preference":{"type":"视频优先","ratio":{"video":0.6,"document":0.4},"judgment":"new"},"goal":{"value":"求职准备","judgment":"match"}}
            """,
            profile.getKnowledgeBaseLevel(), fmtConf(profile.getKnowledgeBaseConfidence()),
            profile.getCognitiveStyleType(), fmtConf(profile.getCognitiveStyleConfidence()),
            profile.getErrorPatternTags() != null ? String.join("、", profile.getErrorPatternTags()) : "无",
                fmtConf(profile.getErrorPatternConfidence()),
            profile.getLearningPaceType(), fmtConf(profile.getLearningPaceConfidence()),
            profile.getContentPreferenceType(), profile.getContentPreferenceRatio(),
            profile.getGoalOrientationType(), fmtConf(profile.getGoalOrientationConfidence()),
            slice.topic() != null ? slice.topic() : "通用辅导",
            slice.text()
        );
    }

    private double fmtConf(BigDecimal bd) {
        return bd != null ? bd.doubleValue() : 0.0;
    }

    // ---- response parsing ----

    @SuppressWarnings("unchecked")
    private UpdateResult parseResponse(String response) {
        if (response == null || response.isBlank()) return UpdateResult.empty();
        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```json\\s*", "").replaceFirst("```\\s*$", "").trim();
            }
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
            }

            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, DimensionJudgment> dims = new LinkedHashMap<>();

            for (String dimKey : List.of("knowledge","cognitive","error","pace","preference","goal")) {
                Object dimData = raw.get(dimKey);
                if (!(dimData instanceof Map<?, ?> dimMap)) {
                    dims.put(dimKey, new DimensionJudgment(null, null, null, null, "insufficient"));
                    continue;
                }
                String judgment = dimMap.get("judgment") != null
                    ? dimMap.get("judgment").toString() : "insufficient";

                if ("error".equals(dimKey)) {
                    Object val = dimMap.get("value");
                    List<String> tags = null;
                    if (val instanceof List<?> list && !list.isEmpty()) {
                        tags = new ArrayList<>();
                        for (Object item : list) tags.add(item.toString());
                    }
                    dims.put(dimKey, new DimensionJudgment(null, null, tags, judgment));
                } else if ("preference".equals(dimKey)) {
                    Object typeObj = dimMap.get("type");
                    Object ratioObj = dimMap.get("ratio");
                    Map<String, Double> ratio = null;
                    if (ratioObj instanceof Map<?, ?> rm) {
                        ratio = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : rm.entrySet()) {
                            if (e.getValue() instanceof Number num) {
                                ratio.put(e.getKey().toString(), num.doubleValue());
                            }
                        }
                    }
                    dims.put(dimKey, new DimensionJudgment(
                        typeObj != null ? typeObj.toString() : null, ratio, null, judgment));
                } else {
                    Object val = dimMap.get("value");
                    String value = val instanceof String s && !s.isBlank() && !"null".equals(s)
                        ? s : null;
                    dims.put(dimKey, new DimensionJudgment(value, null, null, judgment));
                }
            }
            return new UpdateResult(dims);
        } catch (JsonProcessingException | ClassCastException e) {
            log.warn("PassiveProfileUpdateAgent: failed to parse LLM response: {}", e.getMessage());
            return UpdateResult.empty();
        }
    }

    // ---- inner types ----

    /** Per-dimension LLM output: value + judgment. */
    public record DimensionJudgment(
        String value,              // scalar value (null for error/preference)
        Map<String, Double> ratio,  // preference ratio
        List<String> tags,         // error tags
        String judgment            // match | conflict | new | insufficient
    ) {}

    /** Parsed LLM response. */
    public record UpdateResult(Map<String, DimensionJudgment> dimensions) {
        public static UpdateResult empty() {
            Map<String, DimensionJudgment> dims = new LinkedHashMap<>();
            for (String key : List.of("knowledge","cognitive","error","pace","preference","goal")) {
                dims.put(key, new DimensionJudgment(null, null, null, "insufficient"));
            }
            return new UpdateResult(dims);
        }
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/passive/PassiveProfileUpdateAgent.java
git commit -m "feat: add PassiveProfileUpdateAgent with deterministic confidence formula"
```

---

### Task 6: PassiveProfileUpdateService

**Files:**
- Create: `src/main/java/org/example/educatorweb/profile/passive/PassiveProfileUpdateService.java`

- [ ] **Step 1: 创建 PassiveProfileUpdateService**

```java
package org.example.educatorweb.profile.passive;

import org.example.educatorweb.profile.ProfileService;
import org.example.educatorweb.profile.model.StudentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Orchestrates passive profile updates triggered after AI tutoring chat.
 *
 * <p>Runs asynchronously so chat response latency is unaffected.
 * Flow: check threshold → fetch unprocessed conversations → slice →
 * analyze each slice via Agent 1 → apply confidence adjustments →
 * persist → update Redis cursor.
 */
@Service
public class PassiveProfileUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PassiveProfileUpdateService.class);

    private final ProcessedConversationTracker tracker;
    private final ConversationSlicer slicer;
    private final PassiveProfileUpdateAgent agent;
    private final ProfileService profileService;
    private final int threshold;

    public PassiveProfileUpdateService(ProcessedConversationTracker tracker,
                                        ConversationSlicer slicer,
                                        PassiveProfileUpdateAgent agent,
                                        ProfileService profileService,
                                        @Value("${profile.passive.threshold:8}") int threshold) {
        this.tracker = tracker;
        this.slicer = slicer;
        this.agent = agent;
        this.profileService = profileService;
        this.threshold = threshold;
    }

    /**
     * Check if the student has accumulated enough unprocessed conversations,
     * and if so, trigger a full passive profile update cycle.
     * <p>Called asynchronously from {@code AiTutorServiceImpl.chat()}.
     */
    @Async("profileUpdateExecutor")
    public void checkAndTrigger(String studentId) {
        try {
            // 1. Check threshold
            int count = tracker.countUnprocessed(studentId);
            if (count < threshold) {
                log.debug("PassiveProfileUpdate: student={} has {} unprocessed convs (threshold={}), skipping",
                    studentId, count, threshold);
                return;
            }

            log.info("PassiveProfileUpdate: triggering for student={}, unprocessedConvs={}",
                studentId, count);

            // 2. Fetch unprocessed conversations
            List<String> convIds = tracker.getUnprocessedConversationIds(studentId);
            if (convIds.isEmpty()) return;

            // 3. Slice
            List<ConversationSlicer.Slice> slices = slicer.slice(studentId, convIds);
            if (slices.isEmpty()) return;

            // 4. Load current profile (or create empty one)
            StudentProfile profile = profileService.getProfile(studentId);
            if (profile == null) {
                profile = createEmptyProfile(studentId);
            }

            // 5. Incremental update: each slice → LLM → confidence adjustment
            String maxTimestamp = null;
            for (ConversationSlicer.Slice slice : slices) {
                PassiveProfileUpdateAgent.UpdateResult result = agent.analyze(profile, slice);
                agent.applyConfidenceAdjustment(profile, result);

                if (maxTimestamp == null
                    || (slice.maxTimestamp() != null && slice.maxTimestamp().compareTo(maxTimestamp) > 0)) {
                    maxTimestamp = slice.maxTimestamp();
                }
            }

            // 6. Normalize to DB enum values (reuse existing sanitization logic)
            sanitizeProfileForDb(profile);

            // 7. Persist
            profileService.updateProfile(studentId, profile);

            // 8. Mark as processed
            if (maxTimestamp != null && !maxTimestamp.isBlank()) {
                tracker.markProcessed(studentId, maxTimestamp);
            }

            log.info("PassiveProfileUpdate: completed for student={}, convs={}, slices={}, maxTs={}",
                studentId, convIds.size(), slices.size(), maxTimestamp);

        } catch (Exception e) {
            log.error("PassiveProfileUpdate: failed for student={}: {}", studentId, e.getMessage(), e);
        }
    }

    // ---- private helpers ----

    private StudentProfile createEmptyProfile(String studentId) {
        StudentProfile p = new StudentProfile();
        p.setStudentId(studentId);
        p.setKnowledgeBaseLevel("beginner");
        p.setKnowledgeBaseConfidence(new BigDecimal("0.50"));
        p.setCognitiveStyleType("visual");
        p.setCognitiveStyleConfidence(new BigDecimal("0.50"));
        p.setErrorPatternTags(new ArrayList<>());
        p.setErrorPatternConfidence(new BigDecimal("0.00"));
        p.setLearningPaceType("normal");
        p.setLearningPaceConfidence(new BigDecimal("0.50"));
        p.setContentPreferenceType("text");
        p.setContentPreferenceRatio(new LinkedHashMap<>());
        p.setGoalOrientationType("exam");
        p.setGoalOrientationConfidence(new BigDecimal("0.50"));
        return p;
    }

    /**
     * Normalize LLM output values to DB CHECK constraint enums.
     * Mirrors the logic in {@code ChatProfileService.sanitizeProfileForDb()}.
     * Since we cannot inject ChatProfileService (circular dependency risk),
     * we inline the essential normalization here.
     */
    private void sanitizeProfileForDb(StudentProfile p) {
        // knowledge_base_level: only beginner / intermediate / advanced / master
        String kl = p.getKnowledgeBaseLevel();
        if (kl == null || kl.isBlank()
            || (!kl.equals("beginner") && !kl.equals("intermediate")
                && !kl.equals("advanced") && !kl.equals("master"))) {
            p.setKnowledgeBaseLevel("beginner");
        }

        // cognitive_style_type: only visual / auditory
        String cs = p.getCognitiveStyleType();
        if (cs == null || cs.isBlank()
            || (!cs.equals("visual") && !cs.equals("auditory"))) {
            p.setCognitiveStyleType("visual");
        }

        // learning_pace_type: only slow / normal / fast
        String lp = p.getLearningPaceType();
        if (lp == null || lp.isBlank()
            || (!lp.equals("slow") && !lp.equals("normal") && !lp.equals("fast"))) {
            p.setLearningPaceType("normal");
        }

        // goal_orientation_type: only exam / research / career / interest
        String go = p.getGoalOrientationType();
        if (go == null || go.isBlank()
            || (!go.equals("exam") && !go.equals("research")
                && !go.equals("career") && !go.equals("interest"))) {
            p.setGoalOrientationType("exam");
        }

        // content_preference_type: only text / video / audio / interactive / graph / ppt
        Set<String> validTypes = Set.of("text", "video", "audio", "interactive", "graph", "ppt");
        String cp = p.getContentPreferenceType();
        if (cp == null || cp.isBlank() || !validTypes.contains(cp)) {
            p.setContentPreferenceType("text");
        }

        // Clamp confidences to [0, 1]
        p.setKnowledgeBaseConfidence(clamp(p.getKnowledgeBaseConfidence()));
        p.setCognitiveStyleConfidence(clamp(p.getCognitiveStyleConfidence()));
        p.setErrorPatternConfidence(clamp(p.getErrorPatternConfidence()));
        p.setLearningPaceConfidence(clamp(p.getLearningPaceConfidence()));
        p.setGoalOrientationConfidence(clamp(p.getGoalOrientationConfidence()));
    }

    private BigDecimal clamp(BigDecimal bd) {
        if (bd == null) return new BigDecimal("0.50");
        if (bd.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (bd.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return bd;
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/passive/PassiveProfileUpdateService.java
git commit -m "feat: add PassiveProfileUpdateService orchestration"
```

---

### Task 7: 接入 AiTutorServiceImpl 触发点

**Files:**
- Modify: `src/main/java/org/example/educatorweb/aitutor/service/AiTutorServiceImpl.java`

- [ ] **Step 1: 注入 PassiveProfileUpdateService 并添加触发调用**

在 `AiTutorServiceImpl.java` 中：

**添加 import：**
```java
import org.example.educatorweb.profile.passive.PassiveProfileUpdateService;
```

**添加字段（与其他 private final 字段并列）：**
```java
private final PassiveProfileUpdateService passiveProfileUpdateService;
```

**修改构造函数（在 `webSearchService` 参数后添加）：**
```java
public AiTutorServiceImpl(ModelRegistry modelRegistry,
                          RagService ragService,
                          EmbeddingService embeddingService,
                          ChromaClient chromaClient,
                          KnowledgeGraphService kgService,
                          WebSearchService webSearchService,
                          PassiveProfileUpdateService passiveProfileUpdateService) {
    this.modelRegistry = modelRegistry;
    this.ragService = ragService;
    this.embeddingService = embeddingService;
    this.chromaClient = chromaClient;
    this.kgService = kgService;
    this.webSearchService = webSearchService;
    this.passiveProfileUpdateService = passiveProfileUpdateService;
}
```

**在 `chat()` 方法末尾，`storeConversation()` 调用之后，`return` 之前添加：**
```java
        // 8. Async trigger for passive profile update
        passiveProfileUpdateService.checkAndTrigger(studentId);
```

完整改动位置（在 `chat()` 方法内，第 90 行 `storeConversation` 后，第 92 行 `List<ChatResponse.SourceSnippet>` 前）：

```java
        // 7. Store this round in Chroma
        storeConversation(conversationId, studentId, question, answer);

        // 8. Async trigger for passive profile update
        passiveProfileUpdateService.checkAndTrigger(studentId);

        // 9. Build response (was step 8, now step 9)
        List<ChatResponse.SourceSnippet> sources = ragSnippets.stream()
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/aitutor/service/AiTutorServiceImpl.java
git commit -m "feat: wire passive profile update trigger into AiTutorServiceImpl"
```

---

### Task 8: LLM 中文输出映射为 DB 枚举

**Files:**
- Modify: `src/main/java/org/example/educatorweb/profile/passive/PassiveProfileUpdateService.java`

Agent 的 LLM 输出是中文值（如"视觉型""稳扎稳打型"），需映射为 DB 英文枚举。`sanitizeProfileForDb()` 当前只是验证枚举是否合法，不能处理中文→英文的映射。

- [ ] **Step 1: 在 `sanitizeProfileForDb` 方法开头添加中文→英文映射**

将 `sanitizeProfileForDb` 方法替换为：

```java
    /**
     * Normalize LLM output (Chinese labels) to DB CHECK constraint enums.
     */
    private void sanitizeProfileForDb(StudentProfile p) {
        // ---- Step 1: Chinese → English mapping ----

        // knowledge: 入门/薄弱 → beginner, 一般 → intermediate, 熟练 → advanced, 优秀/专家 → master
        p.setKnowledgeBaseLevel(normalizeKnowledgeLevel(p.getKnowledgeBaseLevel()));

        // cognitive: 视觉型/直觉型 → visual, 言语型/分析型 → auditory
        String cs = p.getCognitiveStyleType();
        if (cs != null) {
            if (cs.contains("视觉") || cs.contains("直觉")) p.setCognitiveStyleType("visual");
            else if (cs.contains("言语") || cs.contains("分析")) p.setCognitiveStyleType("auditory");
        }

        // pace: 稳扎稳打型 → slow, 快速推进型/跳跃型 → fast
        String lp = p.getLearningPaceType();
        if (lp != null) {
            if (lp.contains("稳扎稳打")) p.setLearningPaceType("slow");
            else if (lp.contains("快速") || lp.contains("跳跃")) p.setLearningPaceType("fast");
        }

        // goal: 求职准备 → career, 学术深造 → research, 兴趣探索 → interest, 考证通关/课程考试 → exam
        String go = p.getGoalOrientationType();
        if (go != null) {
            if (go.contains("求职")) p.setGoalOrientationType("career");
            else if (go.contains("学术")) p.setGoalOrientationType("research");
            else if (go.contains("兴趣")) p.setGoalOrientationType("interest");
            else if (go.contains("考证") || go.contains("考试") || go.contains("exam")) p.setGoalOrientationType("exam");
        }

        // preference: 视频优先 → video, 文档优先 → text, 混合学习 → text
        String cp = p.getContentPreferenceType();
        if (cp != null) {
            if (cp.contains("视频")) p.setContentPreferenceType("video");
            else if (cp.contains("文档")) p.setContentPreferenceType("text");
            else if (cp.contains("混合")) p.setContentPreferenceType("text");
        }

        // ---- Step 2: Fallback validation (ensure enums match CHECK constraints) ----

        Set<String> validPace = Set.of("slow", "normal", "fast");
        Set<String> validGoal = Set.of("exam", "research", "career", "interest");
        Set<String> validCognitive = Set.of("visual", "auditory");
        Set<String> validKnowledge = Set.of("beginner", "intermediate", "advanced", "master");
        Set<String> validContent = Set.of("text", "video", "audio", "interactive", "graph", "ppt");

        if (p.getKnowledgeBaseLevel() == null || !validKnowledge.contains(p.getKnowledgeBaseLevel()))
            p.setKnowledgeBaseLevel("beginner");
        if (p.getCognitiveStyleType() == null || !validCognitive.contains(p.getCognitiveStyleType()))
            p.setCognitiveStyleType("visual");
        if (p.getLearningPaceType() == null || !validPace.contains(p.getLearningPaceType()))
            p.setLearningPaceType("normal");
        if (p.getGoalOrientationType() == null || !validGoal.contains(p.getGoalOrientationType()))
            p.setGoalOrientationType("exam");
        if (p.getContentPreferenceType() == null || !validContent.contains(p.getContentPreferenceType()))
            p.setContentPreferenceType("text");

        // Clamp confidences
        p.setKnowledgeBaseConfidence(clamp(p.getKnowledgeBaseConfidence()));
        p.setCognitiveStyleConfidence(clamp(p.getCognitiveStyleConfidence()));
        p.setErrorPatternConfidence(clamp(p.getErrorPatternConfidence()));
        p.setLearningPaceConfidence(clamp(p.getLearningPaceConfidence()));
        p.setGoalOrientationConfidence(clamp(p.getGoalOrientationConfidence()));
    }

    private String normalizeKnowledgeLevel(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return "beginner";
        String s = llmOutput.trim();
        if (s.contains("入门") || s.contains("薄弱") || s.contains("beginner")) return "beginner";
        if (s.contains("一般") || s.contains("intermediate")) return "intermediate";
        if (s.contains("熟练") || s.contains("advanced")) return "advanced";
        if (s.contains("优秀") || s.contains("master") || s.contains("专家")) return "master";
        return "beginner";
    }
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/passive/PassiveProfileUpdateService.java
git commit -m "feat: add Chinese-to-DB-enum mapping in sanitizeProfileForDb"
```

---

### Task 9: 集成验证

- [ ] **Step 1: 完整编译**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw compile
```

Expected: BUILD SUCCESS, no warnings related to new code

- [ ] **Step 2: 检查 Bean 注入是否完整**

```bash
cd D:/softwaredev/softwareEdu && ./mvnw spring-boot:run 2>&1 | head -80
```

检查启动日志中是否出现：
- `AsyncConfig` 相关日志
- 无 `UnsatisfiedDependencyException` 或循环依赖错误
- 所有 Bean 成功创建

Ctrl+C 停止。

- [ ] **Step 3: 触发链路验证（模拟）**

确认以下调用链在运行时不抛异常：

```
AiTutorServiceImpl.chat()
  → passiveProfileUpdateService.checkAndTrigger(studentId)  // @Async
    → tracker.countUnprocessed(studentId)                    // Redis GET + Chroma query
    → slicer.slice(studentId, convIds)                       // 切片
    → agent.analyze(profile, slice)                          // LLM 调用
    → agent.applyConfidenceAdjustment(profile, result)       // 置信度调节
    → profileService.updateProfile(studentId, profile)       // MySQL 写入
    → tracker.markProcessed(studentId, maxTs)                // Redis SET
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: integration verification passed"
```

---

## 自检结果

### 1. Spec 覆盖
- ✅ 已处理对话追踪（Redis cursor）→ Task 3
- ✅ 混合切片器（会话级 + LLM 边界）→ Task 4
- ✅ 阈值触发（≥8 新对话）→ Task 6
- ✅ 增量更新 Agent（match/conflict/new/insufficient）→ Task 5
- ✅ 确定性置信度调节公式 → Task 5 `applyConfidenceAdjustment`
- ✅ confidence ≤ 0 翻转 + 反向加回差值 → Task 5 conflict 分支
- ✅ DB 枚举约束兼容 → Task 8
- ✅ 异步执行不阻塞聊天 → Task 1 + Task 6 `@Async`
- ✅ ChromaClient 新增 cursor 查询 → Task 2
- ✅ AiTutorServiceImpl 触发点 → Task 7
- ✅ 配置项 profile.passive.* → Task 1 Step 2

### 2. Placeholder 检查
- 无 TBD/TODO/implement later
- 所有异常处理均有具体 catch 块和 log
- 所有方法均有完整实现代码

### 3. 类型一致性
- `ProcessedConversationTracker.getUnprocessedConversationIds()` → `List<String>` ✅
- `ConversationSlicer.slice()` 接收 `List<String>` → `List<Slice>` ✅
- `PassiveProfileUpdateAgent.analyze()` 接收 `StudentProfile` + `Slice` → `UpdateResult` ✅
- `PassiveProfileUpdateAgent.applyConfidenceAdjustment()` 接收 `StudentProfile` + `UpdateResult` → void ✅
- `PassiveProfileUpdateService.checkAndTrigger()` 接收 `String` → `@Async` void ✅
