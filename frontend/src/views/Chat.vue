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
      <!-- Message list -->
      <div class="chat-messages" ref="msgList">
        <div v-if="messages.length === 0" class="chat-empty">
          <div class="empty-icon">💬</div>
          <h2>智学派 AI 助手</h2>
          <p>选择一个模式，输入你的问题或知识点，开始学习之旅</p>
        </div>

        <div v-for="(msg, i) in messages" :key="i" :class="['msg-row', msg.role]">
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
        </div>

        <!-- Loading indicator -->
        <div v-if="loading" class="msg-row assistant">
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
        return { role: 'user', type: 'user', content: m.document || '' }
      }
      return {
        role: 'assistant',
        type: 'text',
        content: renderMarkdownLine(m.document || ''),
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
  messages.value.push({ role: 'user', type: 'user', content: text })
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
      role: 'assistant',
      type: 'text',
      content: renderMarkdownLine(data.answer || ''),
      sources,
      ragCount: sources.length,
      // ChatResponse has no hasKg/hasWeb fields — decorative defaults per plan
      hasKg: data.hasKg !== undefined ? data.hasKg : true,
      hasWeb: data.hasWeb !== undefined ? data.hasWeb : sources.length < 2,
      question: text,
      rawAnswer: data.answer || ''
    })
  } catch (e) {
    ElMessage.error('提问失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  } finally {
    loading.value = false
  }
}

// ---- Resource generation (SSE) — ported from Learning.vue startGenerate() ----
const sendResourceGenerate = async (text) => {
  loading.value = true
  loadingText.value = '🔍 需求分析...'

  // Insert agent-progress placeholder message
  messages.value.push({
    role: 'assistant',
    type: 'agent-progress',
    agents: agents.map(a => ({ ...a, status: 'pending' }))
  })
  const progressIdx = messages.value.length - 1
  await scrollToBottom()

  const token = localStorage.getItem('token') || ''
  const body = JSON.stringify({
    studentId: getStudentId(),
    knowledgePoint: text,
    types: [activeMode.value.toUpperCase()]
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

    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const reader = response.body.getReader()
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
        try {
          handleSseEvent(JSON.parse(json), progressIdx, text)
        } catch { /* skip unparseable */ }
      }
    }
  } catch (e) {
    ElMessage.error('生成失败：' + (e.message || '请稍后重试'))
    // Remove the progress placeholder on failure
    if (messages.value[progressIdx] && messages.value[progressIdx].type === 'agent-progress') {
      messages.value.splice(progressIdx, 1)
    }
  } finally {
    loading.value = false
  }
}

const handleSseEvent = (evt, progressIdx, text) => {
  const stage = evt.stage || ''
  const idx = stageToIdx[stage] ?? -1

  // Update agent progress on the placeholder bubble
  if (idx >= 0 && idx < agents.length
      && messages.value[progressIdx] && messages.value[progressIdx].type === 'agent-progress') {
    messages.value[progressIdx].agents = agents.map((a, i) => ({
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
    if (messages.value[progressIdx] && messages.value[progressIdx].type === 'agent-progress') {
      messages.value.splice(progressIdx, 1)
    }

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
    const resIdx = messages.value.length - 1

    // Async render mermaid mindmap (mutate through the reactive array proxy)
    if (type === 'MINDMAP') {
      nextTick(() => {
        renderMindmap(content).then(svg => {
          if (messages.value[resIdx]) messages.value[resIdx].mindmapSvg = svg
        })
      })
    }

    if (stage === 'DONE') ElMessage.success('资源生成完成')
    if (stage === 'FALLBACK') ElMessage.warning(evt.message || '生成降级完成')

    scrollToBottom()
  }
}

// ---- Helpers (ported from Learning.vue) ----
const renderMarkdownLine = (text) => safeMarkdown(text)

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
/* ---- Layout ---- */
.chat-layout {
  display: flex;
  height: calc(100vh - 48px);
  background: #f5f6fa;
}
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* ---- Message list ---- */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 20px;
}
.chat-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #8890a0;
  text-align: center;
}
.chat-empty .empty-icon { font-size: 56px; margin-bottom: 12px; }
.chat-empty h2 { font-size: 22px; color: #1a1a2e; margin: 0 0 8px; }
.chat-empty p { font-size: 14px; margin: 0; }

.msg-row { display: flex; margin-bottom: 18px; }
.msg-row.user { justify-content: flex-end; }
.msg-row.assistant { justify-content: flex-start; }

.msg-bubble {
  border-radius: 14px;
  padding: 14px 18px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}
.msg-bubble.user {
  max-width: 70%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  border-bottom-right-radius: 4px;
  white-space: pre-wrap;
}
.msg-bubble.ai {
  max-width: 85%;
  background: #fff;
  color: #1a1a2e;
  border: 1px solid #eef0f4;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
  border-bottom-left-radius: 4px;
}

.msg-content { font-size: 14px; line-height: 1.8; }
.msg-source-tags { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.msg-actions { display: flex; gap: 4px; margin-top: 8px; padding-top: 8px; border-top: 1px solid #f2f3f7; }

/* Agent progress bubble */
.agent-progress { padding: 12px 16px; }
.agent-flow-inline { display: flex; flex-wrap: wrap; gap: 8px; }
.agent-tag {
  font-size: 12px; padding: 4px 10px; border-radius: 12px;
  background: #f2f3f7; color: #909399; transition: all 0.25s;
}
.agent-tag.loading { background: #eef0ff; color: #667eea; font-weight: 600; }
.agent-tag.done { background: #f0f9eb; color: #67c23a; }

/* Loading bubble */
.loading-bubble { display: flex; align-items: center; gap: 8px; color: #667eea; }

/* ---- Resource bubble ---- */
.resource-bubble { width: 85%; max-width: 85%; }
.resource-header { display: flex; align-items: center; gap: 12px; }
.resource-icon { font-size: 30px; }
.resource-title-wrap { flex: 1; min-width: 0; }
.resource-title-wrap strong { display: block; font-size: 15px; color: #1a1a2e; }
.resource-meta { font-size: 12px; color: #909399; }
.resource-summary { font-size: 13px; color: #606266; margin: 10px 0; }
.resource-actions { display: flex; gap: 8px; margin-bottom: 8px; }
.code-hint { font-size: 12px; color: #909399; margin: 10px 0 0; }
.raw-fallback { white-space: pre-wrap; font-size: 13px; color: #4a4f5e; }

/* DOC markdown */
.doc-render { font-size: 14px; line-height: 1.8; color: #2c3142; }
.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3) { margin: 16px 0 8px; color: #1a1a2e; }
.markdown-body :deep(p) { margin: 8px 0; }
.markdown-body :deep(pre) { background: #f6f8fa; padding: 12px; border-radius: 8px; overflow: auto; }
.markdown-body :deep(code) { background: #f0f2f5; padding: 2px 5px; border-radius: 4px; font-size: 13px; }
.markdown-body :deep(pre code) { background: none; padding: 0; }
.markdown-body :deep(table) { border-collapse: collapse; margin: 12px 0; }
.markdown-body :deep(th), .markdown-body :deep(td) { border: 1px solid #dcdfe6; padding: 6px 12px; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 24px; }
.markdown-body :deep(.katex-display) { margin: 16px 0; overflow-x: auto; overflow-y: hidden; }
.markdown-body :deep(.katex) { font-size: 1.1em; }

/* QUIZ */
.quiz-toolbar { margin-bottom: 12px; text-align: right; }
.quiz-item { padding: 16px; border: 1px solid #eef0f4; border-radius: 12px; margin-bottom: 12px; }
.quiz-q { display: flex; align-items: flex-start; gap: 8px; font-size: 14px; font-weight: 600; color: #1a1a2e; }
.quiz-num { background: #667eea; color: #fff; border-radius: 50%; width: 22px; height: 22px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; flex-shrink: 0; }
.quiz-type-tag { background: #eef0ff; color: #667eea; font-size: 11px; padding: 2px 8px; border-radius: 8px; flex-shrink: 0; }
.quiz-text { flex: 1; }
.quiz-options { list-style: none; padding: 0; margin: 10px 0 0 30px; }
.quiz-option-item {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 10px 14px; margin: 4px 0;
  border: 1.5px solid #e4e7ed; border-radius: 10px;
  cursor: pointer; transition: all 0.2s;
  font-size: 14px; color: #4a4f5e; list-style: none;
}
.quiz-option-item:hover { border-color: #667eea; background: #f8f7ff; }
.quiz-option-item.option-selected { border-color: #667eea; background: #f0efff; }
.quiz-option-item.option-correct { border-color: #67c23a; background: #f0f9eb; }
.quiz-option-item.option-incorrect { border-color: #f56c6c; background: #fef0f0; }
.quiz-option-item.option-reveal-correct { border-color: #67c23a; background: #f0f9eb; }
.option-marker {
  font-weight: 700; font-size: 14px; min-width: 20px;
  color: #667eea; text-align: center; flex-shrink: 0;
}
.option-correct .option-marker { color: #67c23a; }
.option-incorrect .option-marker { color: #f56c6c; }
.option-text { flex: 1; }
.quiz-answer { margin: 12px 0 0 30px; padding: 12px; background: #f0f9eb; border-radius: 8px; font-size: 13px; }
.quiz-answer-row { color: #529b2e; margin-bottom: 6px; }
.quiz-explain { color: #606266; line-height: 1.6; }
.quiz-written { margin: 10px 0 0 30px; }
.written-area { margin-bottom: 10px; opacity: 0.7; }

/* CODE */
.code-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.code-lang { font-size: 12px; color: #909399; font-weight: 600; }
.code-toolbar-actions { display: flex; gap: 8px; }
.code-editor :deep(textarea) {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace !important;
  font-size: 13px !important; line-height: 1.6 !important;
  background: #1e1e2e !important; color: #e0e0e0 !important;
  border-radius: 10px !important; border-color: #3a3a5c !important;
}
.code-editor :deep(textarea):focus { border-color: #667eea !important; }
.code-output { margin-top: 12px; border: 1px solid #e4e7ed; border-radius: 10px; overflow: hidden; }
.output-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 14px; background: #f5f7fa; border-bottom: 1px solid #e4e7ed;
}
.output-label { font-size: 13px; font-weight: 600; color: #303133; }
.output-meta { font-size: 11px; color: #909399; font-family: monospace; }
.output-body {
  margin: 0; padding: 12px 14px; background: #fafbfc;
  font-family: 'Consolas', 'Monaco', monospace; font-size: 13px; line-height: 1.5;
  white-space: pre-wrap; word-break: break-word;
  color: #303133; max-height: 400px; overflow: auto;
}
.output-body.output-error { color: #f56c6c; background: #fef0f0; }

/* MINDMAP */
.mindmap-svg { overflow-x: auto; padding: 12px 0; }
.mindmap-svg :deep(svg) { max-width: 100%; height: auto; }

/* HTML iframe */
.html-frame { width: 100%; min-height: 500px; border: 1px solid #eef0f4; border-radius: 10px; background: #fff; }

/* ---- Mode bar ---- */
.mode-bar {
  display: flex; justify-content: center; flex-wrap: wrap; gap: 8px;
  padding: 12px 16px; border-top: 1px solid #eef0f4; background: #fff;
}
.mode-item {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 14px; border-radius: 20px;
  border: 1.5px solid #eef0f4; cursor: pointer; transition: all 0.2s;
  font-size: 13px; color: #4a4f5e;
}
.mode-item:hover { border-color: #c4c9ff; }
.mode-item.active {
  border-color: #667eea; background: #f0eeff; color: #667eea; font-weight: 600;
}
.mode-icon { font-size: 16px; }

/* ---- Input area ---- */
.input-area {
  padding: 16px 20px; border-top: 1px solid #eef0f4; background: #fff;
}
.input-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; }

/* ---- Responsive ---- */
@media (max-width: 768px) {
  .msg-bubble.user, .msg-bubble.ai, .resource-bubble { max-width: 95%; }
}
</style>
