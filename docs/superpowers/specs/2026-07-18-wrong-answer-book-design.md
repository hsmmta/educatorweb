# 答题评估提示 + 错题集 — 设计文档

## 1. 目标

### 子功能 A：答题后评估加载提示
用户答完题后系统需要 2-5 秒完成掌握度更新 + LLM 易错分析，当前无任何反馈。加上 loading 提示，评估完成后自动消失。

### 子功能 B：错题集
收集用户答错的题目，支持回顾和重做（不记入测评），存放在学习报告页面。

---

## 2. 子功能 A — 评估加载提示

### 2.1 前端改动

`Chat.vue` 底部新增评估状态条：

```html
<div v-if="assessing" class="assess-bar">
  <span class="assess-spinner">🧠</span> 正在评估你的答题表现...
</div>
```

**触发时机**：`selectQuizOption()` 中，调用 `submitQuizAnswer()` 的同时设置 `assessing = true`，await 完成后设回 `false`。

**视觉效果**：底部固定条，半透明渐变底 + emoji 脉冲动画，3-5 秒后自动消失。不阻塞其他交互。

### 2.2 不涉及后端改动

---

## 3. 子功能 B — 错题集

### 3.1 数据模型

新建 `wrong_answer_book` 表（MySQL）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT PK | |
| student_id | VARCHAR(64) NOT NULL | |
| question | TEXT NOT NULL | 题干 |
| options | JSON NOT NULL | 所有选项数组 |
| user_answer | VARCHAR(10) NOT NULL | 用户选的（如 "A", "True"） |
| correct_answer | VARCHAR(10) NOT NULL | 正确答案 |
| knowledge_point | VARCHAR(256) | 所属知识点 |
| quiz_title | VARCHAR(256) | 来源测验标题 |
| created_at | DATETIME NOT NULL DEFAULT NOW() | |

JPA Entity: `WrongAnswer.java`，Repository: `WrongAnswerRepository.java`。

### 3.2 后端 API

**`POST /api/quiz/wrong-answer`**
```json
{
  "studentId": "xxx",
  "question": "...",
  "options": ["A. ...", "B. ..."],
  "userAnswer": "B",
  "correctAnswer": "C",
  "knowledgePoint": "机器学习简介",
  "quizTitle": "机器学习简介 - 练习题库"
}
```
返回: `{ "id": 1 }`

**`GET /api/quiz/wrong-answers/{studentId}`**
返回: `[{ id, question, options, userAnswer, correctAnswer, knowledgePoint, quizTitle, createdAt }]`

**`DELETE /api/quiz/wrong-answers/{studentId}`**（清空全部错题）

### 3.3 前端收集逻辑

`Chat.vue` 的 `submitQuizAnswer()` 中，当 `correct === false` 时，额外调用：
```javascript
request.post('/quiz/wrong-answer', {
  studentId: getStudentId(),
  question: q.question,
  options: q.options,
  userAnswer: letter,
  correctAnswer: q.answer,
  knowledgePoint: primaryConcept,
  quizTitle: msg.title
})
```

### 3.4 前端展示

**位置**：Profile.vue 学习报告下方，新增「📝 错题集」section。

**卡片布局**：
```
┌─ 📝 错题集 (3 道) ─────────────────────────────┐
│                                                  │
│  [#卡片]                                         │
│  题干：机器学习的核心要素包括？                  │
│  你的答案: B. 模型+数据        ❌               │
│  正确答案: C. 数据+算法+算力     ✅              │
│  来源: 机器学习简介 · 2天前                      │
│  [🙈 不看答案重做] 切换显示/隐藏答案             │
│                                                  │
│  [重做模式]
│  选项可点击 → 选完后显示 ✓/✗                   │
│  → 不发送任何后端请求                           │
│  → 卡片显示 "已重做 ✓" 标记，但不消失           │
└──────────────────────────────────────────────────┘
```

**交互细节**：
- 默认显示答案（`showAnswer: true`），点击「不看答案重做」切换为纯选项
- 重做模式下选项可点击，选完后显示对错标记，不调任何 API
- 重做完标记 `redone: true`，显示 "已重做 ✓"，卡片保留
- 列表按时间倒序，最多展示 50 条，支持滚动

### 3.5 LocalStorage 缓存

错题列表在进入页面时从 API 拉取，缓存到 `localStorage`。后续重做状态（`redone`、`showAnswer`）存储在本地，不提交后端。页面刷新时从 API 重新拉取基础数据，本地状态丢失（`redone` 和 `showAnswer` 重置）。

---

## 4. 文件影响范围

| 文件 | 改动 |
|------|------|
| `Chat.vue` | 新增评估状态条 (`assess-bar`)，`submitQuizAnswer()` 中新增错题收集调用 |
| `WrongAnswer.java` (新增) | JPA Entity |
| `WrongAnswerRepository.java` (新增) | JPA Repository |
| `QuizSubmitController.java` | 新增 `POST /wrong-answer`, `GET /wrong-answers/{id}`, `DELETE /wrong-answers/{id}` |
| `Profile.vue` | 新增错题集 section（模板 + CSS + JS 数据加载） |

---

## 5. 不涉及

- 重做不记入 proficiency / 画像 / 学习报告
- 不清除已有数据
- 不影响现有 quiz 提交流程
