# 学习报告升级 — 设计文档

## 1. 目标

将学习报告从当前"文字列表 + 综合评分"升级为需求文档要求的 5 维评估仪表盘：
知识掌握度（雷达图）、学习进度（进度条）、学习投入度（数字+趋势）、
薄弱环节（标签云）、能力成长趋势（折线图）。

## 2. 数据层

### 2.1 新增表：proficiency_snapshot

```sql
CREATE TABLE proficiency_snapshot (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id  VARCHAR(64)  NOT NULL,
    concept     VARCHAR(255) NOT NULL,
    proficiency DECIMAL(5,4) NOT NULL,
    effective_proficiency DECIMAL(5,4) NOT NULL,
    snapshot_date DATE NOT NULL,
    UNIQUE (student_id, concept, snapshot_date)
);
```

- 每个知识点每天只存一条（UNIQUE 约束）
- 写入时机：`ProficiencyService.recordAnswer()` 末尾，每次答题后 upsert 当天快照
- 读取方式：`findByStudentIdAndSnapshotDateBetween()` 查时间范围内的所有快照

### 2.2 复用表现有表

| 表 | 报告如何使用 |
|----|-------------|
| `student_knowledge_proficiency` | 雷达图数据源（全部概念 + proficiency） |
| `learning_behavior_log` | 投入度聚合（按 userId + eventType 计数和时长求和） |
| `student_profile` | 六维画像展示（不变） |

## 3. 后端

### 3.1 新增 Entity & Repository

- `ProficiencySnapshot.java` — JPA entity，复合唯一约束 `(studentId, concept, snapshotDate)`
- `ProficiencySnapshotRepository.java` — `findByStudentIdAndSnapshotDateBetween()`

### 3.2 修改 ProficiencyService

`recordAnswer()` 方法末尾追加：

```java
// 写入当日 proficiency 快照
ProficiencySnapshot snapshot = new ProficiencySnapshot(
    studentId, concept,
    proficiency.getProficiency(),
    effectiveProficiency,
    LocalDate.now()
);
snapshotRepo.save(snapshot); // UNIQUE 约束下自动 upsert
```

### 3.3 扩展 LearningReportService

从单体 `generateProfileSummary()` 拆为独立方法，各读各的数据源：

| 方法 | 数据源 | 输出 |
|------|--------|------|
| `buildKnowledgeRadar(studentId)` | `student_knowledge_proficiency` 全部行 | `List<RadarPoint>` |
| `buildLearningProgress(studentId)` | `LearningPathService.planPath()` | `ProgressInfo` |
| `buildLearningInput(studentId)` | `learning_behavior_log` 聚合 | `InputInfo` |
| `buildGrowthTrend(studentId)` | `proficiency_snapshot` 近 8 周 | `List<WeekPoint>` |
| `buildWeakPoints(studentId)` | 现有逻辑 | `List<WeakPoint>`（不变） |
| `buildProfileSummary(...)` | 调用以上各方法汇总 | 合并的 `Map<String,Object>` |

### 3.4 GET /api/profile/{studentId}/summary 返回结构

```json
{
  "compositeScore": 0.72,
  "compositeLabel": "良好",
  "weakPoints": [{"concept":"SVM","proficiency":0.42}],
  "strongPoints": [{"concept":"线性回归","proficiency":0.91}],
  "profile": {...六维画像...},

  "knowledgeRadar": [
    {"concept":"梯度下降","proficiency":0.85},
    {"concept":"SVM","proficiency":0.62}
  ],
  "learningProgress": {
    "totalNodes": 25, "completedNodes": 12,
    "currentNode": "决策树"
  },
  "learningInput": {
    "activeDays": 15,
    "totalDurationMin": 320,
    "resourceViews": 28,
    "chatRounds": 42,
    "quizTotal": 68,
    "weeklyTrend": [
      {"week":"W28","duration":80,"quizzes":15}
    ]
  },
  "growthTrend": [
    {"week":"W23","avgProficiency":0.45},
    {"week":"W24","avgProficiency":0.52}
  ]
}
```

所有 JSON key 使用 camelCase，前端直接消费。

### 3.5 SSE 推送

在 `PushNotifyController` 中新增事件类型 `REPORT_UPDATED`。触发条件：
- 任何 `POST /api/log/quiz/submit` 返回后
- 任何 `POST /api/quiz/submit` 返回后
- `maybeAdjustProfile()` 执行后

前端 `MainLayout.vue` 的 SSE 处理器增加 `REPORT_UPDATED` 分支，dispatch 自定义事件
`window.dispatchEvent(new CustomEvent("report-updated"))`。
Profile.vue 监听此事件，触发 `loadReport()` 重新拉取。

## 4. 前端

### 4.1 新增依赖

```json
{
  "echarts": "^5.5.0",
  "vue-echarts": "^7.0.0"
}
```

按需引入：`RadarChart`、`LineChart`、`BarChart`，不引入完整 echarts。

### 4.2 Profile.vue 布局重构

```
┌─────────────────────────────────────────────┐
│  📊 学习报告 · 综合评分 72 分                  │
├──────────────────┬──────────────────────────┤
│   🎯 知识掌握度   │  📈 能力成长趋势           │
│   (雷达图)       │  (折线图, 近8周)          │
│   所有知识点      │  avgProficiency          │
├──────────────────┼──────────────────────────┤
│  ⏱ 学习投入度     │  📂 学习进度              │
│  (4个数字卡片     │  (进度条 + 当前节点)      │
│   + 近4周柱状图)  │                         │
├──────────────────┴──────────────────────────┤
│  📉 薄弱环节 (CSS 标签云, 按 proficiency 着色) │
├─────────────────────────────────────────────┤
│  🧠 六维画像 (保持现有展示)                   │
└─────────────────────────────────────────────┘
```

### 4.3 图表组件选型

| 图表 | ECharts 组件 | 数据来源 |
|------|-------------|---------|
| 雷达图 | `RadarChart` | `knowledgeRadar[]` |
| 折线图 | `LineChart` | `growthTrend[]` |
| 柱状图 | `BarChart` | `learningInput.weeklyTrend[]` |
| 进度条 | Element Plus `<el-progress>` | `learningProgress` |
| 标签云 | 纯 CSS（v-for span with dynamic font-size/color） | `weakPoints[]` |
| 数字卡片 | 纯 div + css | `learningInput` 各项 |

### 4.4 实时更新

```javascript
// MainLayout.vue - SSE handler 新增
if (event === 'REPORT_UPDATED') {
    window.dispatchEvent(new CustomEvent('report-updated'))
}

// Profile.vue - onMounted
window.addEventListener('report-updated', loadReport)
```

### 4.5 兼容现有数据

- `knowledgeRadar` 全部知识点 proficiency 做雷达图，空数据时显示占位提示
- `growthTrend` 历史快照不足 1 周时只显示已有数据点
- `learningInput` 无数据时为 0，不报错
- SSE 推送失败不影响手动刷新（已有的"刷新"按钮保留）

## 5. 影响范围总结

| 层 | 文件 | 改动性质 |
|----|------|---------|
| DB | 新增 `proficiency_snapshot` 表 | DDL |
| Java | 新增 `ProficiencySnapshot` entity | 新文件 |
| Java | 新增 `ProficiencySnapshotRepository` | 新文件 |
| Java | `ProficiencyService.recordAnswer()` 加 2 行 | 增量 |
| Java | `LearningReportService` 拆为 5 方法 | 重构内部 + 扩展返回值 |
| Java | `PushNotifyController` 加 `REPORT_UPDATED` 事件 | 增量 |
| 前端 | 新增 `echarts` + `vue-echarts` 依赖 | 新依赖 |
| 前端 | `Profile.vue` 布局重构 + 图表 | 替换展示区 |
| 前端 | `MainLayout.vue` SSE 新增事件分支 | 增量 |

**不受影响的部分**：AI 辅导、资源推送、测验提交流程、学习路径规划、任何现有 API 签名。

## 6. 风险与对策

| 风险 | 对策 |
|------|------|
| 历史数据无 proficiency 快照，趋势图空白 | 首次部署时,`ProficiencyService` 主动读现有 proficiency 生成一条初始快照（当天日期），后续自然累积 |
| 知识点过多（5000+）导致雷达图不可读 | 默认选取 proficiency 最高 8 个 + 最低 8 个，或按最近学习的 top 12 |
| ECharts 包体积影响加载速度 | 按需引入 RadarChart/LineChart/BarChart，不 import 完整 echarts |
| SSE 并发推送性能 | `REPORT_UPDATED` 仅广播一个轻量事件，不含数据，前端收到后自行拉取 |
