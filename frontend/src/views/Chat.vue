<template>
  <div class="chat-layout">
    <ChatSidebar
      :conversations="conversations"
      :active-id="currentConversationId || ''"
      @new-conversation="newConversation"
      @select="selectConversation"
      @delete="deleteConversation"
    />

    <div class="chat-main">
      <!-- Ambient background glow -->
      <div class="chat-bg" aria-hidden="true">
        <span class="bg-orb orb-a"></span>
        <span class="bg-orb orb-b"></span>
      </div>

      <!-- Message list -->
      <div class="chat-messages" ref="msgList">
        <div v-if="messages.length === 0" class="chat-empty">
          <div class="empty-mark"><span class="empty-glyph">✦</span></div>
          <h2 class="empty-title">智学派 AI 助手</h2>
          <p class="empty-sub">选择一个模式，输入你的问题或知识点，开启你的学习之旅</p>
          <div class="empty-suggestions">
            <button class="suggest-chip" @click="activeMode='chat'; inputText='什么是梯度下降？它有哪些实现方法？'">
              <span class="chip-emoji">💬</span> 什么是梯度下降？
            </button>
            <button class="suggest-chip" @click="activeMode='doc'; inputText='支持向量机（SVM）原理'">
              <span class="chip-emoji">📄</span> 生成 SVM 讲解文档
            </button>
            <button class="suggest-chip" @click="activeMode='mindmap'; inputText='机器学习知识体系'">
              <span class="chip-emoji">🧩</span> 机器学习知识导图
            </button>
            <button class="suggest-chip" @click="activeMode='quiz'; inputText='线性回归'">
              <span class="chip-emoji">📝</span> 出一套线性回归练习题
            </button>
          </div>
        </div>

        <div v-for="msg in messages" :key="msg.id" :class="['msg-row', msg.role]">
          <div v-if="msg.role === 'assistant'" class="msg-avatar ai" aria-hidden="true">✦</div>
          <!-- Agent progress bubble -->
          <div v-if="msg.type === 'agent-progress'" class="msg-bubble ai agent-progress">
            <div class="agent-flow-inline">
              <span v-for="(a, j) in msg.agents" :key="j" :class="['agent-tag', a.status]">
                {{ a.avatar }} {{ a.name }}
              </span>
            </div>
          </div>

          <!-- AI text answer -->
          <div v-else-if="msg.type === 'text'" class="msg-bubble ai">
            <div class="msg-content markdown-body" v-html="msg.content"></div>
            <div class="msg-source-tags" v-if="msg.sources && msg.sources.length">
              <el-tag size="small" type="success">① 私人智库 {{ msg.ragCount }}条</el-tag>
              <el-tag v-if="msg.hasKg" size="small" type="info">② 知识图谱</el-tag>
              <el-tag v-if="msg.hasWeb" size="small" type="warning">③ 互联网</el-tag>
            </div>
            <div class="msg-actions">
              <el-button text size="small" :icon="DocumentCopy" @click="copyText(msg.content)">复制</el-button>
              <el-button text size="small" :icon="RefreshRight" @click="regenerate(msg)">重新生成</el-button>
            </div>
          </div>

          <!-- Resource generation result -->
          <div v-else-if="msg.type === 'resource'" class="msg-bubble ai resource-bubble">
            <div class="resource-header">
              <span class="resource-icon">{{ msg.resourceIcon }}</span>
              <div class="resource-title-wrap">
                <strong>{{ msg.title }}</strong>
                <span class="resource-meta">{{ msg.resourceLabel }}</span>
              </div>
            </div>
            <p class="resource-summary">{{ msg.summary }}</p>
            <div class="resource-actions">
              <el-button v-if="msg.showPreview" size="small" type="primary" plain @click="msg.expanded = !msg.expanded">
                {{ msg.expanded ? '收起' : '预览' }}
              </el-button>
              <el-button v-if="msg.downloadable" size="small" :icon="Download" @click="downloadResource(msg)">下载</el-button>
            </div>

            <!-- PPT / VIDEO: file-based, download only -->
            <div v-if="msg.renderType === 'PPT' || msg.renderType === 'VIDEO'" class="file-render">
              <el-result icon="success" :title="`${msg.resourceLabel}已生成`" sub-title="点击上方“下载”按钮保存到本地" />
            </div>

            <!-- Previewable resource types -->
            <template v-else-if="msg.expanded">
              <!-- DOC: rendered markdown + KaTeX -->
              <div v-if="msg.renderType === 'DOC'" class="doc-render markdown-body" v-html="msg.renderedHtml"></div>

              <!-- MINDMAP: rendered Mermaid SVG -->
              <div v-else-if="msg.renderType === 'MINDMAP'" class="mindmap-render">
                <div v-if="msg.mindmapSvg" class="mindmap-svg" v-html="msg.mindmapSvg"></div>
                <pre v-else class="raw-fallback">{{ msg.rawContent }}</pre>
                <p class="code-hint">💡 思维导图由 Mermaid 实时渲染。点击下载可保存为 .mmd 离线使用。</p>
              </div>

              <!-- CODE: Jupyter-like interactive editor -->
              <div v-else-if="msg.renderType === 'CODE'" class="code-render">
                <div class="code-toolbar">
                  <span class="code-lang">Python</span>
                  <div class="code-toolbar-actions">
                    <el-button size="small" type="primary" :icon="VideoPlay" @click="runCode(msg)" :loading="msg.codeRunning">
                      {{ msg.codeRunning ? '运行中…' : '运行' }}
                    </el-button>
                    <el-button size="small" :icon="DocumentCopy" @click="copyCode(msg)">复制</el-button>
                  </div>
                </div>
                <el-input
                  v-model="msg.editableCode"
                  type="textarea"
                  :rows="14"
                  class="code-editor"
                  placeholder="在此编辑 Python 代码…"
                  :disabled="msg.codeRunning"
                />
                <div v-if="msg.codeOutput || msg.codeRunning" class="code-output">
                  <div class="output-header">
                    <span class="output-label">▶ 输出</span>
                    <span class="output-meta" v-if="!msg.codeRunning">{{ msg.codeExecTime }}ms · exit={{ msg.codeExitCode }}</span>
                  </div>
                  <pre class="output-body" :class="{ 'output-error': msg.codeExitCode !== 0 }">{{ msg.codeOutput || '（等待输出…）' }}</pre>
                </div>
              </div>

              <!-- HTML: live interactive iframe -->
              <div v-else-if="msg.renderType === 'HTML'" class="html-render">
                <iframe
                  :srcdoc="msg.rawContent"
                  sandbox="allow-scripts allow-same-origin"
                  class="html-frame"
                  title="交互课件"
                ></iframe>
                <p class="code-hint">💡 这是真实可交互的网页课件，在沙箱中运行。点击下载可保存为 .html 离线打开。</p>
              </div>

              <!-- QUIZ: interactive question cards -->
              <div v-else-if="msg.renderType === 'QUIZ'" class="quiz-render">
                <div v-if="msg.quizData">
                  <div class="quiz-toolbar">
                    <el-button size="small" @click="msg.showAnswers = !msg.showAnswers">
                      {{ msg.showAnswers ? '隐藏解析' : '显示全部解析' }}
                    </el-button>
                  </div>
                  <div v-for="(q, qi) in msg.quizData.questions" :key="qi" class="quiz-item">
                    <div class="quiz-q">
                      <span class="quiz-num">{{ qi + 1 }}</span>
                      <span class="quiz-type-tag">{{ quizTypeLabel(q.type) }}</span>
                      <span class="quiz-text">{{ q.question }}</span>
                    </div>
                    <!-- Interactive MC / TF options -->
                    <ul v-if="q.options && q.options.length && (q.type === 'MC' || q.type === 'TF')" class="quiz-options">
                      <li
                        v-for="(opt, oj) in q.options" :key="oj"
                        :class="[
                          'quiz-option-item',
                          {
                            'option-selected': msg.selectedOption[qi] === optionLetter(opt),
                            'option-correct': msg.selectedOption[qi] === optionLetter(opt) && msg.optionResult[qi] === 'correct',
                            'option-incorrect': msg.selectedOption[qi] === optionLetter(opt) && msg.optionResult[qi] === 'incorrect',
                            'option-reveal-correct': msg.optionResult[qi] === 'incorrect' && isCorrectAnswer(q, opt)
                          }
                        ]"
                        @click="selectQuizOption(msg, qi, opt)"
                      >
                        <span class="option-marker">
                          <span v-if="msg.selectedOption[qi] === optionLetter(opt) && msg.optionResult[qi] === 'correct'">✅</span>
                          <span v-else-if="msg.selectedOption[qi] === optionLetter(opt) && msg.optionResult[qi] === 'incorrect'">❌</span>
                          <span v-else-if="msg.optionResult[qi] === 'incorrect' && isCorrectAnswer(q, opt)">✅</span>
                          <span v-else>{{ optionLetter(opt) }}</span>
                        </span>
                        <span class="option-text">{{ opt.replace(/^[A-Z][.)]\s*/, '') }}</span>
                      </li>
                    </ul>
                    <!-- Non-interactive SHORT_ANSWER / FILL_BLANK -->
                    <div v-else-if="q.type === 'SHORT_ANSWER' || q.type === 'FILL_BLANK'" class="quiz-written">
                      <div v-if="q.type === 'SHORT_ANSWER'" class="written-area">
                        <el-input type="textarea" :rows="3" placeholder="请输入你的答案..." disabled />
                      </div>
                      <div v-else class="written-area">
                        <el-input placeholder="请填空..." disabled />
                      </div>
                      <div v-if="msg.showAnswers" class="quiz-answer">
                        <div class="quiz-answer-row"><strong>参考答案：</strong>{{ q.answer }}</div>
                        <div v-if="q.explanation" class="quiz-explain"><strong>解析：</strong>{{ q.explanation }}</div>
                      </div>
                    </div>
                    <div v-if="msg.showAnswers || msg.optionResult[qi]" class="quiz-answer">
                      <div class="quiz-answer-row">
                        <strong>答案：</strong>{{ q.answer }}
                        <span v-if="msg.optionResult[qi] === 'correct'" style="color:#67c23a; margin-left:8px;">✓ 正确!</span>
                        <span v-else-if="msg.optionResult[qi] === 'incorrect'" style="color:#f56c6c; margin-left:8px;">✗ 不对</span>
                      </div>
                      <div v-if="q.explanation" class="quiz-explain"><strong>解析：</strong>{{ q.explanation }}</div>
                    </div>
                  </div>
                </div>
                <pre v-else class="raw-fallback">{{ msg.rawContent }}</pre>
              </div>

              <!-- Fallback -->
              <pre v-else class="raw-fallback">{{ msg.rawContent }}</pre>
            </template>
          </div>

          <!-- User message -->
          <div v-else-if="msg.role === 'user'" class="msg-bubble user">
            {{ msg.content }}
          </div>
          <div v-if="msg.role === 'user'" class="msg-avatar user" aria-hidden="true">👤</div>
        </div>

        <!-- Loading indicator -->
        <div v-if="loading" class="msg-row assistant">
          <div class="msg-avatar ai" aria-hidden="true">✦</div>
          <div class="msg-bubble ai loading-bubble">
            <el-icon class="is-loading"><Loading /></el-icon> {{ loadingText }}
          </div>
        </div>
      </div>

      <!-- Mode bar -->
      <div class="mode-bar">
        <div
          v-for="m in modes" :key="m.key"
          :class="['mode-item', { active: activeMode === m.key }]"
          @click="switchMode(m.key)"
        >
          <span class="mode-icon">{{ m.icon }}</span>
          <span class="mode-label">{{ m.label }}</span>
        </div>
      </div>

      <!-- Input area -->
      <div class="input-area">
        <el-input
          v-model="inputText"
          :placeholder="currentMode.placeholder"
          type="textarea"
          :rows="2"
          @keyup.enter.ctrl="sendMessage"
          :disabled="loading"
        />
        <div class="input-footer">
          <el-tag size="small" type="info">Ctrl + Enter 发送</el-tag>
          <el-button type="primary" :icon="Promotion" :loading="loading" @click="sendMessage" :disabled="!inputText.trim()">
            发送
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, Loading, DocumentCopy, RefreshRight, Download, VideoPlay } from '@element-plus/icons-vue'
import request from '@/api/request'
import { marked } from 'marked'
import katex from 'katex'
import 'katex/dist/katex.min.css'
import mermaid from 'mermaid'
import ChatSidebar from '@/components/ChatSidebar.vue'

// ---- Init libraries (ported from Learning.vue) ----
mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })

// Register marked extension for LaTeX math
marked.use({
  extensions: [{
    name: 'math',
    level: 'inline',
    start(src) { return src.indexOf('$') },
    tokenizer(src) {
      const displayMatch = /^\$\$([\s\S]*?)\$\$/.exec(src)
      if (displayMatch) {
        return { type: 'math', raw: displayMatch[0], text: displayMatch[1].trim(), display: true }
      }
      const inlineMatch = /^\$([^\n$]+)\$/.exec(src)
      if (inlineMatch) {
        return { type: 'math', raw: inlineMatch[0], text: inlineMatch[1].trim(), display: false }
      }
    },
    renderer(token) {
      try {
        return katex.renderToString(token.text, {
          displayMode: token.display,
          throwOnError: false,
          trust: true
        })
      } catch { return token.raw }
    }
  }]
})

// ---- Mode config ----
const modes = [
  { key: 'chat', icon: '💬', label: '问答', placeholder: '输入你的问题，AI 将从私人智库中检索答案...' },
  { key: 'doc', icon: '📄', label: '文档', placeholder: '输入知识点，如：SVM 支持向量机原理' },
  { key: 'ppt', icon: '📊', label: 'PPT', placeholder: '输入知识点，如：决策树算法详解' },
  { key: 'quiz', icon: '📝', label: '题库', placeholder: '输入知识点，如：线性回归推导' },
  { key: 'mindmap', icon: '🧩', label: '导图', placeholder: '输入知识点，如：机器学习知识体系' },
  { key: 'code', icon: '💻', label: '代码', placeholder: '输入知识点，如：使用 Python 实现 K-means' },
  { key: 'html', icon: '🌐', label: '课件', placeholder: '输入知识点，如：概率论基础' }
]
const activeMode = ref('chat')
const currentMode = computed(() => modes.find(m => m.key === activeMode.value) || modes[0])
const switchMode = (key) => { activeMode.value = key }

// ---- State ----
const inputText = ref('')
const loading = ref(false)
const loadingText = ref('思考中...')
const messages = ref([])
const currentConversationId = ref(null)
const conversations = ref([])
const msgList = ref(null)

// Stable, monotonically increasing message ids — array index keys are fragile
// for stateful bubbles (CODE editor / QUIZ inputs) since the messages array is
// spliced and reassigned during navigation.
let msgIdCounter = 0
const nextMsgId = () => `m-${Date.now()}-${++msgIdCounter}`

// Resource generation metadata
const agents = [
  { name: 'RequireAgent', avatar: '🔍' },
  { name: 'DesignAgent',  avatar: '🎨' },
  { name: 'Generator',    avatar: '⚙️' },
  { name: 'ReviewAgent',  avatar: '🛡️' }
]
const stageToIdx = { INIT: -1, REQUIRE: 0, DESIGN: 1, GENERATING: 2, REVIEWING: 3, DONE: 4, FALLBACK: 4 }
const TYPE_LABELS = {
  DOC: '课程文档', PPT: '教学PPT', QUIZ: '练习题库',
  MINDMAP: '思维导图', CODE: '代码案例', HTML: '交互课件', VIDEO: '教学视频'
}
// NOTE: the plan referenced TYPE_ICONS without defining it — defined here.
const TYPE_ICONS = {
  DOC: '📄', PPT: '📊', QUIZ: '📝', MINDMAP: '🧩', CODE: '💻', HTML: '🌐', VIDEO: '🎬'
}

const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.studentId || 'anonymous'
  } catch { return 'anonymous' }
}

// ---- Conversations (Task-1 backend API) ----
const loadConversations = async () => {
  try {
    const res = await request.get('/tutor/conversations', { params: { studentId: getStudentId() } })
    conversations.value = res.data || []
  } catch { conversations.value = [] }
}

const newConversation = () => {
  currentConversationId.value = null
  messages.value = []
}

const selectConversation = async (conv) => {
  currentConversationId.value = conv.conversationId
  try {
    const res = await request.get(`/tutor/conversations/${conv.conversationId}/messages`, {
      params: { studentId: getStudentId() }
    })
    messages.value = (res.data || []).map(m => {
      const meta = m.metadata || {}
      const role = meta.role === 'user' ? 'user' : 'assistant'
      if (role === 'user') {
        return { id: nextMsgId(), role: 'user', type: 'user', content: m.document || '' }
      }
      return {
        id: nextMsgId(),
        role: 'assistant',
        type: 'text',
        content: safeMarkdown(m.document || ''),
        sources: [],
        ragCount: 0
      }
    })
    await scrollToBottom()
  } catch { messages.value = [] }
}

// deleteConversation: the Task-1 backend has NO delete endpoint yet, so this is
// front-end only — drop it from the local list and reset if it was active.
// TODO(backend): add DELETE /api/tutor/conversations/{id} and call it here.
const deleteConversation = (conv) => {
  conversations.value = conversations.value.filter(c => c.conversationId !== conv.conversationId)
  if (currentConversationId.value === conv.conversationId) {
    newConversation()
  }
}

// ---- Send message ----
const sendMessage = async () => {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  inputText.value = ''
  messages.value.push({ id: nextMsgId(), role: 'user', type: 'user', content: text })
  await scrollToBottom()

  if (activeMode.value === 'chat') {
    await sendChatMessage(text)
  } else {
    await sendResourceGenerate(text)
  }

  await scrollToBottom()
  loadConversations()
}

// ---- Chat (Q&A) — ported from Tutoring.vue askQuestion() ----
const sendChatMessage = async (text) => {
  loading.value = true
  loadingText.value = '检索中...'

  try {
    const res = await request.post('/tutor/chat', {
      studentId: getStudentId(),
      question: text,
      conversationId: currentConversationId.value
    })
    const data = res.data
    currentConversationId.value = data.conversationId

    const sources = data.sources || []
    messages.value.push({
      id: nextMsgId(),
      role: 'assistant',
      type: 'text',
      content: safeMarkdown(data.answer || ''),
      sources,
      ragCount: sources.length,
      // ChatResponse has no hasKg/hasWeb fields — decorative defaults per plan
      hasKg: data.hasKg !== undefined ? data.hasKg : true,
      hasWeb: data.hasWeb !== undefined ? data.hasWeb : sources.length < 2,
      question: text
    })
  } catch (e) {
    ElMessage.error('提问失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  } finally {
    loading.value = false
  }
}

// Remove the agent-progress placeholder by stable id (returns true if removed).
const removeProgressBubble = (progressId) => {
  const i = messages.value.findIndex(m => m.id === progressId)
  if (i !== -1 && messages.value[i].type === 'agent-progress') {
    messages.value.splice(i, 1)
    return true
  }
  return false
}

// ---- Resource generation (SSE) — ported from Learning.vue startGenerate() ----
const sendResourceGenerate = async (text) => {
  loading.value = true
  loadingText.value = '🔍 需求分析...'

  // Insert agent-progress placeholder message (tracked by stable id, not index)
  const progressId = nextMsgId()
  messages.value.push({
    id: progressId,
    role: 'assistant',
    type: 'agent-progress',
    agents: agents.map(a => ({ ...a, status: 'pending' }))
  })
  await scrollToBottom()

  const token = localStorage.getItem('token') || ''
  const body = JSON.stringify({
    studentId: getStudentId(),
    knowledgePoint: text,
    types: [activeMode.value.toUpperCase()]
  })

  let reader = null
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

    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      const lines = buffer.split('\n')
      buffer = lines.pop()
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const json = line.substring(5).trim()
        if (!json) continue
        // Scope the try to parsing ONLY — a throw inside handleSseEvent must not
        // be silently swallowed as if the line were unparseable.
        let parsed
        try { parsed = JSON.parse(json) } catch { continue }
        handleSseEvent(parsed, progressId, text)
      }
    }

    // Stream ended. A terminal DONE/FALLBACK event already removed the progress
    // bubble; if none arrived (stream closed early), clean up the orphaned
    // placeholder so the user isn't stuck on a spinner.
    if (removeProgressBubble(progressId)) {
      ElMessage.warning('生成中断，请重试')
    }
  } catch (e) {
    ElMessage.error('生成失败：' + (e.message || '请稍后重试'))
    removeProgressBubble(progressId)
  } finally {
    reader?.cancel().catch(() => {})
    loading.value = false
  }
}

const handleSseEvent = (evt, progressId, text) => {
  const stage = evt.stage || ''
  const idx = stageToIdx[stage] ?? -1

  // Update agent progress on the placeholder bubble (found by stable id)
  const progressMsg = messages.value.find(m => m.id === progressId)
  if (idx >= 0 && idx < agents.length && progressMsg && progressMsg.type === 'agent-progress') {
    progressMsg.agents = agents.map((a, i) => ({
      ...a,
      status: i < idx ? 'done' : i === idx ? 'loading' : 'pending'
    }))
    loadingText.value = idx < 1 ? '🔍 需求分析...'
      : idx === 1 ? '🎨 内容设计...'
      : idx === 2 ? '✍️ 内容生成...'
      : '🛡️ 质量审核...'
  }

  if (stage === 'DONE' || stage === 'FALLBACK') {
    // Remove the progress placeholder, then append the result bubble
    removeProgressBubble(progressId)

    const typeKey = activeMode.value.toUpperCase()
    const payload = evt.payload || {}
    const item = payload[typeKey] || Object.values(payload)[0]

    let type, content, title
    if (item) {
      type = item.type || typeKey
      content = item.content || ''
      title = item.title || `${text} - 学习资源`
    } else {
      type = typeKey
      content = evt.message || '生成完成'
      title = `${text} - 学习资源`
    }

    const resMsg = {
      id: nextMsgId(),
      role: 'assistant',
      type: 'resource',
      renderType: type,
      resourceIcon: TYPE_ICONS[type] || '📄',
      resourceLabel: TYPE_LABELS[type] || type,
      title,
      summary: `已为你生成「${text}」${TYPE_LABELS[type] || type}`,
      rawContent: content,
      downloadable: true,
      downloadPath: (item && item.downloadPath) || null,
      showPreview: type !== 'PPT' && type !== 'VIDEO',
      expanded: type !== 'PPT' && type !== 'VIDEO',
      // DOC
      renderedHtml: type === 'DOC' ? safeMarkdown(content) : '',
      // MINDMAP
      mindmapSvg: '',
      // CODE
      editableCode: type === 'CODE' ? stripCodeFences(content) : '',
      codeRunning: false,
      codeOutput: '',
      codeExecTime: 0,
      codeExitCode: 0,
      // QUIZ
      quizData: type === 'QUIZ' ? parseQuizData(content) : null,
      selectedOption: {},
      optionResult: {},
      showAnswers: false
    }
    messages.value.push(resMsg)
    // Capture the reactive PROXY element (returned by indexing the reactive
    // array), NOT the raw resMsg literal: mutating the proxy triggers re-render
    // and is stale-safe — if the array is later reassigned (navigation), this
    // orphaned proxy simply isn't rendered.
    const target = messages.value[messages.value.length - 1]

    // Async render mermaid mindmap
    if (type === 'MINDMAP') {
      nextTick(() => {
        renderMindmap(content).then(svg => { target.mindmapSvg = svg })
      })
    }

    if (stage === 'DONE') ElMessage.success('资源生成完成')
    if (stage === 'FALLBACK') ElMessage.warning(evt.message || '生成降级完成')

    scrollToBottom()
  }
}

// ---- Helpers (ported from Learning.vue) ----
const safeMarkdown = (text) => {
  if (!text) return ''
  try { return marked.parse(text) } catch { return text }
}

const copyText = (html) => {
  const div = document.createElement('div')
  div.innerHTML = html
  const text = div.textContent || ''
  navigator.clipboard.writeText(text)
    .then(() => ElMessage.success('已复制'))
    .catch(() => ElMessage.warning('复制失败'))
}

const copyCode = async (msg) => {
  try {
    await navigator.clipboard.writeText(msg.editableCode || msg.rawContent || '')
    ElMessage.success('代码已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

const optionLetter = (opt) => {
  const m = (opt || '').match(/^([A-Z])[.)]\s?/)
  return m ? m[1] : ''
}

const quizTypeLabel = (t) => ({
  MC: '单选', TF: '判断', SHORT_ANSWER: '简答', FILL_BLANK: '填空'
}[t] || t)

const isCorrectAnswer = (q, optText) => {
  if (!q) return false
  if (q.type === 'TF') {
    const optContent = optText.replace(/^[A-Z][.)]\s*/, '').trim()
    const ans = q.answer?.trim() || ''
    const optTrue = /^(true|t|yes|正确|是|√|对)$/i.test(optContent)
    const ansTrue = /^(true|t|yes|正确|是|√|对)$/i.test(ans)
    return optTrue === ansTrue
  }
  const letter = optionLetter(optText)
  return letter.toUpperCase() === (q.answer?.trim()?.toUpperCase() || '')
}

const selectQuizOption = (msg, qIndex, optText) => {
  const q = msg.quizData?.questions?.[qIndex]
  if (!q || q.type === 'SHORT_ANSWER' || q.type === 'FILL_BLANK') return
  const letter = optionLetter(optText)
  if (!letter) return
  msg.selectedOption = { ...msg.selectedOption, [qIndex]: letter }

  let isCorrect = false
  if (q.type === 'TF') {
    const optContent = optText.replace(/^[A-Z][.)]\s*/, '').trim()
    const ans = q.answer?.trim() || ''
    const optTrue = /^(true|t|yes|正确|是|√|对)$/i.test(optContent)
    const ansTrue = /^(true|t|yes|正确|是|√|对)$/i.test(ans)
    isCorrect = optTrue === ansTrue
  } else {
    const correctLetter = q.answer?.trim().toUpperCase() || ''
    isCorrect = letter.toUpperCase() === correctLetter
  }
  msg.optionResult = { ...msg.optionResult, [qIndex]: isCorrect ? 'correct' : 'incorrect' }
}

const parseQuizData = (content) => {
  try {
    const parsed = JSON.parse(content)
    if (parsed.questions) {
      parsed.questions.forEach(q => {
        if (q.type === 'TF' && (!q.options || q.options.length === 0)) {
          q.options = ['A. True', 'B. False']
        }
      })
    }
    return parsed
  } catch { return null }
}

const stripCodeFences = (c) => {
  let s = (c || '').trim()
  if (s.startsWith('```')) {
    s = s.replace(/^```\w*\n?/, '').replace(/\n?```$/, '')
  }
  return s.trim()
}

const renderMindmap = async (content) => {
  if (!content) return ''
  try {
    let code = content.trim()
    if (code.startsWith('```')) {
      code = code.replace(/^```\w*\n?/, '').replace(/\n?```$/, '')
    }
    const id = 'mindmap-' + Math.random().toString(36).slice(2, 8)
    const { svg } = await mermaid.render(id, code)
    return svg
  } catch (e) {
    console.warn('Mermaid render failed:', e.message)
    return ''
  }
}

// runCode → POST /api/generate/run-code (real backend endpoint; matches Learning.vue)
const runCode = async (msg) => {
  if (!msg.editableCode || !msg.editableCode.trim()) {
    ElMessage.warning('代码为空，无法运行')
    return
  }
  msg.codeRunning = true
  msg.codeOutput = ''
  try {
    const res = await request.post('/generate/run-code', { code: msg.editableCode })
    const data = res.data
    msg.codeOutput = [data.stdout, data.stderr ? `\n--- stderr ---\n${data.stderr}` : '']
      .filter(Boolean).join('\n').trim() || '（无输出）'
    msg.codeExecTime = data.executionTimeMs ?? 0
    msg.codeExitCode = data.exitCode ?? 0
    if (data.timedOut) {
      msg.codeOutput += '\n⚠ 执行超时（30秒）'
    }
  } catch (e) {
    msg.codeOutput = '请求失败：' + (e.response?.data?.error || e.message || '请稍后重试')
    msg.codeExitCode = -1
  } finally {
    msg.codeRunning = false
  }
}

// downloadResource — ported from Learning.vue downloadResult()
const downloadResource = (msg) => {
  const type = msg.renderType

  // File-based types → backend download endpoint
  if (type === 'PPT' || type === 'VIDEO') {
    if (msg.downloadPath) {
      window.open(`/api/generate/download/${msg.downloadPath}`, '_blank')
    } else {
      ElMessage.warning('文件生成失败，无法下载')
    }
    return
  }

  // Text types → client-side Blob download
  const extMap = { DOC: 'md', QUIZ: 'json', CODE: 'py', HTML: 'html', MINDMAP: 'mmd' }
  const mimeMap = {
    DOC: 'text/markdown', QUIZ: 'application/json', CODE: 'text/x-python',
    HTML: 'text/html', MINDMAP: 'text/plain'
  }
  const ext = extMap[type] || 'txt'
  const mime = mimeMap[type] || 'text/plain'
  const safeName = (msg.title || 'resource').replace(/[\\/:*?"<>|]/g, '_')
  const content = type === 'CODE' ? (msg.editableCode || msg.rawContent) : msg.rawContent

  const blob = new Blob([content || ''], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${safeName}.${ext}`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success('已下载')
}

const regenerate = (msg) => {
  if (loading.value) return
  if (msg.question) {
    activeMode.value = 'chat'
    inputText.value = msg.question
    sendMessage()
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (msgList.value) msgList.value.scrollTop = msgList.value.scrollHeight
}

onMounted(loadConversations)
</script>

<style scoped>
/* ═══════════════════════════════════════════════
   智学派 AI 辅导 — 精致化视觉（保持紫色主题）
   ═══════════════════════════════════════════════ */
.chat-layout {
  --brand-1: #667eea;
  --brand-2: #764ba2;
  --ink: #1a1a2e;
  --ink-soft: #5b6270;
  --line: #ecedf5;
  display: flex;
  height: calc(100vh - 48px);
  background:
    radial-gradient(1200px 500px at 80% -10%, rgba(118,75,162,0.06), transparent 60%),
    radial-gradient(900px 500px at 0% 110%, rgba(102,126,234,0.07), transparent 55%),
    linear-gradient(180deg, #f7f8fc 0%, #f2f3fb 100%);
}
.chat-main {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* ---- Ambient background orbs ---- */
.chat-bg { position: absolute; inset: 0; overflow: hidden; pointer-events: none; z-index: 0; }
.bg-orb { position: absolute; border-radius: 50%; filter: blur(90px); opacity: 0.5; }
.orb-a {
  width: 420px; height: 420px; top: -140px; right: -80px;
  background: radial-gradient(circle, rgba(102,126,234,0.35), transparent 70%);
  animation: drift 18s ease-in-out infinite;
}
.orb-b {
  width: 360px; height: 360px; bottom: -120px; left: -60px;
  background: radial-gradient(circle, rgba(118,75,162,0.28), transparent 70%);
  animation: drift 22s ease-in-out infinite reverse;
}
@keyframes drift {
  0%, 100% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(28px, -24px) scale(1.08); }
}

/* ---- Message list ---- */
.chat-messages {
  position: relative;
  z-index: 1;
  flex: 1;
  overflow-y: auto;
  padding: 28px clamp(16px, 6vw, 80px);
  scroll-behavior: smooth;
}
.chat-messages::-webkit-scrollbar { width: 8px; }
.chat-messages::-webkit-scrollbar-thumb { background: rgba(102,126,234,0.18); border-radius: 4px; }
.chat-messages::-webkit-scrollbar-thumb:hover { background: rgba(102,126,234,0.32); }

/* ---- Empty state ---- */
.chat-empty {
  height: 100%;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  text-align: center;
}
.empty-mark { margin-bottom: 22px; animation: fadeUp 0.6s ease both; }
.empty-glyph {
  display: flex; align-items: center; justify-content: center;
  width: 78px; height: 78px; border-radius: 24px;
  background: linear-gradient(135deg, var(--brand-1), var(--brand-2));
  color: #fff; font-size: 38px;
  box-shadow: 0 16px 44px rgba(102,126,234,0.4);
  animation: pulseGlow 3s ease-in-out infinite;
}
@keyframes pulseGlow {
  0%, 100% { box-shadow: 0 16px 44px rgba(102,126,234,0.4); transform: translateY(0); }
  50% { box-shadow: 0 22px 60px rgba(118,75,162,0.5); transform: translateY(-4px); }
}
.empty-title {
  font-size: 26px; font-weight: 800; letter-spacing: 1px; margin: 0 0 8px;
  background: linear-gradient(135deg, var(--brand-1), var(--brand-2));
  -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent;
  animation: fadeUp 0.6s ease 0.05s both;
}
.empty-sub { font-size: 14px; color: var(--ink-soft); margin: 0 0 28px; animation: fadeUp 0.6s ease 0.1s both; }
.empty-suggestions {
  display: flex; flex-wrap: wrap; gap: 12px; justify-content: center; max-width: 560px;
  animation: fadeUp 0.6s ease 0.16s both;
}
.suggest-chip {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 11px 18px; border-radius: 14px;
  border: 1px solid rgba(102,126,234,0.18);
  background: rgba(255,255,255,0.7); backdrop-filter: blur(8px);
  color: #4a4f5e; font-size: 13.5px; cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s, border-color 0.2s, color 0.2s;
}
.suggest-chip:hover {
  transform: translateY(-3px);
  border-color: var(--brand-1); color: var(--brand-1);
  box-shadow: 0 10px 26px rgba(102,126,234,0.18);
}
.chip-emoji { font-size: 16px; }

/* ---- Message rows ---- */
.msg-row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 22px; animation: msgIn 0.35s ease both; }
.msg-row.user { justify-content: flex-end; }
.msg-row.assistant { justify-content: flex-start; }
@keyframes msgIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

/* Avatars */
.msg-avatar {
  flex-shrink: 0; width: 36px; height: 36px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  font-size: 17px; margin-top: 2px;
}
.msg-avatar.ai {
  background: linear-gradient(135deg, var(--brand-1), var(--brand-2));
  color: #fff; box-shadow: 0 6px 16px rgba(102,126,234,0.32);
}
.msg-avatar.user { background: #fff; border: 1px solid var(--line); box-shadow: 0 2px 8px rgba(0,0,0,0.04); }

/* Bubbles */
.msg-bubble {
  border-radius: 18px;
  padding: 15px 19px;
  font-size: 14px;
  line-height: 1.75;
  word-break: break-word;
}
.msg-bubble.user {
  max-width: 68%;
  background: linear-gradient(135deg, var(--brand-1), var(--brand-2));
  color: #fff;
  border-bottom-right-radius: 6px;
  white-space: pre-wrap;
  box-shadow: 0 8px 24px rgba(102,126,234,0.28);
}
.msg-bubble.ai {
  max-width: 82%;
  background: rgba(255,255,255,0.86);
  backdrop-filter: blur(10px);
  color: var(--ink);
  border: 1px solid rgba(255,255,255,0.9);
  border-bottom-left-radius: 6px;
  box-shadow: 0 8px 30px rgba(31,38,86,0.07);
}

.msg-content { font-size: 14px; line-height: 1.85; }
.msg-source-tags { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.msg-actions {
  display: flex; gap: 4px; margin-top: 10px; padding-top: 10px;
  border-top: 1px solid var(--line);
}

/* Agent progress bubble */
.agent-progress { padding: 13px 17px; }
.agent-flow-inline { display: flex; flex-wrap: wrap; gap: 8px; }
.agent-tag {
  font-size: 12px; padding: 5px 12px; border-radius: 13px;
  background: #f3f4fb; color: #9096a8; transition: all 0.3s;
}
.agent-tag.loading {
  background: linear-gradient(135deg, rgba(102,126,234,0.14), rgba(118,75,162,0.14));
  color: var(--brand-1); font-weight: 600;
  position: relative; overflow: hidden;
}
.agent-tag.loading::after {
  content: ''; position: absolute; inset: 0;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.6), transparent);
  transform: translateX(-100%); animation: shimmer 1.4s infinite;
}
@keyframes shimmer { to { transform: translateX(100%); } }
.agent-tag.done { background: #eef8e6; color: #67c23a; }

/* Loading bubble */
.loading-bubble { display: flex; align-items: center; gap: 8px; color: var(--brand-1); font-weight: 500; }

/* ---- Resource bubble ---- */
.resource-bubble { width: 82%; max-width: 82%; }
.resource-header {
  display: flex; align-items: center; gap: 13px;
  padding-bottom: 12px; border-bottom: 1px dashed var(--line);
}
.resource-icon {
  font-size: 26px; width: 46px; height: 46px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center; border-radius: 13px;
  background: linear-gradient(135deg, rgba(102,126,234,0.12), rgba(118,75,162,0.12));
}
.resource-title-wrap { flex: 1; min-width: 0; }
.resource-title-wrap strong { display: block; font-size: 15px; color: var(--ink); font-weight: 700; }
.resource-meta { font-size: 12px; color: #9096a8; }
.resource-summary { font-size: 13px; color: var(--ink-soft); margin: 12px 0; }
.resource-actions { display: flex; gap: 8px; margin-bottom: 8px; }
.code-hint { font-size: 12px; color: #9096a8; margin: 10px 0 0; }
.raw-fallback { white-space: pre-wrap; font-size: 13px; color: #4a4f5e; }

/* DOC markdown */
.doc-render { font-size: 14px; line-height: 1.85; color: #2c3142; }
.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3) { margin: 18px 0 9px; color: var(--ink); }
.markdown-body :deep(p) { margin: 8px 0; }
.markdown-body :deep(pre) { background: #f6f8fa; padding: 13px; border-radius: 10px; overflow: auto; }
.markdown-body :deep(code) { background: #f0f2f5; padding: 2px 6px; border-radius: 5px; font-size: 13px; }
.markdown-body :deep(pre code) { background: none; padding: 0; }
.markdown-body :deep(table) { border-collapse: collapse; margin: 12px 0; }
.markdown-body :deep(th), .markdown-body :deep(td) { border: 1px solid #dcdfe6; padding: 6px 12px; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 24px; }
.markdown-body :deep(.katex-display) { margin: 16px 0; overflow-x: auto; overflow-y: hidden; }
.markdown-body :deep(.katex) { font-size: 1.1em; }

/* QUIZ */
.quiz-toolbar { margin-bottom: 12px; text-align: right; }
.quiz-item { padding: 16px; border: 1px solid var(--line); border-radius: 14px; margin-bottom: 12px; background: rgba(255,255,255,0.6); }
.quiz-q { display: flex; align-items: flex-start; gap: 8px; font-size: 14px; font-weight: 600; color: var(--ink); }
.quiz-num { background: linear-gradient(135deg, var(--brand-1), var(--brand-2)); color: #fff; border-radius: 50%; width: 22px; height: 22px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; flex-shrink: 0; }
.quiz-type-tag { background: #eef0ff; color: var(--brand-1); font-size: 11px; padding: 2px 8px; border-radius: 8px; flex-shrink: 0; }
.quiz-text { flex: 1; }
.quiz-options { list-style: none; padding: 0; margin: 10px 0 0 30px; }
.quiz-option-item {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 10px 14px; margin: 6px 0;
  border: 1.5px solid #e6e8f0; border-radius: 12px;
  cursor: pointer; transition: all 0.2s;
  font-size: 14px; color: #4a4f5e; list-style: none;
}
.quiz-option-item:hover { border-color: var(--brand-1); background: #f8f7ff; transform: translateX(2px); }
.quiz-option-item.option-selected { border-color: var(--brand-1); background: #f0efff; }
.quiz-option-item.option-correct { border-color: #67c23a; background: #f0f9eb; }
.quiz-option-item.option-incorrect { border-color: #f56c6c; background: #fef0f0; }
.quiz-option-item.option-reveal-correct { border-color: #67c23a; background: #f0f9eb; }
.option-marker { font-weight: 700; font-size: 14px; min-width: 20px; color: var(--brand-1); text-align: center; flex-shrink: 0; }
.option-correct .option-marker { color: #67c23a; }
.option-incorrect .option-marker { color: #f56c6c; }
.option-text { flex: 1; }
.quiz-answer { margin: 12px 0 0 30px; padding: 12px; background: #f0f9eb; border-radius: 10px; font-size: 13px; }
.quiz-answer-row { color: #529b2e; margin-bottom: 6px; }
.quiz-explain { color: #606266; line-height: 1.6; }
.quiz-written { margin: 10px 0 0 30px; }
.written-area { margin-bottom: 10px; opacity: 0.7; }

/* CODE */
.code-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.code-lang { font-size: 12px; color: #9096a8; font-weight: 600; letter-spacing: 0.5px; }
.code-toolbar-actions { display: flex; gap: 8px; }
.code-editor :deep(textarea) {
  font-family: 'JetBrains Mono', 'Consolas', 'Monaco', monospace !important;
  font-size: 13px !important; line-height: 1.6 !important;
  background: #1e1e2e !important; color: #e0e0e0 !important;
  border-radius: 12px !important; border-color: #33334d !important;
}
.code-editor :deep(textarea):focus { border-color: var(--brand-1) !important; box-shadow: 0 0 0 3px rgba(102,126,234,0.2) !important; }
.code-output { margin-top: 12px; border: 1px solid #e6e8f0; border-radius: 12px; overflow: hidden; }
.output-header { display: flex; align-items: center; justify-content: space-between; padding: 9px 14px; background: #f5f7fa; border-bottom: 1px solid #e6e8f0; }
.output-label { font-size: 13px; font-weight: 600; color: #303133; }
.output-meta { font-size: 11px; color: #9096a8; font-family: monospace; }
.output-body {
  margin: 0; padding: 13px 14px; background: #fafbfc;
  font-family: 'Consolas', 'Monaco', monospace; font-size: 13px; line-height: 1.5;
  white-space: pre-wrap; word-break: break-word; color: #303133; max-height: 400px; overflow: auto;
}
.output-body.output-error { color: #f56c6c; background: #fef0f0; }

/* MINDMAP */
.mindmap-svg { overflow-x: auto; padding: 12px 0; }
.mindmap-svg :deep(svg) { max-width: 100%; height: auto; }

/* HTML iframe */
.html-frame { width: 100%; min-height: 500px; border: 1px solid var(--line); border-radius: 12px; background: #fff; }

/* ---- Mode bar ---- */
.mode-bar {
  position: relative; z-index: 1;
  display: flex; justify-content: center; flex-wrap: wrap; gap: 8px;
  padding: 14px 16px 6px;
}
.mode-item {
  display: flex; align-items: center; gap: 6px;
  padding: 7px 15px; border-radius: 22px;
  border: 1px solid transparent;
  background: rgba(255,255,255,0.7); backdrop-filter: blur(6px);
  cursor: pointer; transition: all 0.22s;
  font-size: 13px; color: var(--ink-soft);
  box-shadow: 0 2px 8px rgba(31,38,86,0.04);
}
.mode-item:hover { transform: translateY(-2px); color: var(--brand-1); box-shadow: 0 6px 16px rgba(102,126,234,0.14); }
.mode-item.active {
  background: linear-gradient(135deg, var(--brand-1), var(--brand-2));
  color: #fff; font-weight: 600; border-color: transparent;
  box-shadow: 0 8px 20px rgba(102,126,234,0.36);
}
.mode-icon { font-size: 16px; }

/* ---- Input area ---- */
.input-area {
  position: relative; z-index: 1;
  margin: 8px clamp(16px, 6vw, 80px) 20px;
  padding: 14px 16px;
  background: rgba(255,255,255,0.9); backdrop-filter: blur(12px);
  border: 1px solid rgba(255,255,255,0.9);
  border-radius: 20px;
  box-shadow: 0 12px 36px rgba(31,38,86,0.09);
  transition: box-shadow 0.25s, border-color 0.25s;
}
.input-area:focus-within {
  border-color: rgba(102,126,234,0.4);
  box-shadow: 0 14px 40px rgba(102,126,234,0.16);
}
.input-area :deep(.el-textarea__inner) {
  border: none !important; box-shadow: none !important;
  background: transparent !important; resize: none;
  font-size: 14px; line-height: 1.7; padding: 4px 6px;
}
.input-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; }
.input-footer :deep(.el-button--primary) {
  background: linear-gradient(135deg, var(--brand-1), var(--brand-2));
  border: none; border-radius: 12px; padding: 9px 22px; font-weight: 600;
  box-shadow: 0 6px 18px rgba(102,126,234,0.32);
  transition: transform 0.2s, box-shadow 0.2s;
}
.input-footer :deep(.el-button--primary:hover) { transform: translateY(-2px); box-shadow: 0 10px 26px rgba(102,126,234,0.44); }
.input-footer :deep(.el-button--primary.is-disabled) { opacity: 0.5; box-shadow: none; transform: none; }

/* ---- Responsive ---- */
@media (max-width: 768px) {
  .msg-bubble.user, .msg-bubble.ai, .resource-bubble { max-width: 90%; }
  .chat-messages { padding: 20px 14px; }
  .input-area { margin: 8px 14px 16px; }
}
</style>
