<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <h1>🤖 沉浸学习</h1>
        <p>多智能体协同工作，为你生成个性化多模态学习资源</p>
      </div>
    </div>

    <!-- 资源类型选择 -->
    <section class="section">
      <h3>选择资源类型</h3>
      <div class="resource-grid">
        <div
          v-for="res in resourceTypes"
          :key="res.type"
          :class="['res-card', { selected: selectedType === res.type }]"
          @click="selectedType = res.type"
        >
          <span class="res-icon">{{ res.icon }}</span>
          <span class="res-label">{{ res.label }}</span>
          <span class="res-desc">{{ res.desc }}</span>
        </div>
      </div>
    </section>

    <!-- 输入区 -->
    <section class="section">
      <h3>输入学习需求</h3>
      <div class="input-area">
        <el-input
          v-model="topic"
          placeholder="输入知识点或课程主题，如：SVM 支持向量机原理"
          size="large"
          class="topic-input"
        />
        <el-input
          v-model="context"
          type="textarea"
          :rows="3"
          placeholder="补充信息（可选）：如专业、年级、学习目标、薄弱环节等"
        />
        <div class="input-row">
          <el-select v-model="difficulty" placeholder="难度" style="width: 140px">
            <el-option label="入门" value="beginner" />
            <el-option label="进阶" value="intermediate" />
            <el-option label="高级" value="advanced" />
          </el-select>
          <el-button type="primary" size="large" :icon="MagicStick" :loading="generating" @click="startGenerate" :disabled="!topic.trim()">
            开始生成
          </el-button>
        </div>
      </div>
    </section>

    <!-- 智能体执行流程 -->
    <section v-if="agentSteps.length > 0" class="section">
      <h3>智能体协作流程</h3>
      <div class="agent-flow">
        <div v-for="(agent, i) in agentSteps" :key="agent.name" class="agent-item-wrap">
          <div :class="['agent-item', `agent-${agent.status}`]">
            <div class="agent-avatar">{{ agent.avatar }}</div>
            <div class="agent-info">
              <strong>{{ agent.name }}</strong>
              <span>{{ agent.desc }}</span>
            </div>
            <el-icon v-if="agent.status === 'done'" class="agent-check"><CircleCheckFilled /></el-icon>
            <el-icon v-else-if="agent.status === 'loading'" class="agent-loading"><Loading /></el-icon>
          </div>
          <div v-if="i < agentSteps.length - 1" class="agent-arrow">↓</div>
        </div>
      </div>
    </section>

    <!-- 生成结果 -->
    <section v-if="result" class="section">
      <h3>生成结果</h3>
      <div class="result-card">
        <div class="result-header">
          <span class="result-icon">{{ resultIcon }}</span>
          <div class="result-title-wrap">
            <strong>{{ result.title }}</strong>
            <span class="result-meta">{{ result.typeLabel }}</span>
          </div>
          <el-button type="primary" plain :icon="Download" @click="downloadResult">下载</el-button>
        </div>

        <!-- DOC: rendered markdown -->
        <div v-if="result.type === 'DOC'" class="doc-render markdown-body" v-html="renderedHtml"></div>

        <!-- QUIZ: interactive question cards -->
        <div v-else-if="result.type === 'QUIZ'" class="quiz-render">
          <div v-if="quizData">
            <div class="quiz-toolbar">
              <el-button size="small" @click="showAnswers = !showAnswers">
                {{ showAnswers ? '隐藏解析' : '显示全部解析' }}
              </el-button>
            </div>
            <div v-for="(q, i) in quizData.questions" :key="i" class="quiz-item">
              <div class="quiz-q">
                <span class="quiz-num">{{ i + 1 }}</span>
                <span class="quiz-type-tag">{{ quizTypeLabel(q.type) }}</span>
                <span class="quiz-text">{{ q.question }}</span>
              </div>
              <!-- Interactive MC / TF options -->
              <ul v-if="q.options && q.options.length && (q.type === 'MC' || q.type === 'TF')" class="quiz-options">
                <li
                  v-for="(opt, j) in q.options" :key="j"
                  :class="[
                    'quiz-option-item',
                    {
                      'option-selected': selectedOption[i] === optionLetter(opt),
                      'option-correct': selectedOption[i] === optionLetter(opt) && optionResult[i] === 'correct',
                      'option-incorrect': selectedOption[i] === optionLetter(opt) && optionResult[i] === 'incorrect',
                      'option-reveal-correct': optionResult[i] === 'incorrect' && isCorrectAnswer(q, opt)
                    }
                  ]"
                  @click="selectOption(i, opt)"
                >
                  <span class="option-marker">
                    <span v-if="selectedOption[i] === optionLetter(opt) && optionResult[i] === 'correct'">✅</span>
                    <span v-else-if="selectedOption[i] === optionLetter(opt) && optionResult[i] === 'incorrect'">❌</span>
                    <span v-else-if="optionResult[i] === 'incorrect' && isCorrectAnswer(q, opt)">✅</span>
                    <span v-else>{{ optionLetter(opt) }}</span>
                  </span>
                  <span class="option-text">{{ opt.replace(/^[A-Z][.)]\s*/, '') }}</span>
                </li>
              </ul>
              <!-- Non-interactive options for other types -->
              <ul v-else-if="q.options && q.options.length" class="quiz-options">
                <li v-for="(opt, j) in q.options" :key="j">{{ opt }}</li>
              </ul>
              <div v-if="showAnswers || optionResult[i]" class="quiz-answer">
                <div class="quiz-answer-row">
                  <strong>答案：</strong>{{ q.answer }}
                  <span v-if="optionResult[i] === 'correct'" style="color:#67c23a; margin-left:8px;">✓ 正确!</span>
                  <span v-else-if="optionResult[i] === 'incorrect'" style="color:#f56c6c; margin-left:8px;">✗ 不对</span>
                </div>
                <div v-if="q.explanation" class="quiz-explain"><strong>解析：</strong>{{ q.explanation }}</div>
              </div>
            </div>
          </div>
          <pre v-else class="raw-fallback">{{ result.content }}</pre>
        </div>

        <!-- CODE: code block + copy + execution output -->
        <div v-else-if="result.type === 'CODE'" class="code-render">
          <div class="code-toolbar">
            <span class="code-lang">Python</span>
            <el-button size="small" :icon="DocumentCopy" @click="copyCode">复制代码</el-button>
          </div>
          <pre class="code-block"><code>{{ result.content }}</code></pre>
          <p class="code-hint">💡 上方代码已在后端沙箱运行，输出以注释形式嵌入在代码顶部。</p>
        </div>

        <!-- HTML: live interactive iframe -->
        <div v-else-if="result.type === 'HTML'" class="html-render">
          <iframe
            :srcdoc="result.content"
            sandbox="allow-scripts allow-same-origin"
            class="html-frame"
            title="交互课件"
          ></iframe>
          <p class="code-hint">💡 这是真实可交互的网页课件，在沙箱中运行。点击下载可保存为 .html 离线打开。</p>
        </div>

        <!-- MINDMAP: rendered Mermaid SVG -->
        <div v-else-if="result.type === 'MINDMAP'" class="mindmap-render">
          <div v-if="mindmapSvg" class="mindmap-svg" v-html="mindmapSvg"></div>
          <pre v-else class="code-block">{{ result.content }}</pre>
          <p class="code-hint">💡 思维导图由 Mermaid 实时渲染。点击下载可保存为 .mmd 离线使用。</p>
        </div>

        <!-- PPT / VIDEO: file-based, download only -->
        <div v-else-if="result.type === 'PPT' || result.type === 'VIDEO'" class="file-render">
          <el-result icon="success" :title="`${result.typeLabel}已生成`" sub-title="点击上方“下载”按钮保存到本地">
          </el-result>
        </div>

        <!-- Fallback -->
        <pre v-else class="raw-fallback">{{ result.content }}</pre>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, CircleCheckFilled, Loading, Download, DocumentCopy } from '@element-plus/icons-vue'
import { marked } from 'marked'
import katex from 'katex'
import 'katex/dist/katex.min.css'
import mermaid from 'mermaid'

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

const selectedType = ref('doc')
const topic = ref('')
const context = ref('')
const difficulty = ref('intermediate')
const generating = ref(false)
const agentSteps = ref([])
const result = ref(null)
const quizData = ref(null)
const showAnswers = ref(false)
const mindmapSvg = ref('')
// Interactive quiz state: { questionIndex: 'A' }
const selectedOption = ref({})
// { questionIndex: 'correct' | 'incorrect' }
const optionResult = ref({})

const TYPE_LABELS = {
  DOC: '课程文档 (Markdown)', QUIZ: '练习题库', CODE: '代码案例',
  HTML: '交互课件', MINDMAP: '思维导图', PPT: '教学PPT', VIDEO: '教学视频'
}
const TYPE_ICONS = {
  DOC: '📄', QUIZ: '📝', CODE: '💻', HTML: '🌐', MINDMAP: '🧩', PPT: '📊', VIDEO: '🎬'
}

const resultIcon = computed(() => result.value ? (TYPE_ICONS[result.value.type] || '📄') : '📄')
const renderedHtml = computed(() => {
  if (!result.value || result.value.type !== 'DOC') return ''
  try { return marked.parse(result.value.content || '') } catch { return result.value.content }
})

const renderMindmap = async (content) => {
  mindmapSvg.value = ''
  if (!content) return
  try {
    // Strip markdown fences if present
    let code = content.trim()
    if (code.startsWith('```')) {
      code = code.replace(/^```\w*\n?/, '').replace(/\n?```$/, '')
    }
    const id = 'mindmap-' + Math.random().toString(36).slice(2, 8)
    const { svg } = await mermaid.render(id, code)
    mindmapSvg.value = svg
  } catch (e) {
    console.warn('Mermaid render failed:', e.message)
    mindmapSvg.value = ''
  }
}

const optionLetter = (opt) => {
  // Extract letter from "A. xxx" or "A) xxx" prefix
  const m = (opt || '').match(/^([A-Z])[.)]\s/)
  return m ? m[1] : ''
}

const resetQuiz = () => {
  selectedOption.value = {}
  optionResult.value = {}
}

const selectOption = (qIndex, optText) => {
  const q = quizData.value?.questions?.[qIndex]
  if (!q || q.type === 'SHORT_ANSWER') return
  const letter = optionLetter(optText)
  if (!letter) return
  selectedOption.value = { ...selectedOption.value, [qIndex]: letter }
  // TF: answer is "True"/"False", map A→True, B→False
  let correctAnswer = q.answer?.trim() || ''
  if (q.type === 'TF') {
    if (letter === 'A') correctAnswer = 'True'
    else if (letter === 'B') correctAnswer = 'False'
  }
  const isCorrect = letter === correctAnswer || letter.toLowerCase() === correctAnswer.toLowerCase()
  optionResult.value = { ...optionResult.value, [qIndex]: isCorrect ? 'correct' : 'incorrect' }
}

const isCorrectAnswer = (q, optText) => {
  if (!q) return false
  const letter = optionLetter(optText)
  // TF: answer is "True"/"False"
  if (q.type === 'TF') {
    const ans = q.answer?.trim() || ''
    return (letter === 'A' && ans === 'True') || (letter === 'B' && ans === 'False')
  }
  return letter === (q.answer?.trim() || '')
}

const quizTypeLabel = (t) => ({
  MC: '单选', TF: '判断', SHORT_ANSWER: '简答', FILL_BLANK: '填空'
}[t] || t)

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
  quizData.value = null
  showAnswers.value = false
  mindmapSvg.value = ''
  resetQuiz()

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

      const lines = buffer.split('\n')
      buffer = lines.pop()
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

  if (idx >= 0 && idx < agents.length) {
    agentSteps.value = agents.slice(0, idx + 1).map((a, i) => ({
      name: a.name, avatar: a.avatar, desc: a.desc,
      status: i < idx ? 'done' : 'loading'
    }))
  }

  if (stage === 'DONE' || stage === 'FALLBACK') {
    agentSteps.value = agents.map(a => ({
      name: a.name, avatar: a.avatar, desc: a.desc, status: 'done'
    }))

    const typeKey = selectedType.value.toUpperCase()
    const payload = evt.payload || {}
    const item = payload[typeKey] || Object.values(payload)[0]

    if (item) {
      result.value = {
        type: item.type || typeKey,
        typeLabel: TYPE_LABELS[item.type || typeKey] || (item.type || typeKey),
        title: item.title || `${topic.value} - 学习资源`,
        content: item.content || '',
        downloadPath: item.downloadPath || null
      }
      // Parse quiz JSON
      if (result.value.type === 'QUIZ') {
        try { quizData.value = JSON.parse(result.value.content) } catch { quizData.value = null }
      }
      // Render mermaid mindmap
      if (result.value.type === 'MINDMAP') {
        nextTick(() => renderMindmap(result.value.content))
      }
    } else {
      result.value = {
        type: typeKey,
        typeLabel: TYPE_LABELS[typeKey] || typeKey,
        title: `${topic.value} - 学习资源`,
        content: evt.message || '生成完成',
        downloadPath: null
      }
    }

    if (stage === 'DONE') ElMessage.success('资源生成完成')
    if (stage === 'FALLBACK') ElMessage.warning(evt.message || '生成降级完成')
  }
}

const copyCode = async () => {
  try {
    await navigator.clipboard.writeText(result.value.content)
    ElMessage.success('代码已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

const downloadResult = () => {
  const r = result.value
  if (!r) return

  // File-based types → backend download endpoint
  if (r.type === 'PPT' || r.type === 'VIDEO') {
    if (r.downloadPath) {
      window.open(`/api/generate/download/${r.downloadPath}`, '_blank')
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
  const ext = extMap[r.type] || 'txt'
  const mime = mimeMap[r.type] || 'text/plain'
  const safeName = (topic.value || 'resource').replace(/[\\/:*?"<>|]/g, '_')

  const blob = new Blob([r.content], { type: mime })
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
</script>

<style scoped>
.page-container { max-width: 1000px; margin: 0 auto; padding: 32px 24px 60px; }

.page-header {
  background: #fff; padding: 28px 32px; border-radius: 20px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.header-left h1 { font-size: 26px; font-weight: 700; color: #1a1a2e; margin: 0 0 6px; }
.header-left p { font-size: 14px; color: #8890a0; margin: 0; }

.section { margin-top: 28px; }
.section h3 { font-size: 18px; font-weight: 600; color: #1a1a2e; margin: 0 0 14px; }

/* 资源类型选择 */
.resource-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 12px; }
.res-card {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  padding: 18px 10px; background: #fff; border-radius: 14px;
  border: 2px solid #eef0f4; cursor: pointer; transition: all 0.25s;
}
.res-card:hover { border-color: #c4c9ff; }
.res-card.selected { border-color: #667eea; background: #f8f7ff; }
.res-icon { font-size: 30px; }
.res-label { font-size: 13px; font-weight: 600; color: #1a1a2e; }
.res-desc { font-size: 11px; color: #909399; }

/* 输入区 */
.input-area {
  background: #fff; padding: 24px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
  display: flex; flex-direction: column; gap: 14px;
}
.input-row { display: flex; gap: 12px; align-items: center; }

/* 智能体流程 */
.agent-flow { display: flex; flex-direction: column; align-items: center; }
.agent-item-wrap { display: flex; flex-direction: column; align-items: center; width: 100%; max-width: 500px; }
.agent-item {
  display: flex; align-items: center; gap: 14px;
  width: 100%; padding: 16px 20px;
  background: #fff; border-radius: 14px;
  border: 1px solid #eef0f4; transition: all 0.3s;
}
.agent-loading { border-color: #667eea; background: #f8f7ff; }
.agent-done { opacity: 0.7; }
.agent-avatar { font-size: 28px; }
.agent-info { flex: 1; }
.agent-info strong { display: block; font-size: 14px; color: #1a1a2e; margin-bottom: 2px; }
.agent-info span { font-size: 12px; color: #909399; }
.agent-check { color: #67c23a; font-size: 20px; }
.agent-loading-icon { color: #667eea; font-size: 20px; animation: spin 1s linear infinite; }
.agent-arrow { color: #c0c4cc; font-size: 20px; margin: 4px 0; }

@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

/* 结果 */
.result-card {
  background: #fff; border-radius: 16px; padding: 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.result-header { display: flex; align-items: center; gap: 14px; margin-bottom: 16px; }
.result-icon { font-size: 36px; }
.result-title-wrap { flex: 1; }
.result-header strong { display: block; font-size: 16px; color: #1a1a2e; }
.result-meta { font-size: 12px; color: #909399; }

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
/* KaTeX display math */
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
.quiz-options li { padding: 6px 0; font-size: 14px; color: #4a4f5e; }
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

/* CODE */
.code-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.code-lang { font-size: 12px; color: #909399; font-weight: 600; }
.code-block { background: #1e1e2e; color: #e0e0e0; padding: 16px; border-radius: 10px; font-family: 'Consolas', 'Monaco', monospace; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.code-hint { font-size: 12px; color: #909399; margin: 10px 0 0; }

/* MINDMAP */
.mindmap-svg { overflow-x: auto; padding: 12px 0; }
.mindmap-svg :deep(svg) { max-width: 100%; height: auto; }

/* HTML iframe */
.html-frame { width: 100%; min-height: 600px; border: 1px solid #eef0f4; border-radius: 10px; background: #fff; }

/* fallback */
.raw-fallback { white-space: pre-wrap; font-size: 13px; color: #4a4f5e; }

@media (max-width: 800px) {
  .resource-grid { grid-template-columns: repeat(4, 1fr); }
}
</style>
