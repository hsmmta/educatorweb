# 学习路径可交互升级 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 学习路径可点击使用 — 持久化保存、节点一键生成资源、掌握度达标智能提示

**Architecture:** `student_profile` 表加 `learning_path_json` TEXT 字段存储路径，前端 ResourcePush.vue 展示路径节点列表，点击跳 Chat.vue 带 topic/mode URL 参数自动触发生成。SSE 推送掌握度里程碑通知。

**Tech Stack:** Spring Boot JPA + Vue 3 + Element Plus

**Source spec:** `docs/superpowers/specs/2026-07-16-learning-path-interactive-design.md`

---

## File Map

| Action | File | Purpose |
|--------|------|---------|
| Modify | `src/.../profile/ProfileService.java` | Add saveLearningPath/getSavedLearningPath interface |
| Modify | `src/.../profile/impl/ProfileServiceImpl.java` | Implement path JSON serialization/deserialization |
| Modify | `src/.../learningpath/LearningPathService.java` | Persist path after planPath() |
| Modify | `src/.../learningpath/LearningPathController.java` | Add save/saved endpoints |
| Modify | `src/.../topicpush/api/PushNotifyController.java` | Add notifyMilestone() |
| Modify | `src/.../learninglog/service/LearningBehaviorService.java` | Milestone check after quiz |
| Modify | `frontend/src/views/ResourcePush.vue` | Path display + node click |
| Modify | `frontend/src/views/Chat.vue` | URL param auto-generate |
| Modify | `frontend/src/views/MainLayout.vue` | Milestone SSE handler |
| Modify | `frontend/src/views/Profile.vue` | Path summary card |

---

### Task 1: ProfileService — Path Persistence Interface

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\profile\ProfileService.java`

- [ ] **Step 1: Add interface methods**

At the end of the interface (before closing `}`), add:

```java
    /** Save a learning path JSON to the student's profile. */
    void saveLearningPath(String studentId, String pathJson);

    /** Get the saved learning path JSON from the student's profile. */
    String getSavedLearningPathJson(String studentId);
```

- [ ] **Step 2: Implement in ProfileServiceImpl**

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\profile\impl\ProfileServiceImpl.java`

Add imports:
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
```

Implement the methods. Find the class body and add before the closing `}`:

```java
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public void saveLearningPath(String studentId, String pathJson) {
        Optional<StudentProfile> opt = profileRepo.findById(studentId);
        if (opt.isEmpty()) {
            log.warn("ProfileService: cannot save path — profile not found for {}", studentId);
            return;
        }
        StudentProfile profile = opt.get();
        profile.setLearningPathJson(pathJson);
        profile.setUpdatedAt(LocalDateTime.now());
        profileRepo.save(profile);
        log.info("ProfileService: saved learning path for user={}", studentId);
    }

    @Override
    public String getSavedLearningPathJson(String studentId) {
        return profileRepo.findById(studentId)
            .map(StudentProfile::getLearningPathJson)
            .orElse(null);
    }
```

- [ ] **Step 3: Add learningPathJson field to StudentProfile entity**

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\profile\model\StudentProfile.java`

Add the field and getter/setter:

```java
    @Column(name = "learning_path_json", columnDefinition = "TEXT")
    private String learningPathJson;

    public String getLearningPathJson() { return learningPathJson; }
    public void setLearningPathJson(String learningPathJson) { this.learningPathJson = learningPathJson; }
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/ProfileService.java \
        src/main/java/org/example/educatorweb/profile/impl/ProfileServiceImpl.java \
        src/main/java/org/example/educatorweb/profile/model/StudentProfile.java
git commit -m "feat: add learning_path_json to StudentProfile with save/get service methods"
```

---

### Task 2: Persist Path After Planning

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\learningpath\LearningPathService.java`

- [ ] **Step 1: Add dependencies and persist at end of planPath()**

Add field and update constructor:
```java
    private final ProfileService profileService;
```

The constructor already has `ProfileService profileService` — verify it's injected. If not, add it.

At the end of the `planPath()` method (after `path.setCreatedAt(LocalDateTime.now())`), add:

```java
        // Persist path to student profile for later retrieval
        try {
            String pathJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(path);
            profileService.saveLearningPath(studentId, pathJson);
        } catch (Exception e) {
            log.warn("LearningPathService: failed to persist path: {}", e.getMessage());
        }

        return path;
```

Ensure `planPath()` returns `LearningPath` at the end.

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/learningpath/LearningPathService.java
git commit -m "feat: persist learning path to profile after planning"
```

---

### Task 3: Path Save/Saved API Endpoints

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\learningpath\LearningPathController.java`

- [ ] **Step 1: Add GET saved and POST save endpoints**

After the `updateProgress()` method, add:

```java
    /**
     * Get the saved learning path for a student.
     * GET /api/push/path/{studentId}/saved
     */
    @GetMapping("/path/{studentId}/saved")
    public ResponseResult<Map<String, Object>> getSavedPath(
            @PathVariable String studentId) {
        String json = profileService.getSavedLearningPathJson(studentId);
        if (json == null) {
            return ResponseResult.success(Map.of("exists", false));
        }
        try {
            LearningPath path = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, LearningPath.class);
            return ResponseResult.success(Map.of("exists", true, "path", path));
        } catch (Exception e) {
            return ResponseResult.success(Map.of("exists", false, "error", "parse failed"));
        }
    }
```

Add import: `import org.example.educatorweb.profile.ProfileService;`
Add field: `private final ProfileService profileService;`
Update constructor to include `ProfileService profileService`.

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/learningpath/LearningPathController.java
git commit -m "feat: add GET /path/{id}/saved endpoint for saved learning path"
```

---

### Task 4: Milestone Notification in PushNotifyController

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\topicpush\api\PushNotifyController.java`

- [ ] **Step 1: Add notifyMilestone method**

After the existing `notifyReportUpdated()` method, add:

```java
    /** Fire a proficiency milestone notification (proficiency >= 60%). */
    public void notifyMilestone(String userId, String concept,
                                int proficiencyPct, String nextNode) {
        try {
            String payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of(
                    "type", "PROFICIENCY_MILESTONE",
                    "concept", concept,
                    "proficiency", proficiencyPct,
                    "nextNode", nextNode != null ? nextNode : ""
                ));
            reportUpdateSink.tryEmitNext(userId + "::" + payload);
        } catch (Exception e) {
            log.debug("PushNotifyController: milestone notify skipped: {}", e.getMessage());
        }
    }
```

- [ ] **Step 2: Update SSE subscribe to parse milestone payloads**

The current `reportUpdates` Flux looks like:
```java
Flux<ServerSentEvent<Object>> reportUpdates = reportUpdateSink.asFlux()
    .filter(uid -> uid.equals(studentId))
    .map(uid -> ServerSentEvent.<Object>builder()
        .data(Map.of("type", "REPORT_UPDATED"))
        .build());
```

Replace with:
```java
Flux<ServerSentEvent<Object>> reportUpdates = reportUpdateSink.asFlux()
    .filter(msg -> {
        String uid = msg.contains("::") ? msg.substring(0, msg.indexOf("::")) : msg;
        return uid.equals(studentId);
    })
    .map(msg -> {
        Object data;
        if (msg.contains("::")) {
            String jsonPart = msg.substring(msg.indexOf("::") + 2);
            try {
                data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(jsonPart, Map.class);
            } catch (Exception e) {
                data = Map.of("type", "REPORT_UPDATED");
            }
        } else {
            data = Map.of("type", "REPORT_UPDATED");
        }
        return ServerSentEvent.<Object>builder().data(data).build();
    });
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/topicpush/api/PushNotifyController.java
git commit -m "feat: add PROFICIENCY_MILESTONE SSE notification with nextNode"
```

---

### Task 5: Milestone Check in LearningBehaviorService

**Files:**
- Modify: `E:\educatorweb\educatorweb\src\main\java\org\example\educatorweb\learninglog\service\LearningBehaviorService.java`

- [ ] **Step 1: Add milestone check after quiz results**

In `logQuizResults()`, after the existing `pushNotifyController.notifyReportUpdated(userId)`, add:

```java

        // Check proficiency milestone (>= 60%)
        BigDecimal newProf = kp.getProficiency();
        if (newProf != null && newProf.doubleValue() >= 0.6) {
            // Find next node from saved path
            String nextNode = null;
            try {
                String pathJson = profileRepo.findById(userId)
                    .map(p -> p.getLearningPathJson())
                    .orElse(null);
                if (pathJson != null) {
                    var path = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(pathJson, java.util.Map.class);
                    var nodes = (java.util.List<java.util.Map<String, Object>>) path.get("nodes");
                    if (nodes != null) {
                        for (int i = 0; i < nodes.size(); i++) {
                            String name = (String) nodes.get(i).get("knowledgePointName");
                            if (concept.equals(name) && i + 1 < nodes.size()) {
                                nextNode = (String) nodes.get(i + 1).get("knowledgePointName");
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("LearningBehavior: milestone nextNode lookup failed: {}", e.getMessage());
            }
            int pct = (int) Math.round(newProf.doubleValue() * 100);
            pushNotifyController.notifyMilestone(userId, concept, pct, nextNode);
        }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/educatorweb/learninglog/service/LearningBehaviorService.java
git commit -m "feat: trigger PROFICIENCY_MILESTONE notification when proficiency >= 60%"
```

---

### Task 6: ResourcePush.vue — Path Display + Node Click

**Files:**
- Modify: `E:\educatorweb\educatorweb\frontend\src\views\ResourcePush.vue`

- [ ] **Step 1: Add saved path state and load function**

In `<script setup>`, add after existing state:

```javascript
const savedPath = ref(null)
const savedPathLoading = ref(false)

const loadSavedPath = async () => {
  savedPathLoading.value = true
  try {
    const res = await request.get(`/push/path/${getStudentId()}/saved`)
    const data = res.data?.data
    if (data?.exists) {
      savedPath.value = data.path
    } else {
      savedPath.value = null
    }
  } catch { savedPath.value = null }
  finally { savedPathLoading.value = false }
}

const goLearnFromPath = (node) => {
  const topic = node.knowledgePointName
  window.location.href = '/chat?topic=' + encodeURIComponent(topic) + '&mode=html'
}

const statusLabel = (node) => {
  if (node.status === 'COMPLETED') return '已掌握'
  if (node.status === 'CURRENT') return '学习中'
  return '待学习'
}

const nodeClickable = (node, index) => {
  if (node.status === 'COMPLETED') return true
  if (node.status === 'CURRENT') return true
  // First PENDING after CURRENT is clickable
  const nodes = savedPath.value?.nodes || []
  const currentIdx = nodes.findIndex(n => n.status === 'CURRENT')
  if (currentIdx >= 0 && index === currentIdx + 1) return true
  return false
}
```

- [ ] **Step 2: Add path display template**

Replace the right card (`<!-- ========== 右卡片：自主探索 ========== -->`) with combined explore + path:

```html
      <!-- ========== 右卡片：自主探索 + 学习路径 ========== -->
      <section class="card explore-card">
        <h3 class="card-title">🔍 自主探索</h3>

        <el-input v-model="kpFilterText" placeholder="过滤知识点..." clearable size="small"
          class="kp-filter" :prefix-icon="Search" />

        <el-select v-model="activeCategory" size="small" class="kp-cat-select"
          v-if="kpCategories.length">
          <el-option v-for="cat in kpCategories" :key="cat.name"
            :label="cat.name + ' (' + cat.points.length + ')'" :value="cat.name" />
        </el-select>

        <div class="kp-grid" v-if="filteredPoints.length">
          <span v-for="kp in filteredPoints" :key="kp.id" class="kp-chip"
            @click="handleKpClick(kp)" :title="kp.name + ' · 难度 ' + (kp.difficulty || 3)">
            {{ kp.name }}
          </span>
        </div>
        <div v-else-if="kpCategories.length" class="empty-state">
          <p>没有匹配 "{{ kpFilterText }}" 的知识点</p>
        </div>
      </section>

      <!-- ========== 右卡片下半：学习路径 ========== -->
      <section class="card path-card" style="margin-top:24px">
        <h3 class="card-title">📐 我的学习路径</h3>

        <div v-if="!savedPath" class="empty-state">
          <span class="empty-icon">🗺️</span>
          <p>暂未规划学习路径</p>
          <el-button size="small" type="primary" @click="panelMode='search'; $nextTick(() => handleSearch())">
            搜索知识点规划路径 →
          </el-button>
        </div>

        <template v-else>
          <div class="path-summary-header">
            <span>目标：<strong>{{ savedPath.targetKnowledgePoint }}</strong></span>
            <span>{{ savedPath.completedNodes || 0 }}/{{ savedPath.totalNodes }} 已完成</span>
          </div>

          <div class="path-node-list">
            <div v-for="(node, i) in (savedPath.nodes || [])" :key="i"
              :class="['path-node-item', {
                completed: node.status === 'COMPLETED',
                current: node.status === 'CURRENT',
                locked: !nodeClickable(node, i)
              }]"
              @click="nodeClickable(node, i) && goLearnFromPath(node)">
              <span class="path-node-status">
                {{ node.status === 'COMPLETED' ? '✅' : node.status === 'CURRENT' ? '🔵' : '⚪' }}
              </span>
              <span class="path-node-name">{{ node.knowledgePointName }}</span>
              <span class="path-node-label">{{ statusLabel(node) }}</span>
            </div>
          </div>

          <el-button size="small" text @click="panelMode='search'" style="margin-top:12px">
            🔄 重新规划目标
          </el-button>
        </template>
      </section>
```

- [ ] **Step 3: Add path CSS styles**

At end of `<style scoped>`, add:

```css
.path-card { margin-top: 24px; min-height: 200px; }
.path-summary-header {
  display: flex; justify-content: space-between;
  font-size: 13px; color: #909399; margin-bottom: 12px;
  padding-bottom: 10px; border-bottom: 1px solid #f0f2f5;
}
.path-node-list { display: flex; flex-direction: column; gap: 6px; }
.path-node-item {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border-radius: 10px;
  border: 1px solid #f0f2f5; cursor: pointer;
  transition: all 0.15s; font-size: 14px;
}
.path-node-item:hover:not(.locked) {
  background: #f0eeff; border-color: #667eea; transform: translateX(2px);
}
.path-node-item.completed { background: #f0f9eb; border-color: #c0e0b0; }
.path-node-item.current { background: #eef0ff; border-color: #667eea; font-weight: 600; }
.path-node-item.locked { opacity: 0.4; cursor: not-allowed; pointer-events: none; }
.path-node-status { font-size: 16px; width: 24px; text-align: center; }
.path-node-name { flex: 1; }
.path-node-label { font-size: 12px; color: #909399; }
```

- [ ] **Step 4: Call loadSavedPath in onMounted**

Add `loadSavedPath()` call in `onMounted()`:

```javascript
onMounted(() => {
  loadLatestPush()
  loadContext()
  loadKnowledgePoints()
  loadSavedPath()
  window.addEventListener('push-refresh', refreshHandler)
})
```

Also add import for `request` (should already exist as it's imported via `../api/index.js` — if not, add `import request from '../api/index.js'` or use the existing API functions).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/ResourcePush.vue
git commit -m "feat: add saved learning path display with clickable nodes"
```

---

### Task 7: Chat.vue — URL Param Auto-Generate

**Files:**
- Modify: `E:\educatorweb\educatorweb\frontend\src\views\Chat.vue`

- [ ] **Step 1: Add URL param parsing in script setup**

After the existing `import { ref, computed, nextTick, onMounted } from 'vue'`, add:
```javascript
import { useRoute } from 'vue-router'
```

At the end of `onMounted` (after `loadConversations()`), add:

```javascript
  // Auto-trigger resource generation if topic/mode passed via URL
  const route = useRoute()
  const urlTopic = route.query.topic
  const urlMode = route.query.mode
  if (urlTopic) {
    await nextTick()
    inputText.value = urlTopic
    if (urlMode && modes.some(m => m.key === urlMode)) {
      activeMode.value = urlMode
    }
    await nextTick()
    await sendResourceGenerate(urlTopic)
  }
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/views/Chat.vue
git commit -m "feat: auto-trigger resource generation from URL params (topic + mode)"
```

---

### Task 8: MainLayout.vue — Milestone SSE Handler

**Files:**
- Modify: `E:\educatorweb\educatorweb\frontend\src\views\MainLayout.vue`

- [ ] **Step 1: Add PROFICIENCY_MILESTONE handling**

In the `es.onmessage` handler, after the `if (data.type === 'REPORT_UPDATED')` block, add:

```javascript
        if (data.type === 'PROFICIENCY_MILESTONE') {
          ElNotification({
            title: '🎉 掌握度达标',
            message: `「${data.concept}」掌握度已达 ${data.proficiency}%${data.nextNode ? '，建议继续学习：' + data.nextNode : ''}`,
            type: 'success',
            duration: 8000,
            onClick() {
              if (data.nextNode) {
                window.location.href = '/chat?topic=' + encodeURIComponent(data.nextNode) + '&mode=html'
              }
            }
          })
          return
        }
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/views/MainLayout.vue
git commit -m "feat: handle PROFICIENCY_MILESTONE SSE event with notification"
```

---

### Task 9: Profile.vue — Path Summary Card

**Files:**
- Modify: `E:\educatorweb\educatorweb\frontend\src\views\Profile.vue`

- [ ] **Step 1: Add savedPath state and load function**

In `<script setup>`, after `const reportData = ref(...)`, add:

```javascript
const savedPath = ref(null)

const loadSavedPath = async () => {
  try {
    const res = await request.get(`/push/path/${getStudentId()}/saved`)
    const data = res.data?.data
    savedPath.value = data?.exists ? data.path : null
  } catch { savedPath.value = null }
}
```

Add import: `import request from '../api/index.js'` (or `import request from '@/api/request'`).

- [ ] **Step 2: Add path card in template**

After the tag cloud div in the learning report section, add:

```html
        <!-- 学习路径摘要 -->
        <div class="chart-box" v-if="savedPath" style="margin-top:20px">
          <h4>📐 当前学习路径</h4>
          <p style="color:#909399;font-size:13px;margin:8px 0">
            目标：{{ savedPath.targetKnowledgePoint }} ·
            {{ savedPath.completedNodes || 0 }}/{{ savedPath.totalNodes }} 节点已完成
          </p>
          <el-progress
            :percentage="savedPath.totalNodes ? Math.round((savedPath.completedNodes || 0) / savedPath.totalNodes * 100) : 0"
            :stroke-width="10" color="#667eea" style="margin-bottom:12px" />
          <el-button size="small" @click="$router.push('/push')">查看完整路径 →</el-button>
        </div>
```

- [ ] **Step 3: Call loadSavedPath in loadReport**

In the `loadReport()` function, add at the end:
```javascript
  loadSavedPath()
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/Profile.vue
git commit -m "feat: add learning path summary card to profile report"
```

---

## Verification Checklist

1. Start backend and frontend
2. ResourcePush → search a topic → see path in lower right card
3. Click a path node → Chat opens with topic pre-filled and generation starts automatically
4. Submit a quiz on that topic → proficiency updates
5. After proficiency >= 60% → SSE notification appears with link to next node
6. Profile page → bottom shows path summary card
7. Refresh browser → path persists (loaded from saved API)
