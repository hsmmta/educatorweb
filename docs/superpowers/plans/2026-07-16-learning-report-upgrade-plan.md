# 学习报告升级 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将学习报告从文字列表升级为 5 维可视化仪表盘（雷达图、进度条、折线图、标签云、柱状图）

**Architecture:** 新增一张 `proficiency_snapshot` 表做历史快照，后端 `LearningReportService` 拆为 5 个独立方法各读各的数据源汇总返回，前端 Profile.vue 用 ECharts 组件替换 text 表格

**Tech Stack:** Spring Boot JPA + Vue 3 + ECharts 5 + vue-echarts 7

**Source spec:** `docs/superpowers/specs/2026-07-16-learning-report-upgrade-design.md`

---

## File Map

| Action | File | Purpose |
|--------|------|---------|
| Create | `src/.../profile/model/ProficiencySnapshot.java` | JPA entity for daily proficiency snapshots |
| Create | `src/.../profile/repository/ProficiencySnapshotRepository.java` | Spring Data JPA repository |
| Modify | `src/.../profile/ProficiencyService.java:114` | Save snapshot after each answer |
| Modify | `src/.../learninglog/repository/LearningBehaviorLogRepository.java` | Add aggregation queries |
| Modify | `src/.../profile/LearningReportService.java` | Expand with radar/progress/input/growth methods |
| Modify | `src/.../topicpush/api/PushNotifyController.java` | Add REPORT_UPDATED event |
| Modify | `src/.../learninglog/service/LearningBehaviorService.java:114` | Fire REPORT_UPDATED after quiz submit |
| Modify | `frontend/package.json` | Add echarts + vue-echarts |
| Modify | `frontend/src/views/Profile.vue` | Replace text tables with chart dashboard |
| Modify | `frontend/src/views/MainLayout.vue:78-101` | Add REPORT_UPDATED SSE handler |

---

### Task 1: ProficiencySnapshot Entity

**Files:**
- Create: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\profile\model\ProficiencySnapshot.java`

- [ ] **Step 1: Create the entity**

```java
package org.example.educatorweb.profile.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "proficiency_snapshot", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"student_id", "concept", "snapshot_date"})
})
public class ProficiencySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", length = 64, nullable = false)
    private String studentId;

    @Column(length = 255, nullable = false)
    private String concept;

    @Column(precision = 5, scale = 4, nullable = false)
    private BigDecimal proficiency;

    @Column(name = "effective_proficiency", precision = 5, scale = 4, nullable = false)
    private BigDecimal effectiveProficiency;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    public ProficiencySnapshot() {}

    public ProficiencySnapshot(String studentId, String concept,
                               BigDecimal proficiency, BigDecimal effectiveProficiency,
                               LocalDate snapshotDate) {
        this.studentId = studentId;
        this.concept = concept;
        this.proficiency = proficiency;
        this.effectiveProficiency = effectiveProficiency;
        this.snapshotDate = snapshotDate;
    }

    // Getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getConcept() { return concept; }
    public void setConcept(String concept) { this.concept = concept; }
    public BigDecimal getProficiency() { return proficiency; }
    public void setProficiency(BigDecimal proficiency) { this.proficiency = proficiency; }
    public BigDecimal getEffectiveProficiency() { return effectiveProficiency; }
    public void setEffectiveProficiency(BigDecimal effectiveProficiency) { this.effectiveProficiency = effectiveProficiency; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }
}
```

- [ ] **Step 2: Create the repository**

**Files:**
- Create: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\profile\repository\ProficiencySnapshotRepository.java`

```java
package org.example.educatorweb.profile.repository;

import org.example.educatorweb.profile.model.ProficiencySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProficiencySnapshotRepository extends JpaRepository<ProficiencySnapshot, Long> {

    Optional<ProficiencySnapshot> findByStudentIdAndConceptAndSnapshotDate(
        String studentId, String concept, LocalDate snapshotDate);

    List<ProficiencySnapshot> findByStudentIdAndSnapshotDateBetween(
        String studentId, LocalDate start, LocalDate end);

    List<ProficiencySnapshot> findByStudentId(String studentId);
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd E:/educatorweb/educatorweb && mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/model/ProficiencySnapshot.java \
        src/main/java/org/example/educatorweb/profile/repository/ProficiencySnapshotRepository.java
git commit -m "feat: add proficiency_snapshot entity and repository"
```

---

### Task 2: Save Snapshots in ProficiencyService

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\profile\ProficiencyService.java`

- [ ] **Step 1: Add snapshot repository import and field**

Add to imports (after line 5):
```java
import org.example.educatorweb.profile.model.ProficiencySnapshot;
import org.example.educatorweb.profile.repository.ProficiencySnapshotRepository;
import java.time.LocalDate;
```

Add field to class (after line 52 `private final ProfileService profileService;`):
```java
private final ProficiencySnapshotRepository snapshotRepo;
```

Update constructor (lines 54-58) from:
```java
public ProficiencyService(StudentKnowledgeProficiencyRepository proficiencyRepo,
                          ProfileService profileService) {
    this.proficiencyRepo = proficiencyRepo;
    this.profileService = profileService;
}
```
to:
```java
public ProficiencyService(StudentKnowledgeProficiencyRepository proficiencyRepo,
                          ProfileService profileService,
                          ProficiencySnapshotRepository snapshotRepo) {
    this.proficiencyRepo = proficiencyRepo;
    this.profileService = profileService;
    this.snapshotRepo = snapshotRepo;
}
```

- [ ] **Step 2: Add snapshot save in updateProficiency()**

After line 114 (`proficiencyRepo.save(prof);`), add:
```java
// Save daily proficiency snapshot for trend tracking
try {
    LocalDate today = LocalDate.now();
    snapshotRepo.findByStudentIdAndConceptAndSnapshotDate(studentId, concept, today)
        .ifPresentOrElse(
            existing -> {
                existing.setProficiency(rawProficiency);
                existing.setEffectiveProficiency(BigDecimal.valueOf(effective));
                snapshotRepo.save(existing);
            },
            () -> snapshotRepo.save(new ProficiencySnapshot(
                studentId, concept, rawProficiency,
                BigDecimal.valueOf(effective), today))
        );
} catch (Exception e) {
    log.debug("ProficiencyService: snapshot save skipped: {}", e.getMessage());
}
```

- [ ] **Step 3: Populate initial snapshot on first deploy**

Add a one-time method to backfill existing proficiency data as initial snapshots. In `ProficiencyService`, add:
```java
/**
 * Backfill today's snapshot for all existing proficiency records.
 * Call once on first deploy to seed the snapshot table.
 */
@Transactional
public void backfillSnapshots(String studentId) {
    LocalDate today = LocalDate.now();
    List<StudentKnowledgeProficiency> all = proficiencyRepo.findByStudentId(studentId);
    for (StudentKnowledgeProficiency kp : all) {
        if (snapshotRepo.findByStudentIdAndConceptAndSnapshotDate(
                studentId, kp.getConcept(), today).isEmpty()) {
            double effective = effectiveProficiency(
                kp.getProficiency() != null ? kp.getProficiency().doubleValue() : 0.0,
                kp.getLastStudyTime());
            snapshotRepo.save(new ProficiencySnapshot(
                studentId, kp.getConcept(),
                kp.getProficiency() != null ? kp.getProficiency() : BigDecimal.ZERO,
                BigDecimal.valueOf(effective), today));
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

```bash
cd E:/educatorweb/educatorweb && mvn compile -q 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/ProficiencyService.java
git commit -m "feat: save daily proficiency snapshots on each answer"
```

---

### Task 3: Add LearningBehaviorLog Aggregation Queries

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\learninglog\repository\LearningBehaviorLogRepository.java`

- [ ] **Step 1: Add aggregation query methods**

At the end of the interface (before the closing `}`), add:
```java
    /** Count distinct active days for a user (days with any logged activity). */
    @Query("SELECT COUNT(DISTINCT FUNCTION('DATE', l.createdAt)) FROM LearningBehaviorLog l WHERE l.userId = :userId")
    long countActiveDaysByUserId(@Param("userId") String userId);

    /** Count events by type for a user. */
    long countByUserIdAndEventType(String userId, String eventType);

    /** Get all events for a user within a date range. */
    @Query("SELECT l FROM LearningBehaviorLog l WHERE l.userId = :userId AND l.createdAt >= :start AND l.createdAt <= :end ORDER BY l.createdAt")
    List<LearningBehaviorLog> findByUserIdAndCreatedAtBetween(
        @Param("userId") String userId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end);
```

Add required imports at top:
```java
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

- [ ] **Step 2: Verify compilation**

```bash
cd E:/educatorweb/educatorweb && mvn compile -q 2>&1 | tail -5
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/learninglog/repository/LearningBehaviorLogRepository.java
git commit -m "feat: add aggregation queries to LearningBehaviorLogRepository"
```

---

### Task 4: Expand LearningReportService

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\profile\LearningReportService.java`

- [ ] **Step 1: Add new dependencies**

Add fields and update constructor (lines 44-52):
```java
    private final ProficiencyService proficiencyService;
    private final ProfileService profileService;
    private final ProficiencySnapshotRepository snapshotRepo;
    private final LearningBehaviorLogRepository behaviorLogRepo;

    public LearningReportService(ProficiencyService proficiencyService,
                                  ProfileService profileService,
                                  ProficiencySnapshotRepository snapshotRepo,
                                  LearningBehaviorLogRepository behaviorLogRepo) {
        this.proficiencyService = proficiencyService;
        this.profileService = profileService;
        this.snapshotRepo = snapshotRepo;
        this.behaviorLogRepo = behaviorLogRepo;
    }
```

Add imports:
```java
import org.example.educatorweb.profile.repository.ProficiencySnapshotRepository;
import org.example.educatorweb.learninglog.repository.LearningBehaviorLogRepository;
import java.time.DayOfWeek;
import java.time.temporal.WeekFields;
```

- [ ] **Step 2: Add buildKnowledgeRadar() method**

Append after `generateSummary()` method (before the data type definitions):
```java
    /**
     * Build radar chart data: all proficiency entries with concept + effectiveProficiency.
     * Limits to top 8 most practiced + bottom 4 weakest for readability.
     */
    private List<Map<String, Object>> buildKnowledgeRadar(String studentId) {
        List<ProficiencyService.ProficiencyResult> all = proficiencyService.getAllProficiencies(studentId);
        if (all.isEmpty()) return List.of();

        // Sort by totalQuestions desc → pick top 8 most-practiced
        List<ProficiencyService.ProficiencyResult> sorted = all.stream()
            .sorted(Comparator.comparingInt(ProficiencyService.ProficiencyResult::totalQuestions).reversed())
            .limit(8)
            .toList();

        // Add weakest concepts not already included (up to 4)
        Set<String> included = new HashSet<>();
        sorted.forEach(r -> included.add(r.concept()));
        List<ProficiencyService.ProficiencyResult> weak = all.stream()
            .filter(r -> !included.contains(r.concept()))
            .sorted(Comparator.comparingDouble(ProficiencyService.ProficiencyResult::effectiveProficiency))
            .limit(4)
            .toList();

        List<Map<String, Object>> radar = new ArrayList<>();
        for (ProficiencyService.ProficiencyResult r : sorted) {
            radar.add(Map.of("concept", r.concept(),
                "proficiency", Math.round(r.effectiveProficiency() * 100.0) / 100.0));
        }
        for (ProficiencyService.ProficiencyResult r : weak) {
            radar.add(Map.of("concept", r.concept(),
                "proficiency", Math.round(r.effectiveProficiency() * 100.0) / 100.0));
        }
        return radar;
    }
```

- [ ] **Step 3: Add buildLearningProgress() method**

```java
    /**
     * Build learning progress from the student's latest learning path.
     */
    private Map<String, Object> buildLearningProgress(String studentId) {
        // Read from student_profile directly — avoids LearningPathService circular dep
        StudentProfile profile = profileService.getProfile(studentId);
        List<StudentKnowledgeProficiency> allKps = proficiencyService.getAllProficiencies(studentId)
            .stream().map(r -> {
                StudentKnowledgeProficiency kp = new StudentKnowledgeProficiency();
                kp.setConcept(r.concept());
                kp.setProficiency(BigDecimal.valueOf(r.effectiveProficiency()));
                return kp;
            }).toList();

        int total = allKps.size();
        long completed = allKps.stream()
            .filter(k -> k.getProficiency() != null && k.getProficiency().doubleValue() >= 0.8)
            .count();

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("totalNodes", total);
        progress.put("completedNodes", (int) completed);
        progress.put("currentNode", "");
        return progress;
    }
```

- [ ] **Step 4: Add buildLearningInput() method**

```java
    /**
     * Build learning input metrics from behavior log aggregation.
     */
    private Map<String, Object> buildLearningInput(String studentId) {
        long activeDays = behaviorLogRepo.countActiveDaysByUserId(studentId);
        long resourceViews = behaviorLogRepo.countByUserIdAndEventType(studentId, "RESOURCE_VIEW");
        long chatRounds = behaviorLogRepo.countByUserIdAndEventType(studentId, "CHAT_INTERACTION");
        long quizTotal = behaviorLogRepo.countByUserIdAndEventType(studentId, "QUIZ_ANSWER");

        // Build weekly trend (last 4 weeks)
        List<Map<String, Object>> weeklyTrend = new ArrayList<>();
        LocalDate now = LocalDate.now();
        WeekFields wf = WeekFields.of(DayOfWeek.MONDAY, 1);
        for (int i = 3; i >= 0; i--) {
            LocalDate weekStart = now.minusWeeks(i).with(wf.dayOfWeek(), 1);
            LocalDate weekEnd = weekStart.plusDays(6);
            LocalDateTime start = weekStart.atStartOfDay();
            LocalDateTime end = weekEnd.atTime(23, 59, 59);

            List<LearningBehaviorLog> weekLogs =
                behaviorLogRepo.findByUserIdAndCreatedAtBetween(studentId, start, end);
            long weekQuizzes = weekLogs.stream()
                .filter(l -> "QUIZ_ANSWER".equals(l.getEventType())).count();
            long weekViews = weekLogs.stream()
                .filter(l -> "RESOURCE_VIEW".equals(l.getEventType())).count();

            weeklyTrend.add(Map.of(
                "week", "W" + (now.get(wf.weekOfYear()) - i),
                "quizzes", weekQuizzes,
                "views", weekViews
            ));
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("activeDays", (int) activeDays);
        input.put("totalDurationMin", 0); // duration tracked via detail JSON, simplified
        input.put("resourceViews", (int) resourceViews);
        input.put("chatRounds", (int) chatRounds);
        input.put("quizTotal", (int) quizTotal);
        input.put("weeklyTrend", weeklyTrend);
        return input;
    }
```

- [ ] **Step 5: Add buildGrowthTrend() method**

```java
    /**
     * Build proficiency growth trend (last 8 weeks) from snapshots.
     */
    private List<Map<String, Object>> buildGrowthTrend(String studentId) {
        LocalDate today = LocalDate.now();
        LocalDate eightWeeksAgo = today.minusWeeks(8);
        List<ProficiencySnapshot> snapshots =
            snapshotRepo.findByStudentIdAndSnapshotDateBetween(studentId, eightWeeksAgo, today);

        // Group by week, average per week
        Map<Integer, List<Double>> byWeek = new LinkedHashMap<>();
        WeekFields wf = WeekFields.of(DayOfWeek.MONDAY, 1);
        int currentWeek = today.get(wf.weekOfYear());

        for (ProficiencySnapshot s : snapshots) {
            int week = s.getSnapshotDate().get(wf.weekOfYear());
            byWeek.computeIfAbsent(week, k -> new ArrayList<>())
                .add(s.getEffectiveProficiency().doubleValue());
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int w = currentWeek - 7; w <= currentWeek; w++) {
            List<Double> vals = byWeek.getOrDefault(w, List.of());
            double avg = vals.isEmpty() ? 0.0
                : vals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            trend.add(Map.of(
                "week", "W" + w,
                "avgProficiency", Math.round(avg * 100.0) / 100.0
            ));
        }
        return trend;
    }
```

- [ ] **Step 6: Wire new methods into generateProfileSummary()**

In `generateProfileSummary()` (after line 160, before `return result;`), add:
```java
        result.put("knowledgeRadar", buildKnowledgeRadar(studentId));
        result.put("learningProgress", buildLearningProgress(studentId));
        result.put("learningInput", buildLearningInput(studentId));
        result.put("growthTrend", buildGrowthTrend(studentId));
```

- [ ] **Step 7: Verify compilation**

```bash
cd E:/educatorweb/educatorweb && mvn compile -q 2>&1 | tail -5
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/LearningReportService.java
git commit -m "feat: expand LearningReportService with radar/progress/input/growth data"
```

---

### Task 5: Add REPORT_UPDATED SSE Event

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\topicpush\api\PushNotifyController.java`
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\learninglog\service\LearningBehaviorService.java`

- [ ] **Step 1: Add REPORT_UPDATED sink to PushNotifyController**

In `PushNotifyController.java`, add a separate sink for report update events. Replace the class body with:

After line 27 (`private final PushTriggerService pushTrigger;`), add:
```java
    /** Separate sink for lightweight report-updated notifications. */
    private final Sinks.Many<String> reportUpdateSink =
        Sinks.many().multicast().onBackpressureBuffer();

    public Sinks.Many<String> getReportUpdateSink() {
        return reportUpdateSink;
    }
```

Add import: `import reactor.core.publisher.Sinks;`

In the `subscribe()` method, add the report update stream. After line 31 (after the heartbeat Flux), insert after the existing merge:
```java
        Flux<ServerSentEvent<String>> reportUpdates = reportUpdateSink.asFlux()
            .filter(uid -> uid.equals(studentId))
            .map(uid -> ServerSentEvent.<String>builder()
                .event("REPORT_UPDATED")
                .data("{}")
                .build());

        return Flux.merge(
                events.map(e -> ServerSentEvent.<PushNotification>builder()
                    .data(e)
                    .build()),
                heartbeat,
                reportUpdates
            )
```

Since `PushNotification` is typed, we need to change the return type or use a broader type. The simplest fix: use `ServerSentEvent<String>` for all.

Replace the entire `subscribe` method:
```java
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribe(@RequestParam String studentId) {

        Flux<ServerSentEvent<String>> pushEvents = pushTrigger.getNotificationSink()
            .asFlux()
            .filter(n -> n.userId().equals(studentId))
            .map(n -> ServerSentEvent.<String>builder()
                .event("PUSH")
                .data(objectToJson(n))
                .build());

        Flux<ServerSentEvent<String>> reportUpdates = reportUpdateSink.asFlux()
            .filter(uid -> uid.equals(studentId))
            .map(uid -> ServerSentEvent.<String>builder()
                .event("REPORT_UPDATED")
                .data("{}")
                .build());

        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
            .map(tick -> ServerSentEvent.<String>builder()
                .comment("heartbeat")
                .build());

        return Flux.merge(pushEvents, reportUpdates, heartbeat)
            .doOnSubscribe(s ->
                log.info("PushNotifyController: SSE subscribed for user={}", studentId))
            .doOnCancel(() ->
                log.info("PushNotifyController: SSE cancelled for user={}", studentId));
    }

    private String objectToJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
```

- [ ] **Step 2: Fire REPORT_UPDATED after quiz submission**

In `LearningBehaviorService.java`, inject `PushNotifyController` and fire after `logQuizResults()`.

Add field and update constructor:
```java
    private final PushNotifyController pushNotifyController;

    public LearningBehaviorService(LearningBehaviorLogRepository logRepo,
                                   StudentKnowledgeProficiencyRepository proficiencyRepo,
                                   StudentProfileRepository profileRepo,
                                   ApplicationEventPublisher eventPublisher,
                                   PushNotifyController pushNotifyController) {
        this.logRepo = logRepo;
        this.proficiencyRepo = proficiencyRepo;
        this.profileRepo = profileRepo;
        this.eventPublisher = eventPublisher;
        this.pushNotifyController = pushNotifyController;
    }
```

Add import: `import org.example.educatorweb.topicpush.api.PushNotifyController;`

At the end of `logQuizResults()` (after line 113, after `maybeAdjustProfile(...)`), add:
```java
        // Notify report subscribers to refresh
        try {
            pushNotifyController.getReportUpdateSink().tryEmitNext(userId);
        } catch (Exception e) {
            log.debug("LearningBehavior: report update notify skipped: {}", e.getMessage());
        }
```

- [ ] **Step 3: Verify compilation**

```bash
cd E:/educatorweb/educatorweb && mvn compile -q 2>&1 | tail -5
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/educatorweb/topicpush/api/PushNotifyController.java \
        src/main/java/org/example/educatorweb/learninglog/service/LearningBehaviorService.java
git commit -m "feat: add REPORT_UPDATED SSE event for real-time report refresh"
```

---

### Task 6: Install ECharts Dependency

**Files:**
- Modify: `E:\educatorweb\educatorweb\frontend\package.json`

- [ ] **Step 1: Install echarts and vue-echarts**

```bash
cd E:/educatorweb/educatorweb/frontend && npm install echarts vue-echarts --save
```

- [ ] **Step 2: Verify install**

```bash
cd E:/educatorweb/educatorweb/frontend && node -e "require('echarts'); require('vue-echarts'); console.log('OK')"
```

Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "feat: add echarts and vue-echarts for learning report dashboard"
```

---

### Task 7: Add REPORT_UPDATED Handler to MainLayout.vue

**Files:**
- Modify: `E:\educatorweb\educatorweb\frontend\src\views\MainLayout.vue`

- [ ] **Step 1: Update SSE event handler**

The SSE data now uses `event:` field from ServerSentEvent (e.g., `event: PUSH` or `event: REPORT_UPDATED`). Update the `es.onmessage` handler to read `event.type`:

Replace lines 78-101 (the entire `es.onmessage` block) with:
```javascript
    es.addEventListener('PUSH', (event) => {
      try {
        const data = JSON.parse(event.data)
        pushNotificationCount.value++
        ElNotification({
          title: '资源推送',
          message: '已为你推送 ' + data.resourceCount + ' 个学习资源（' + (data.triggerType === 'COUNT' ? '话题触发' : '定时推送') + '）',
          type: 'info',
          duration: 5000,
          onClick: goToPush
        })
        window.dispatchEvent(new CustomEvent('push-refresh'))
      } catch { /* ignore */ }
    })

    es.addEventListener('REPORT_UPDATED', () => {
      window.dispatchEvent(new CustomEvent('report-updated'))
    })

    // Backward-compatible: handle Data field without event type (legacy format)
    es.addEventListener('message', (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.triggerType === 'PATH_UPDATED') {
          ElNotification({
            title: '学习路径已更新',
            message: '你的画像发生了变化，学习路径已自动调整（剩余 ' + data.resourceCount + ' 个节点）',
            type: 'success',
            duration: 5000,
            onClick: goToPush
          })
        } else {
          pushNotificationCount.value++
          ElNotification({
            title: '资源推送',
            message: '已为你推送 ' + data.resourceCount + ' 个学习资源（' + (data.triggerType === 'COUNT' ? '话题触发' : '定时推送') + '）',
            type: 'info',
            duration: 5000,
            onClick: goToPush
          })
          window.dispatchEvent(new CustomEvent('push-refresh'))
        }
      } catch { /* ignore */ }
    })
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/views/MainLayout.vue
git commit -m "feat: add REPORT_UPDATED SSE handler in MainLayout"
```

---

### Task 8: Refactor Profile.vue — Charts Section

**Files:**
- Modify: `E:\educatorweb\educatorweb\frontend\src\views\Profile.vue`

- [ ] **Step 1: Add imports for ECharts**

Replace the `<script setup>` imports (lines 225-229):
```javascript
import { ref, reactive, computed, onMounted, onUnmounted, h } from 'vue'
import { ElMessage } from 'element-plus'
import { getProfileSummaryApi } from '../api/index.js'
import { ChatDotRound, Edit, UserFilled } from '@element-plus/icons-vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { RadarChart } from 'echarts/charts'
import { LineChart } from 'echarts/charts'
import { BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, RadarComponent } from 'echarts/components'
import { GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([CanvasRenderer, RadarChart, LineChart, BarChart,
     TitleComponent, TooltipComponent, LegendComponent, RadarComponent, GridComponent])
```

- [ ] **Step 2: Add chart option computed properties**

After `const tips` computed (after line 304), add:
```javascript
// ---- chart options ----
const radarOption = computed(() => {
  const data = reportData.value.knowledgeRadar || []
  if (!data.length) return {}
  return {
    title: { text: '知识掌握度', left: 'center', textStyle: { fontSize: 14, color: '#1a1a2e' } },
    tooltip: {},
    legend: { bottom: 0, data: ['有效掌握度'] },
    radar: {
      indicator: data.map(d => ({ name: d.concept, max: 1 })),
      radius: '60%'
    },
    series: [{
      type: 'radar',
      data: [{ value: data.map(d => d.proficiency), name: '有效掌握度' }],
      areaStyle: { color: 'rgba(102,126,234,0.2)' },
      lineStyle: { color: '#667eea' },
      itemStyle: { color: '#667eea' }
    }]
  }
})

const growthOption = computed(() => {
  const data = reportData.value.growthTrend || []
  if (!data.length) return {}
  return {
    title: { text: '能力成长趋势', left: 'center', textStyle: { fontSize: 14, color: '#1a1a2e' } },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: data.map(d => d.week) },
    yAxis: { type: 'value', min: 0, max: 1, axisLabel: { formatter: v => Math.round(v * 100) + '%' } },
    series: [{
      type: 'line',
      data: data.map(d => d.avgProficiency),
      smooth: true,
      lineStyle: { color: '#667eea' },
      itemStyle: { color: '#667eea' },
      areaStyle: { color: 'rgba(102,126,234,0.1)' }
    }]
  }
})

const inputOption = computed(() => {
  const data = (reportData.value.learningInput?.weeklyTrend || [])
  if (!data.length) return {}
  return {
    title: { text: '近4周投入度', left: 'center', textStyle: { fontSize: 14, color: '#1a1a2e' } },
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, data: ['测验数', '浏览数'] },
    xAxis: { type: 'category', data: data.map(d => d.week) },
    yAxis: { type: 'value' },
    series: [
      { name: '测验数', type: 'bar', data: data.map(d => d.quizzes), color: '#667eea' },
      { name: '浏览数', type: 'bar', data: data.map(d => d.views), color: '#764ba2' }
    ]
  }
})
```

- [ ] **Step 3: Add report data state and event listener**

After `const profileExists = ref(false)` (around line 231), add:
```javascript
const reportData = ref({ knowledgeRadar: [], learningProgress: {}, learningInput: {}, growthTrend: [] })
```

In `onMounted`, add the SSE listener. After the existing `onMounted` block (line 319+), add at the end:
```javascript
  window.addEventListener('report-updated', loadReport)
})

onUnmounted(() => {
  window.removeEventListener('report-updated', loadReport)
})
```

Update the `loadReport` extraction from `data` to also capture new fields. Inside the `try` block of `onMounted` (after line 332 `stats.quizCount = data.quizCount || 0`), add:
```javascript
      reportData.value = {
        knowledgeRadar: data.knowledgeRadar || [],
        learningProgress: data.learningProgress || { totalNodes: 0, completedNodes: 0, currentNode: '' },
        learningInput: data.learningInput || { activeDays: 0, totalDurationMin: 0, resourceViews: 0, chatRounds: 0, quizTotal: 0, weeklyTrend: [] },
        growthTrend: data.growthTrend || []
      }
```

Wrap the loading logic into a named `loadReport` function for SSE re-calls. Replace the `onMounted` block (lines 319-367) to extract into:

```javascript
const loadReport = async () => {
  try {
    const res = await getProfileSummaryApi(getStudentId())
    const data = res.data
    if (data && data.exists) {
      profileExists.value = true
      stats.learningDays = data.learningDays || 0
      stats.resourceCount = data.resourceCount || 0
      stats.quizCount = data.quizCount || 0

      reportData.value = {
        knowledgeRadar: data.knowledgeRadar || [],
        learningProgress: data.learningProgress || { totalNodes: 0, completedNodes: 0, currentNode: '' },
        learningInput: data.learningInput || { activeDays: 0, totalDurationMin: 0, resourceViews: 0, chatRounds: 0, quizTotal: 0, weeklyTrend: [] },
        growthTrend: data.growthTrend || []
      }

      const confMap = data.confidences || {}
      dimensions.value = [...same as before...]
      // ... rest of existing dimension setup ...
    }
  } catch (e) { /* silent */ }
}

onMounted(async () => {
  const info = localStorage.getItem('userInfo')
  if (info) { try { userInfo.value = JSON.parse(info) } catch { userInfo.value = {} } }
  await loadReport()
  window.addEventListener('report-updated', loadReport)
})

onUnmounted(() => {
  window.removeEventListener('report-updated', loadReport)
})
```

**Note — loadReport extraction:** Move the existing `data` reading logic (lines 326-367 in the original file) into `loadReport`. The complete extracted function is:

```javascript
const loadReport = async () => {
  try {
    const res = await getProfileSummaryApi(getStudentId())
    const data = res.data
    if (data && data.exists) {
      profileExists.value = true
      stats.learningDays = data.learningDays || 0
      stats.resourceCount = data.resourceCount || 0
      stats.quizCount = data.quizCount || 0
      reportData.value = {
        compositeScore: data.compositeScore || 0,
        knowledgeRadar: data.knowledgeRadar || [],
        learningProgress: data.learningProgress || { totalNodes: 0, completedNodes: 0, currentNode: '' },
        learningInput: data.learningInput || { activeDays: 0, totalDurationMin: 0, resourceViews: 0, chatRounds: 0, quizTotal: 0, weeklyTrend: [] },
        growthTrend: data.growthTrend || []
      }
      const confMap = data.confidences || {}
      dimensions.value = [
        { key: 'knowledge', icon: '\u{1F4D6}', label: '知识基础',
          value: data.knowledgeBaseLevel || '', confidence: Math.round((confMap.knowledge || 0) * 100), color: '#667eea' },
        { key: 'cognitive', icon: '\u{1F9E9}', label: '认知风格',
          value: data.cognitiveStyleType || '', confidence: Math.round((confMap.cognitive || 0) * 100), color: '#3b82f6' },
        { key: 'error', icon: '⚠️', label: '易错偏好',
          value: (data.errorPatternTags || []).join('、') || '', confidence: Math.round((confMap.error || 0) * 100), color: '#f97316' },
        { key: 'pace', icon: '\u{1F3C3}', label: '学习步调',
          value: data.learningPaceType || '', confidence: Math.round((confMap.pace || 0) * 100), color: '#22c55e' },
        { key: 'preference', icon: '\u{1F3AF}', label: '内容偏好',
          value: data.contentPreferenceType || '', confidence: 50, color: '#ec4899' },
        { key: 'goal', icon: '\u{1F3C6}', label: '目标导向',
          value: data.goalOrientationType || '', confidence: Math.round((confMap.goal || 0) * 100), color: '#a855f7' }
      ]
      weakPoints.value = (data.weakPoints || []).map(wp => ({
        concept: wp.concept, proficiency: wp.proficiency, confidence: wp.confidence,
        totalQuestions: wp.totalQuestions || 0, correctQuestions: wp.correctQuestions || 0,
        daysSinceStudy: wp.daysSinceStudy || 0
      }))
      strongPoints.value = (data.strongPoints || []).map(sp => ({
        concept: sp.concept, proficiency: sp.proficiency, confidence: sp.confidence
      }))
      summaryText.value = data.summary || ''
    } else {
      profileExists.value = false
    }
  } catch (e) { /* silent */ }
}
```

The `onMounted` becomes:
```javascript
onMounted(async () => {
  const info = localStorage.getItem('userInfo')
  if (info) { try { userInfo.value = JSON.parse(info) } catch { userInfo.value = {} } }
  await loadReport()
  window.addEventListener('report-updated', loadReport)
})

onUnmounted(() => {
  window.removeEventListener('report-updated', loadReport)
})
```

- [ ] **Step 4: Replace weak/strong point tables with dashboard**

Replace lines 108-199 (the entire `<!-- ====== 学习报告 ====== -->` section) with:
```html
    <!-- ====== 学习报告仪表盘 ====== -->
    <section class="section" v-if="profileExists">
      <div class="report-header">
        <h3>📋 学习报告</h3>
        <span class="report-date">生成于 {{ reportDate }}</span>
        <el-button size="small" text @click="loadReport">刷新</el-button>
      </div>

      <!-- 无数据 -->
      <div v-if="stats.quizCount === 0" class="report-empty">
        <el-empty description="还没有学习记录，完成首次练习后将自动生成学习报告" :image-size="100">
          <el-button type="primary" @click="$router.push('/learning')">去学习 →</el-button>
        </el-empty>
      </div>

      <template v-else>
        <!-- Row 1: 综合评分 + 投入度数字 -->
        <div class="stats-row">
          <div class="stat-card composite">
            <span class="stat-card-num">{{ reportData.compositeScore || stats.compositeScore || '—' }}</span>
            <span class="stat-card-label">综合评分</span>
          </div>
          <div class="stat-card">
            <span class="stat-card-num">{{ reportData.learningInput?.activeDays || 0 }}</span>
            <span class="stat-card-label">活跃天数</span>
          </div>
          <div class="stat-card">
            <span class="stat-card-num">{{ reportData.learningInput?.resourceViews || 0 }}</span>
            <span class="stat-card-label">浏览资源</span>
          </div>
          <div class="stat-card">
            <span class="stat-card-num">{{ reportData.learningInput?.chatRounds || 0 }}</span>
            <span class="stat-card-label">AI 对话</span>
          </div>
          <div class="stat-card">
            <span class="stat-card-num">{{ reportData.learningInput?.quizTotal || 0 }}</span>
            <span class="stat-card-label">答题数</span>
          </div>
        </div>

        <!-- Row 2: 雷达图 + 折线图 -->
        <div class="chart-row">
          <div class="chart-box" v-if="(reportData.knowledgeRadar || []).length">
            <v-chart :option="radarOption" autoresize style="height:320px" />
          </div>
          <div class="chart-box" v-if="(reportData.growthTrend || []).length">
            <v-chart :option="growthOption" autoresize style="height:320px" />
          </div>
          <div v-if="!(reportData.knowledgeRadar || []).length && !(reportData.growthTrend || []).length"
               class="chart-box chart-empty">
            <p>完成练习后，这里将展示知识点掌握雷达图与成长趋势</p>
          </div>
        </div>

        <!-- Row 3: 学习进度 + 投入度柱状图 -->
        <div class="chart-row">
          <div class="chart-box" v-if="reportData.learningProgress?.totalNodes">
            <h4>📂 学习进度</h4>
            <el-progress
              :percentage="Math.round((reportData.learningProgress.completedNodes || 0) / (reportData.learningProgress.totalNodes || 1) * 100)"
              :stroke-width="16" color="#667eea" />
            <p class="progress-label">
              已完成 {{ reportData.learningProgress.completedNodes }} /
              共 {{ reportData.learningProgress.totalNodes }} 个知识点
            </p>
          </div>
          <div class="chart-box" v-if="(reportData.learningInput?.weeklyTrend || []).length">
            <v-chart :option="inputOption" autoresize style="height:280px" />
          </div>
        </div>

        <!-- Row 4: 薄弱环节标签云 -->
        <div class="tag-cloud" v-if="weakPoints.length">
          <h4>📉 薄弱环节</h4>
          <span v-for="wp in weakPoints" :key="wp.concept"
                :style="{ fontSize: 12 + (1 - wp.proficiency) * 12 + 'px',
                         color: wp.proficiency < 0.4 ? '#f56c6c' : wp.proficiency < 0.6 ? '#e6a23c' : '#909399' }"
                class="tag-cloud-item">{{ wp.concept }}</span>
        </div>

        <!-- 学习建议（保留） -->
        <h4>💡 学习建议</h4>
        <div class="tips-list">
          <div v-for="(tip, i) in learningTips" :key="i" :class="['tip-item', tip.level]">
            <span class="tip-icon">{{ tip.icon }}</span>
            <div class="tip-body">
              <strong>{{ tip.title }}</strong>
              <p>{{ tip.desc }}</p>
            </div>
          </div>
        </div>
      </template>
    </section>
```

- [ ] **Step 5: Add new CSS styles**

Replace the existing `.report-*` styles and append chart styles. At the end of the `<style scoped>` block (before the closing `</style>`), add:
```css
.stats-row { display: flex; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.stat-card {
  flex: 1; min-width: 100px; text-align: center;
  padding: 16px 12px; border-radius: 14px;
  background: linear-gradient(135deg, rgba(102,126,234,0.06), rgba(118,75,162,0.04));
  border: 1px solid rgba(102,126,234,0.12);
}
.stat-card.composite { background: linear-gradient(135deg, #667eea, #764ba2); color: #fff; border: none; }
.stat-card-num { display: block; font-size: 28px; font-weight: 800; }
.stat-card.composite .stat-card-num { color: #fff; }
.stat-card-label { font-size: 12px; color: #909399; margin-top: 4px; display: block; }
.stat-card.composite .stat-card-label { color: rgba(255,255,255,0.8); }

.chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 24px; }
.chart-box {
  background: #fff; border-radius: 14px; padding: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
}
.chart-empty {
  display: flex; align-items: center; justify-content: center;
  min-height: 200px; color: #909399; font-size: 14px;
}
.progress-label { text-align: center; color: #909399; font-size: 13px; margin-top: 10px; }

.tag-cloud { padding: 16px; margin-bottom: 20px; text-align: center; }
.tag-cloud h4 { margin-bottom: 12px; }
.tag-cloud-item {
  display: inline-block; margin: 4px 8px; cursor: default;
  font-weight: 600; transition: transform 0.15s;
}
.tag-cloud-item:hover { transform: scale(1.1); }

@media (max-width: 768px) {
  .chart-row { grid-template-columns: 1fr; }
  .stat-card { min-width: 70px; }
}
```

- [ ] **Step 6: Test frontend build**

```bash
cd E:/educatorweb/educatorweb/frontend && npx vite build 2>&1 | tail -5
```

Expected: built successfully

- [ ] **Step 7: Commit**

```bash
git add frontend/src/views/Profile.vue frontend/src/views/MainLayout.vue
git commit -m "feat: upgrade Profile.vue with ECharts dashboard (radar, line, bar, tag cloud)"
```

---

## Verification Checklist

After all tasks complete, verify:

1. Start backend: `mvn spring-boot:run -Dnet.bytebuddy.experimental=true`
2. Start frontend: `cd frontend && npm run dev`
3. Open Profile page — should see empty state (no quiz data yet)
4. Complete a quiz via ResourceView → submit
5. Refresh Profile — should see radar chart + growth line + input bar + tag cloud
6. Complete another quiz → SSE should trigger `REPORT_UPDATED` → charts refresh automatically
7. Delete a conversation → should persist after page refresh

**Deployment note:** On first deploy, the `proficiency_snapshot` table will be empty (no historical data). Existing users will see 0 data points on the growth trend chart until they answer new quizzes. To backfill existing proficiency data, call `ProficiencyService.backfillSnapshots(studentId)` once per user. This can be triggered by adding a temporary REST endpoint or calling it from `ProfileController.getProfileSummary()` if the snapshot table is empty for that user.
7. Delete a conversation via sidebar → should persist after page refresh
