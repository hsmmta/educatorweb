# 学习路径可交互升级 — 设计文档

## 1. 目标

解决当前学习路径"规划完无法使用"的问题，实现三个核心能力：

1. **路径持久化**：规划后的路径保存在个人中心，可随时查看和恢复
2. **节点一键生成资源**：点击路径节点自动跳转资源生成页（主题预填 + 模式预选）
3. **掌握度智能提示**：知识点 proficiency ≥ 60% 时弹通知推荐继续学下一节点

## 2. 数据层

### 2.1 student_profile 新增字段

```sql
ALTER TABLE student_profile ADD COLUMN learning_path_json TEXT;
```

存储最近一次规划的完整路径 JSON（`LearningPath` 对象序列化）。每次调用 `planPath()` 后更新。

## 3. 后端

### 3.1 路径持久化

**LearningPathService.planPath()** 末尾增加：

```java
// 持久化路径到画像
profileService.saveLearningPath(studentId, path);
```

**ProfileService** 新增方法：
```java
void saveLearningPath(String studentId, LearningPath path);
LearningPath getSavedLearningPath(String studentId);
```

内部用 `ObjectMapper` 序列化/反序列化 `student_profile.learning_path_json`。

### 3.2 保存路径 API

新增 `POST /api/push/path/{studentId}/save`：
```json
// Request body: LearningPath JSON
// Response: { "saved": true }
```

前端点击"重新规划"后调用此接口覆盖保存。

### 3.3 获取已保存路径 API

新增 `GET /api/push/path/{studentId}/saved`：
```json
// Response: { "path": LearningPath, "savedAt": "2026-07-16T10:00:00" }
```

Profile.vue 学习报告区域也调这个接口展示路径摘要。

### 3.4 掌握度里程碑 SSE

**PushNotifyController** 新增：
```java
public void notifyMilestone(String userId, String concept,
                            double proficiency, String nextNode) {
    reportUpdateSink.tryEmitNext(userId + "::" +
        objectMapper.writeValueAsString(Map.of(
            "type", "PROFICIENCY_MILESTONE",
            "concept", concept,
            "proficiency", Math.round(proficiency * 100),
            "nextNode", nextNode != null ? nextNode : ""
        )));
}
```

**LearningBehaviorService.logQuizResults()** 末尾在 `notifyReportUpdated()` 之前增加判断：
```java
BigDecimal newProf = kp.getProficiency();
if (newProf != null && newProf.doubleValue() >= 0.6) {
    // Find next node in saved path
    String nextNode = null;
    LearningPath savedPath = profileService.getSavedLearningPath(userId);
    if (savedPath != null && savedPath.getNodes() != null) {
        for (int i = 0; i < savedPath.getNodes().size(); i++) {
            if (concept.equals(savedPath.getNodes().get(i).getKnowledgePointName())
                && i + 1 < savedPath.getNodes().size()) {
                nextNode = savedPath.getNodes().get(i + 1).getKnowledgePointName();
                break;
            }
        }
    }
    pushNotifyController.notifyMilestone(userId, concept,
        newProf.doubleValue(), nextNode);
}
```

SSE 数据流复用现有 `reportUpdateSink`，payload 中加入 `type: "PROFICIENCY_MILESTONE"` 区分普通报告更新。

## 4. 前端

### 4.1 ResourcePush.vue — 路径持久化展示

**右卡片下半部分**新增"我的学习路径"区域（替换或扩展当前自主探索区）：

```
📐 我的学习路径
目标：机器学习 · 8 节点 · 已完成 2
┌──────────────────────────────┐
│ ✅ 线性回归          [已掌握]  │ 灰色，不可点击
│ 🔵 梯度下降          [学习中]  │ 高亮，可点击
│ ⚪ 逻辑回归          [待学习]  │ 可点击
│ ⚪ SVM              [待学习]  │ 锁定，不可点击
└──────────────────────────────┘
[🔄 重新规划目标]
```

数据来源：
- `onMounted` 时调 `GET /api/push/path/{studentId}/saved` 加载
- 如果没有已保存路径，显示"暂未规划学习路径，去搜索 →"
- 节点状态判断：
  - `COMPLETED`（proficiency ≥ 0.8）→ ✅ 已掌握
  - `CURRENT` → 🔵 学习中（当前节点）
  - `PENDING` 且是紧接 CURRENT 之后的第一个 → ⚪ 待学习
  - `PENDING` 且前面有未完成节点 → 锁定，不可点击

### 4.2 ResourcePush.vue — 节点点击 → Chat.vue

```javascript
function goLearnFromPath(node) {
  const topic = node.knowledgePointName
  const mode = 'html'  // 默认交互课件
  window.location.href = `/chat?topic=${encodeURIComponent(topic)}&mode=${mode}`
}
```

### 4.3 Chat.vue — URL 参数自动生成

在 `onMounted` / `setup` 中解析 URL query：

```javascript
import { useRoute } from 'vue-router'
const route = useRoute()

onMounted(async () => {
  await loadConversations()
  
  const topic = route.query.topic
  const mode = route.query.mode
  if (topic) {
    inputText.value = topic
    if (mode) activeMode.value = mode
    // 自动触发资源生成
    await nextTick()
    await sendResourceGenerate(topic)
  }
})
```

这样用户打开 `/chat?topic=梯度下降&mode=html` 后自动开始生成交互课件，无需手动操作。

### 4.4 MainLayout.vue — 里程碑通知

SSE `onmessage` 中新增 `PROFICIENCY_MILESTONE` 处理：

```javascript
if (data.type === 'PROFICIENCY_MILESTONE') {
  ElNotification({
    title: '🎉 掌握度达标',
    message: `「${data.concept}」掌握度已达 ${data.proficiency}%${data.nextNode ? '，建议继续学习：' + data.nextNode : ''}`,
    type: 'success',
    duration: 8000,
    onClick() {
      if (data.nextNode) {
        window.location.href = `/chat?topic=${encodeURIComponent(data.nextNode)}&mode=html`
      }
    }
  })
  return
}
```

### 4.5 Profile.vue — 学习报告中的路径摘要

在学习报告仪表盘底部新增"学习路径"卡片：

```html
<div class="chart-box" v-if="savedPath">
  <h4>📐 当前学习路径</h4>
  <p>{{ savedPath.targetKnowledgePoint }} · 
     {{ savedPath.completedNodes }}/{{ savedPath.totalNodes }} 已完成</p>
  <el-progress :percentage="savedPath.totalNodes ? Math.round(savedPath.completedNodes/savedPath.totalNodes*100) : 0" />
  <el-button size="small" @click="$router.push('/push')">查看详情 →</el-button>
</div>
```

## 5. 节点可点击规则

| 节点状态 | 含义 | 可点击？ |
|---------|------|---------|
| COMPLETED | proficiency ≥ 0.8 | 是（复习模式） |
| CURRENT | 第一个未完成节点 | 是（学习模式） |
| PENDING（紧接 CURRENT） | 下一步待学 | 是（预览模式） |
| PENDING（前面有未完成） | 被锁定 | 否，灰色 |

## 6. 影响范围

| 文件 | 改动 |
|------|------|
| `student_profile` 表 | 新增 `learning_path_json TEXT` |
| `LearningPathService.java` | `planPath()` 末尾调用持久化 |
| `ProfileService.java` | 新增 `saveLearningPath/getSavedLearningPath` |
| `LearningPathController.java` | 新增 `POST /save` 和 `GET /saved` 端点 |
| `PushNotifyController.java` | 新增 `notifyMilestone()` 方法 |
| `LearningBehaviorService.java` | `logQuizResults()` 末尾加里程碑判断 |
| `ResourcePush.vue` | 右卡片改为路径展示 + 节点点击跳转 |
| `Chat.vue` | `onMounted` 解析 URL 参数自动生成 |
| `MainLayout.vue` | SSE 新增 `PROFICIENCY_MILESTONE` 处理 |
| `Profile.vue` | 报告底部加路径摘要卡片 |

## 7. 风险

| 风险 | 对策 |
|------|------|
| `learning_path_json` 序列化字段变更 | 读取时 try-catch，反序列化失败返回 null，触发重新规划 |
| 路径节点名称与 proficiency 概念名不一致 | 匹配时用 `equalsIgnoreCase` |
| 自动生成触发太快用户无感知 | Chat.vue 保留 loading 状态 + 进度条，用户可见生成过程 |
