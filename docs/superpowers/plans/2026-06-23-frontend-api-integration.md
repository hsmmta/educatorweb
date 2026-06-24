# 前端-后端 API 对接修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将前端 5 个功能页面（私有智库、AI 助教、沉浸学习、学习画像）从本地模拟切换为真实后端 API 调用，修复 URL / 请求体 / 响应体不一致问题。

**Architecture:** 提取共享 axios 实例到 `api/request.js`，各功能页面通过它调用后端 REST API。SSE 流式接口用原生 `fetch` + `EventSource` 处理。`ResourcePush.vue` 暂不处理（后端无对应接口）。

**Tech Stack:** Vue 3 + Axios + Element Plus，后端 Spring Boot WebFlux REST API，Vite dev proxy `/api` → `localhost:8080`

---

## File Map

| File | Role |
|------|------|
| `frontend/src/api/request.js` | **Create**: 共享 axios 实例（从 auth.js 提取） |
| `frontend/src/api/auth.js` | **Modify**: import request from `./request` |
| `frontend/src/views/ThinkTank.vue` | **Modify**: 对接 `POST /api/rag/documents` multipart 上传 |
| `frontend/src/views/Tutoring.vue` | **Modify**: 对接 `POST /api/tutor/chat` JSON 接口 |
| `frontend/src/views/Learning.vue` | **Modify**: 对接 SSE `POST /api/generate` 流式接口 |
| `frontend/src/views/Profile.vue` | **Modify**: 对接 `GET /api/profile/{studentId}` 获取画像 |

---

### Task 0: 合并 master 到 knowledge-graph

确保 `AiTutorController`（`/api/tutor/chat`）和 `ProfileController`（`/api/profile/*`）在当前分支可用。

- [ ] **Step 1: 合并 master**

```bash
git merge master --no-edit
```

如有冲突，以 master 为主解决。

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && export PATH="$JAVA_HOME/bin:$PATH"
mvn compile -DskipTests -q
```

Expected: 无 `[ERROR]`

- [ ] **Step 3: Commit（如有冲突修复）**

```bash
git add -A && git commit -m "merge: bring master into knowledge-graph for API controllers"
```

---

### Task 1: 提取共享 axios 实例

**Files:**
- Create: `frontend/src/api/request.js`
- Modify: `frontend/src/api/auth.js`

- [ ] **Step 1: 创建 `request.js`**

```js
// frontend/src/api/request.js
import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export default request
```

- [ ] **Step 2: 修改 `auth.js` 使用共享实例**

替换 `frontend/src/api/auth.js` 全部内容为：

```js
import request from './request'

// 认证相关
export const registerApi = (data) => request.post('/auth/register', data)
export const loginApi = (data) => request.post('/auth/login', data)

// 用户资料
export const updateProfileApi = (data) => request.put('/auth/profile', data)
export const changePasswordApi = (data) => request.put('/auth/password', data)
```

- [ ] **Step 3: 验证前端能启动**

```bash
cd frontend && npm run build 2>&1 | tail -5
```

Expected: build 成功，无 import 错误

- [ ] **Step 4: Commit**

```bash
git add frontend/src/api/request.js frontend/src/api/auth.js
git commit -m "refactor(frontend): extract shared axios instance to api/request.js"
```

---

### Task 2: ThinkTank.vue — 对接 RAG 文档上传

**Files:**
- Modify: `frontend/src/views/ThinkTank.vue`

**后端接口：** `POST /api/rag/documents`
- Content-Type: `multipart/form-data`
- Form field: `file` (FilePart)
- Query param: `knowledgePoint` (optional, default `""`)
- Response: `{ "filename": "xxx.pdf", "chunks": 12, "status": "ok" }`

- [ ] **Step 1: 修改 ThinkTank.vue `<script setup>` 部分**

替换 `<script setup>` 整个块（第 85-141 行）为：

```js
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload, UploadFilled, MoreFilled } from '@element-plus/icons-vue'
import request from '@/api/request'

const showUpload = ref(false)
const uploading = ref(false)
const pendingFiles = ref([])
const materials = ref([])

const fileIcon = (type) => {
  const map = { pdf: '📕', doc: '📘', docx: '📘', ppt: '📊', pptx: '📊', md: '📝', txt: '📄', png: '🖼️', jpg: '🖼️' }
  return map[type] || '📎'
}

const handleFileChange = (file) => {
  pendingFiles.value.push(file)
}

const confirmUpload = async () => {
  if (pendingFiles.value.length === 0) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  let successCount = 0
  try {
    for (const f of pendingFiles.value) {
      const formData = new FormData()
      formData.append('file', f.raw)
      const res = await request.post('/rag/documents', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 60000
      })
      const data = res.data
      materials.value.push({
        id: Date.now() + Math.random(),
        name: data.filename || f.name,
        type: f.name.split('.').pop().toLowerCase(),
        size: `${data.chunks || 0} 个文本块`
      })
      successCount++
    }
    ElMessage.success(`成功上传 ${successCount} 个文件`)
    showUpload.value = false
    pendingFiles.value = []
  } catch (e) {
    const msg = e.response?.data?.error || '上传失败，请稍后重试'
    ElMessage.error(msg)
  } finally {
    uploading.value = false
  }
}

const formatSize = (bytes) => {
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + 'KB'
  return (bytes / 1048576).toFixed(1) + 'MB'
}
```

- [ ] **Step 2: 验证前端构建**

```bash
cd frontend && npm run build 2>&1 | tail -5
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/ThinkTank.vue
git commit -m "feat(frontend): connect ThinkTank to POST /api/rag/documents upload"
```

---

### Task 3: Tutoring.vue — 对接 AI 助教

**Files:**
- Modify: `frontend/src/views/Tutoring.vue`

**后端接口：** `POST /api/tutor/chat`
- Request: `{ "studentId": "string", "question": "string", "conversationId": "optional" }`
- Response (直接返回，无 ResponseResult 包装):
  ```json
  {
    "conversationId": "uuid",
    "answer": "回答内容",
    "sources": [{"text": "...", "source": "...", "score": 0.85}],
    "timestamp": "2026-06-23T..."
  }
  ```

- [ ] **Step 1: 修改 Tutoring.vue `<script setup>` 部分**

替换 `<script setup>` 整个块（第 84-158 行）为：

```js
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, CircleCheckFilled, Loading, DocumentCopy, RefreshRight, CircleCheck } from '@element-plus/icons-vue'
import request from '@/api/request'

const question = ref('')
const asking = ref(false)
const answer = ref('')
const answerSourceLabel = ref('')
const answerSourceType = ref('info')
const materialCount = ref(0)
const retrievalSteps = ref([])
const conversationId = ref(null)
const answerSources = ref([])

/** Get studentId from login info stored in localStorage */
const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.studentId || 'anonymous'
  } catch { return 'anonymous' }
}

const askQuestion = async () => {
  if (!question.value.trim()) return
  asking.value = true
  answer.value = ''
  answerSources.value = []
  retrievalSteps.value = [
    { id: 1, text: '正在检索知识库并生成回答...', status: 'loading', source: '' }
  ]

  try {
    const res = await request.post('/tutor/chat', {
      studentId: getStudentId(),
      question: question.value,
      conversationId: conversationId.value
    })

    const data = res.data
    conversationId.value = data.conversationId

    // Build answer HTML
    answer.value = `<p>${(data.answer || '').replace(/\n/g, '<br>')}</p>`

    // Show sources if available
    answerSources.value = data.sources || []
    if (answerSources.value.length > 0) {
      answerSourceLabel.value = `RAG · ${answerSources.value.length} 条参考`
      answerSourceType.value = 'success'
    } else {
      answerSourceLabel.value = 'AI 回答'
      answerSourceType.value = 'info'
    }

    retrievalSteps.value = [
      { id: 1, text: '知识库检索完成', status: 'done', source: `${answerSources.value.length} 条参考文档` }
    ]
  } catch (e) {
    ElMessage.error('提问失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
    retrievalSteps.value = [
      { id: 1, text: '请求失败', status: 'done', source: '' }
    ]
  } finally {
    asking.value = false
    question.value = ''
  }
}
```

- [ ] **Step 2: 在 `<template>` 的回答区域后追加参考来源展示**

在 `answer-footer` div 闭合标签 `</div>` 之后、`answer-card` 闭合标签 `</div>` 之前（第 78 行与第 79 行之间），插入：

```html
        <div v-if="answerSources.length > 0" class="answer-refs">
          <h4>📎 参考来源</h4>
          <div v-for="(src, i) in answerSources" :key="i" class="ref-item">
            <span class="ref-idx">[{{ i + 1 }}]</span>
            <span class="ref-text">{{ src.text?.substring(0, 120) }}...</span>
            <span class="ref-source">{{ src.source }}</span>
          </div>
        </div>
```

- [ ] **Step 3: 在 `<style scoped>` 末尾（`</style>` 之前）追加参考来源样式**

```css
.answer-refs { margin-top: 16px; padding-top: 16px; border-top: 1px solid #f2f3f7; }
.answer-refs h4 { font-size: 13px; color: #8890a0; margin: 0 0 10px; font-weight: 500; }
.ref-item { display: flex; gap: 8px; font-size: 12px; color: #606266; margin-bottom: 6px; }
.ref-idx { color: #667eea; font-weight: 600; flex-shrink: 0; }
.ref-text { flex: 1; line-height: 1.5; }
.ref-source { color: #909399; flex-shrink: 0; }
```

- [ ] **Step 4: 验证前端构建**

```bash
cd frontend && npm run build 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/Tutoring.vue
git commit -m "feat(frontend): connect Tutoring to POST /api/tutor/chat"
```

---

### Task 4: Learning.vue — 对接 SSE 多模态生成

**Files:**
- Modify: `frontend/src/views/Learning.vue`

**后端接口：** `POST /api/generate` (SSE `text/event-stream`)
- Request: `{ "studentId": "string", "knowledgePoint": "string", "types": ["DOC"] }`
- SSE events: `data: { "requestId":"...","stage":"INIT|REQUIRE|DESIGN|GENERATING|REVIEWING|DONE","message":"...","progressPercent":0-100,"payload":{...} }`
- ResourceType enum values: `DOC`, `MINDMAP`, `QUIZ`, `PPT`, `CODE`, `HTML`, `VIDEO`

前端 `selectedType` 值为小写 (`doc`, `ppt` 等)，需转大写传给后端。

- [ ] **Step 1: 修改 Learning.vue `<script setup>` 部分**

替换 `<script setup>` 整个块（第 93-160 行）为：

```js
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, CircleCheckFilled, Loading, Download } from '@element-plus/icons-vue'

const selectedType = ref('doc')
const topic = ref('')
const context = ref('')
const difficulty = ref('intermediate')
const generating = ref(false)
const agentSteps = ref([])
const result = ref(null)

const resourceTypes = [
  { type: 'doc',     icon: '📄', label: '课程文档', desc: '结构化讲解文档' },
  { type: 'ppt',     icon: '📊', label: '教学PPT', desc: '演示文稿课件' },
  { type: 'quiz',    icon: '📝', label: '练习题库', desc: '选择题+简答题' },
  { type: 'mindmap', icon: '🧩', label: '思维导图', desc: '知识结构可视化' },
  { type: 'video',   icon: '🎬', label: '教学视频', desc: '多模态动画讲解' },
  { type: 'code',    icon: '💻', label: '代码案例', desc: '可运行示例代码' },
  { type: 'html',    icon: '🌐', label: '交互课件', desc: '网页交互式学习' }
]

const agents = [
  { name: 'RequireAgent',  avatar: '🔍', desc: '需求分析：收集学习者画像与知识背景' },
  { name: 'DesignAgent',   avatar: '🎨', desc: '方案设计：规划资源结构与内容大纲' },
  { name: 'Generator',     avatar: '⚙️', desc: '内容生成：调用模型生成核心内容' },
  { name: 'ReviewAgent',   avatar: '🛡️', desc: '质量审核：内容安全过滤与事实核查' }
]

/** Map SSE stage to the agent step index */
const stageToIdx = { INIT: -1, REQUIRE: 0, DESIGN: 1, GENERATING: 2, REVIEWING: 3, DONE: 4, FALLBACK: 4 }

const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.studentId || 'anonymous'
  } catch { return 'anonymous' }
}

const startGenerate = async () => {
  if (!topic.value.trim()) return
  generating.value = true
  agentSteps.value = []
  result.value = null

  const token = localStorage.getItem('token') || ''
  const body = JSON.stringify({
    studentId: getStudentId(),
    knowledgePoint: topic.value,
    types: [selectedType.value.toUpperCase()]
  })

  try {
    const response = await fetch('/api/generate', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'Authorization': token ? `Bearer ${token}` : ''
      },
      body
    })

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // Parse SSE lines
      const lines = buffer.split('\n')
      buffer = lines.pop() // keep incomplete line in buffer
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const json = line.substring(5).trim()
        if (!json) continue
        try {
          const evt = JSON.parse(json)
          handleSseEvent(evt)
        } catch { /* skip unparseable */ }
      }
    }
  } catch (e) {
    ElMessage.error('生成失败：' + (e.message || '请稍后重试'))
  } finally {
    generating.value = false
  }
}

const handleSseEvent = (evt) => {
  const stage = evt.stage || ''
  const idx = stageToIdx[stage] ?? -1

  // Update agent steps visualization
  if (idx >= 0 && idx < agents.length) {
    // Mark all previous steps as done
    agentSteps.value = agents.slice(0, idx + 1).map((a, i) => ({
      name: a.name, avatar: a.avatar, desc: a.desc,
      status: i < idx ? 'done' : 'loading'
    }))
  }

  if (stage === 'DONE' || stage === 'FALLBACK') {
    // Mark all steps done
    agentSteps.value = agents.map(a => ({
      name: a.name, avatar: a.avatar, desc: a.desc, status: 'done'
    }))

    // Extract result from payload
    const typeKey = selectedType.value.toUpperCase()
    const payload = evt.payload || {}
    const item = payload[typeKey] || Object.values(payload)[0]
    if (item) {
      result.value = {
        title: item.title || `${topic.value} - 学习资源`,
        type: item.type || typeKey,
        size: '',
        preview: `<pre style="white-space:pre-wrap;max-height:400px;overflow:auto;">${escapeHtml(item.content || '生成完成')}</pre>`
      }
    } else {
      result.value = {
        title: `${topic.value} - 学习资源`,
        type: typeKey,
        size: '',
        preview: `<p>✅ ${evt.message || '生成完成'}</p>`
      }
    }

    if (stage === 'DONE') ElMessage.success('资源生成完成')
    if (stage === 'FALLBACK') ElMessage.warning(evt.message || '生成降级完成')
  }
}

const escapeHtml = (s) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
```

- [ ] **Step 2: 验证前端构建**

```bash
cd frontend && npm run build 2>&1 | tail -5
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/Learning.vue
git commit -m "feat(frontend): connect Learning to SSE POST /api/generate"
```

---

### Task 5: Profile.vue — 对接学习者画像

**Files:**
- Modify: `frontend/src/views/Profile.vue`

**后端接口：** `GET /api/profile/{studentId}`
- Response: `StudentProfile` 对象，字段包括：
  - `studentId`, `knowledgeBaseLevel`, `knowledgeBaseConfidence`
  - `cognitiveStyleType`, `cognitiveStyleConfidence`
  - `errorPatternTags` (string[]), `errorPatternConfidence`
  - `learningPaceType`, `learningPaceConfidence`
  - `contentPreferenceType`, `contentPreferenceRatio` (Map)
  - `goalOrientationType`, `goalOrientationConfidence`

**后端接口：** `POST /api/profile/analyze`
- Request: `{ "studentId": "string" }`
- Response: `ProfileAnalysisResult`（字段同上 + `reasoning`）

- [ ] **Step 1: 修改 Profile.vue `<script setup>` 部分**

替换 `<script setup>` 整个块（第 106-127 行）为：

```js
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Edit, UserFilled } from '@element-plus/icons-vue'
import request from '@/api/request'

const userInfo = ref({})
const profileLoading = ref(false)

const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.studentId || ''
  } catch { return '' }
}

onMounted(async () => {
  // Load basic user info from localStorage (set during login)
  const info = localStorage.getItem('userInfo')
  if (info) {
    try { userInfo.value = JSON.parse(info) } catch { userInfo.value = {} }
  }
  // Fetch learner profile from backend
  await fetchProfile()
})

const fetchProfile = async () => {
  const studentId = getStudentId()
  if (!studentId) return
  profileLoading.value = true
  try {
    const res = await request.get(`/profile/${studentId}`)
    const p = res.data
    // Map backend fields to frontend dimension display
    dimensions.value[0].value = p.knowledgeBaseLevel || ''
    dimensions.value[0].confidence = parseConfidence(p.knowledgeBaseConfidence)
    dimensions.value[1].value = p.cognitiveStyleType || ''
    dimensions.value[1].confidence = parseConfidence(p.cognitiveStyleConfidence)
    dimensions.value[2].value = (p.errorPatternTags || []).join('、') || ''
    dimensions.value[2].confidence = parseConfidence(p.errorPatternConfidence)
    dimensions.value[3].value = p.learningPaceType || ''
    dimensions.value[3].confidence = parseConfidence(p.learningPaceConfidence)
    dimensions.value[4].value = p.contentPreferenceType || ''
    dimensions.value[4].confidence = parseConfidence(p.contentPreferenceRatio
      ? 50 : p.contentPreferenceType ? 50 : 0)
    dimensions.value[5].value = p.goalOrientationType || ''
    dimensions.value[5].confidence = parseConfidence(p.goalOrientationConfidence)
  } catch (e) {
    // 404 = no profile yet, not an error
    if (e.response?.status !== 404) {
      console.warn('Failed to fetch profile:', e.message)
    }
  } finally {
    profileLoading.value = false
  }
}

/** Convert BigDecimal (0.00-1.00) to percentage (0-100) */
const parseConfidence = (val) => {
  if (val == null) return 0
  const n = Number(val)
  if (n <= 1) return Math.round(n * 100)
  return Math.round(n)
}

const dimensions = ref([
  { key: 'knowledge',   icon: '📖', label: '知识基础',   value: '', color: '#667eea', confidence: 0 },
  { key: 'cognitive',   icon: '🧩', label: '认知风格',   value: '', color: '#3b82f6', confidence: 0 },
  { key: 'error',       icon: '⚠️', label: '易错偏好',   value: '', color: '#f97316', confidence: 0 },
  { key: 'pace',        icon: '🏃', label: '学习步调',   value: '', color: '#22c55e', confidence: 0 },
  { key: 'preference',  icon: '🎯', label: '内容偏好',   value: '', color: '#ec4899', confidence: 0 },
  { key: 'goal',        icon: '🏆', label: '目标导向',   value: '', color: '#a855f7', confidence: 0 }
])
```

- [ ] **Step 2: 验证前端构建**

```bash
cd frontend && npm run build 2>&1 | tail -5
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/Profile.vue
git commit -m "feat(frontend): connect Profile to GET /api/profile/{studentId}"
```

---

### Task 6: 最终验证

- [ ] **Step 1: 后端编译**

```bash
export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2" && export PATH="$JAVA_HOME/bin:$PATH"
mvn compile -DskipTests -q
```

Expected: 无 `[ERROR]`

- [ ] **Step 2: 前端构建**

```bash
cd frontend && npm run build
```

Expected: build 成功

- [ ] **Step 3: Commit 最终状态**

```bash
git add -A && git status
git commit -m "feat(frontend): complete API integration for ThinkTank, Tutoring, Learning, Profile"
```

---

## 未覆盖 / 后续

- **ResourcePush.vue** — 后端无 `/api/push` 端点，暂保持硬编码演示数据。需后续实现基于知识图谱的推送 API。
- **CORS** — `AuthController` 已有 `@CrossOrigin(origins = "*")`，其他 controller 如果缺少可能需要补。Vite dev proxy 绕过 CORS 问题，生产需检查。
- **AI 助教 Chroma 依赖** — `AiTutorServiceImpl` 依赖 `ChromaClient`（对话历史向量存储）。如 Chroma 未部署，首次调用可能报错但不影响前端接口格式。
