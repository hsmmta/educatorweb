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
          :class="['res-card', { selected: selectedTypes.includes(res.type) }]"
          @click="toggleType(res.type)"
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
          :rows="2"
          placeholder="补充信息（可选）：如专业、年级、学习目标等"
        />
        <div class="input-row">
          <span class="input-hint">已选 {{ selectedTypes.length }} 种资源类型</span>
          <el-button
            type="primary"
            size="large"
            :icon="MagicStick"
            :loading="generating"
            @click="startGenerate"
            :disabled="!topic.trim() || selectedTypes.length === 0"
          >
            开始生成
          </el-button>
        </div>
      </div>
    </section>

    <!-- 生成进度 -->
    <section v-if="generating || agentSteps.length > 0" class="section">
      <h3>智能体协作流程</h3>
      <div class="progress-bar-wrap">
        <el-progress :percentage="progressPercent" :stroke-width="8" :color="progressColor" />
        <span class="progress-text">{{ progressMessage }}</span>
      </div>
      <div class="agent-flow">
        <div v-for="(agent, i) in agentSteps" :key="agent.name" class="agent-item-wrap">
          <div :class="['agent-item', `agent-${agent.status}`]">
            <div class="agent-avatar">{{ agent.avatar }}</div>
            <div class="agent-info">
              <strong>{{ agent.name }}</strong>
              <span>{{ agent.desc }}</span>
            </div>
            <el-icon v-if="agent.status === 'done'" class="agent-check"><CircleCheckFilled /></el-icon>
            <el-icon v-else-if="agent.status === 'loading'" class="agent-loading-icon"><Loading /></el-icon>
            <el-icon v-else-if="agent.status === 'error'" class="agent-error"><WarningFilled /></el-icon>
          </div>
          <div v-if="i < agentSteps.length - 1" class="agent-arrow">↓</div>
        </div>
      </div>
    </section>

    <!-- 生成结果 -->
    <section v-if="results.length > 0" class="section">
      <h3>生成结果（{{ results.length }} 项）</h3>
      <div class="result-list">
        <div v-for="res in results" :key="res.resourceId || res.type" class="result-card" @click="activeResult = res">
          <div class="result-header">
            <span class="result-icon">{{ iconForType(res.type) }}</span>
            <div class="result-meta">
              <strong>{{ res.title || (topic + ' - ' + labelForType(res.type)) }}</strong>
              <span class="result-type">{{ labelForType(res.type) }} · {{ formatSize(res) }}</span>
            </div>
            <div class="result-actions">
              <el-tag v-if="res.qualityPassed === true" type="success" size="small">已审核</el-tag>
              <el-tag v-else-if="res.qualityPassed === false" type="warning" size="small">待复核</el-tag>
            </div>
          </div>
        </div>
      </div>

      <!-- 内容预览 -->
      <div v-if="activeResult" class="preview-card">
        <div class="preview-header">
          <span>{{ activeResult.title || (topic + ' - ' + labelForType(activeResult.type)) }}</span>
          <el-button size="small" text :icon="Download">下载</el-button>
        </div>
        <div class="preview-body">
          <!-- Markdown 文档 -->
          <div v-if="activeResult.type === 'DOC' || activeResult.type === 'MINDMAP'" class="markdown-body" v-html="renderMarkdown(activeResult.content || '')"></div>
          <!-- HTML 交互课件 -->
          <div v-else-if="activeResult.type === 'HTML'" class="html-frame">
            <iframe :srcdoc="activeResult.content" sandbox="allow-scripts allow-same-origin" class="html-iframe"></iframe>
          </div>
          <!-- 测验 JSON -->
          <div v-else-if="activeResult.type === 'QUIZ'" class="quiz-preview">
            <pre class="code-block">{{ formatQuiz(activeResult.content) }}</pre>
          </div>
          <!-- 代码 -->
          <div v-else-if="activeResult.type === 'CODE'" class="code-preview">
            <pre class="code-block"><code>{{ activeResult.content }}</code></pre>
          </div>
          <!-- 默认文本 -->
          <div v-else class="text-preview">{{ activeResult.content }}</div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import { MagicStick, CircleCheckFilled, Loading, WarningFilled, Download } from '@element-plus/icons-vue'
import { createGenerateStream } from '../api/index.js'

const topic = ref('')
const context = ref('')
const selectedTypes = ref(['doc'])
const generating = ref(false)
const progressPercent = ref(0)
const progressMessage = ref('')
const progressColor = ref('#667eea')
const agentSteps = ref([])
const results = ref([])
const activeResult = ref(null)
let currentController = null

const resourceTypes = [
  { type: 'DOC',     icon: '📄', label: '课程文档', desc: '结构化讲解文档' },
  { type: 'PPT',     icon: '📊', label: '教学PPT',  desc: '演示文稿课件' },
  { type: 'QUIZ',    icon: '📝', label: '练习题库', desc: '选择题+简答题' },
  { type: 'MINDMAP', icon: '🧩', label: '思维导图', desc: '知识结构可视化' },
  { type: 'VIDEO',   icon: '🎬', label: '教学视频', desc: '多模态动画讲解' },
  { type: 'CODE',    icon: '💻', label: '代码案例', desc: '可运行示例代码' },
  { type: 'HTML',    icon: '🌐', label: '交互课件', desc: '网页交互式学习' }
]

const agentDefs = [
  { name: 'RequireAgent',  key: 'REQUIRE',  avatar: '🔍', desc: '需求分析：收集学习者画像与知识背景' },
  { name: 'DesignAgent',   key: 'DESIGN',   avatar: '🎨', desc: '方案设计：规划资源结构与内容大纲' },
  { name: 'Generator',     key: 'GEN',      avatar: '⚙️', desc: '内容生成：调用模型生成核心内容（并行）' },
  { name: 'ReviewAgent',   key: 'REVIEW',   avatar: '🛡️', desc: '质量审核：内容安全过滤与事实核查' }
]

function toggleType(type) {
  const idx = selectedTypes.value.indexOf(type)
  if (idx >= 0) {
    if (selectedTypes.value.length > 1) selectedTypes.value.splice(idx, 1)
  } else {
    selectedTypes.value.push(type)
  }
}

const iconForType = (t) => resourceTypes.find(r => r.type === t)?.icon || '📄'
const labelForType = (t) => resourceTypes.find(r => r.type === t)?.label || t

function formatSize(res) {
  if (res.metadata?.size) return res.metadata.size
  if (res.content) {
    const bytes = new Blob([res.content]).size
    if (bytes > 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + 'MB'
    if (bytes > 1024) return (bytes / 1024).toFixed(0) + 'KB'
    return bytes + 'B'
  }
  return ''
}

function formatQuiz(content) {
  try {
    const q = typeof content === 'string' ? JSON.parse(content) : content
    return JSON.stringify(q, null, 2)
  } catch {
    return content
  }
}

// 简易 Markdown 渲染（将对接到 markdown-it 时替换）
function renderMarkdown(content) {
  if (!content) return ''
  // 基础 Markdown -> HTML
  let html = content
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    // 代码块
    .replace(/```(\w*)\n([\s\S]*?)```/g, '<pre class="code-block"><code class="language-$1">$2</code></pre>')
    // 行内代码
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    // 标题
    .replace(/^#### (.+)$/gm, '<h4>$1</h4>')
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    // 加粗 / 斜体
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    // 列表
    .replace(/^- (.+)$/gm, '<li>$1</li>')
    .replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>')
    // 链接
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>')
    // 换行
    .replace(/\n\n/g, '</p><p>')
    .replace(/\n/g, '<br>')
  return '<p>' + html + '</p>'
}

function mapStageToAgent(stage) {
  if (!stage) return null
  const s = stage.toUpperCase()
  if (s.includes('REQUIRE')) return 'RequireAgent'
  if (s.includes('DESIGN')) return 'DesignAgent'
  if (s.includes('GEN')) return 'Generator'
  if (s.includes('REVIEW')) return 'ReviewAgent'
  if (s === 'INIT') return 'RequireAgent'
  if (s === 'DONE' || s === 'FALLBACK') return 'ReviewAgent'
  return null
}

const startGenerate = () => {
  if (!topic.value.trim() || selectedTypes.value.length === 0) return

  // 取消之前的请求
  if (currentController) { currentController.abort(); currentController = null }

  generating.value = true
  progressPercent.value = 0
  progressMessage.value = '正在初始化...'
  results.value = []
  activeResult.value = null

  // 初始化智能体状态
  agentSteps.value = agentDefs.map(a => ({
    name: a.name, avatar: a.avatar, desc: a.desc, key: a.key, status: 'pending'
  }))

  // 标记第一个 agent 为 loading
  if (agentSteps.value.length > 0) agentSteps.value[0].status = 'loading'

  const studentId = getStudentId()

  currentController = createGenerateStream({
    studentId,
    knowledgePoint: topic.value,
    types: selectedTypes.value
  }, {
    onEvent: (event) => {
      // event: { requestId, stage, message, progressPercent, payload }
      progressPercent.value = Math.min(event.progressPercent || 0, 100)
      progressMessage.value = event.message || '生成中...'

      // 更新进度条颜色
      if (event.stage === 'FALLBACK') {
        progressColor.value = '#e6a23c'
      } else if (event.stage === 'DONE') {
        progressColor.value = '#67c23a'
      }

      // 更新智能体状态
      const agentName = mapStageToAgent(event.stage)
      if (agentName) {
        const agentIdx = agentSteps.value.findIndex(a => a.name === agentName)
        if (agentIdx >= 0) {
          // 标记之前的 agent 为 done
          for (let i = 0; i < agentIdx; i++) {
            if (agentSteps.value[i].status === 'loading') {
              agentSteps.value[i].status = 'done'
            }
          }
          if (event.stage === 'DONE' || event.stage === 'FALLBACK') {
            agentSteps.value[agentIdx].status = 'done'
          } else {
            agentSteps.value[agentIdx].status = 'loading'
          }
        }
      }

      // 提取生成结果
      if (event.payload) {
        const payload = event.payload
        // 检查是否包含结果
        if (payload.results && typeof payload.results === 'object') {
          const resMap = payload.results
          const newResults = []
          for (const [type, resource] of Object.entries(resMap)) {
            const existingIdx = results.value.findIndex(r => r.type === type)
            const resObj = {
              type,
              title: resource.title || (topic.value + ' - ' + labelForType(type)),
              content: resource.content || '',
              metadata: resource.metadata || {},
              resourceId: resource.resourceId,
              qualityPassed: payload.passed
            }
            if (existingIdx >= 0) {
              results.value[existingIdx] = resObj
            } else {
              newResults.push(resObj)
            }
          }
          if (newResults.length > 0) {
            results.value = [...results.value, ...newResults]
            // 自动选中第一个结果
            if (!activeResult.value && results.value.length > 0) {
              activeResult.value = results.value[0]
            }
          }
        }
        // 检查蓝图信息
        if (payload.blueprint) {
          progressMessage.value = '设计方案: ' + (payload.blueprint.title || '已生成教学蓝图')
        }
      }

      // DONE 或 FALLBACK 时标记所有 agent 完成
      if (event.stage === 'DONE' || event.stage === 'FALLBACK') {
        agentSteps.value = agentSteps.value.map(a => ({ ...a, status: 'done' }))
        if (event.stage === 'FALLBACK') {
          // 标记失败
          const reviewIdx = agentSteps.value.findIndex(a => a.key === 'REVIEW')
          if (reviewIdx >= 0) agentSteps.value[reviewIdx].status = 'error'
        }
      }
    },

    onComplete: () => {
      generating.value = false
      progressPercent.value = 100
      progressMessage.value = '生成完成！'
      currentController = null
      if (results.value.length > 0) {
        ElNotification({
          title: '资源生成完成',
          message: `成功生成 ${results.value.length} 项学习资源`,
          type: 'success'
        })
      }
    },

    onError: (err) => {
      generating.value = false
      currentController = null
      progressMessage.value = '生成失败: ' + (err.message || '未知错误')
      progressColor.value = '#f56c6c'
      // 标记当前 loading 的 agent 为 error
      agentSteps.value = agentSteps.value.map(a =>
        a.status === 'loading' ? { ...a, status: 'error' } : a
      )
      ElMessage.error('资源生成失败: ' + (err.message || '请稍后重试'))
    }
  })
}

function getStudentId() {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.id || info.phone || 'anonymous'
  } catch {
    return 'anonymous'
  }
}
</script>

<style scoped>
.page-container { max-width: 1100px; margin: 0 auto; padding: 32px 24px 60px; }

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
  user-select: none;
}
.res-card:hover { border-color: #c4c9ff; transform: translateY(-1px); }
.res-card.selected { border-color: #667eea; background: #f8f7ff; box-shadow: 0 2px 8px rgba(102,126,234,0.12); }
.res-icon { font-size: 30px; }
.res-label { font-size: 13px; font-weight: 600; color: #1a1a2e; }
.res-desc { font-size: 11px; color: #909399; }

/* 输入区 */
.input-area {
  background: #fff; padding: 24px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
  display: flex; flex-direction: column; gap: 14px;
}
.input-row { display: flex; justify-content: space-between; align-items: center; }
.input-hint { font-size: 13px; color: #909399; }

/* 进度条 */
.progress-bar-wrap { margin-bottom: 16px; }
.progress-text { display: block; font-size: 13px; color: #667eea; margin-top: 6px; }

/* 智能体流程 */
.agent-flow { display: flex; flex-direction: column; align-items: center; }
.agent-item-wrap { display: flex; flex-direction: column; align-items: center; width: 100%; max-width: 520px; }
.agent-item {
  display: flex; align-items: center; gap: 14px;
  width: 100%; padding: 16px 20px;
  background: #fff; border-radius: 14px;
  border: 1px solid #eef0f4; transition: all 0.3s;
}
.agent-pending { opacity: 0.5; }
.agent-loading { border-color: #667eea; background: #f8f7ff; box-shadow: 0 2px 8px rgba(102,126,234,0.1); }
.agent-done { border-color: #c0e0c8; background: #f6fef8; }
.agent-error { border-color: #fbc4c4; background: #fff5f5; }
.agent-avatar { font-size: 28px; }
.agent-info { flex: 1; }
.agent-info strong { display: block; font-size: 14px; color: #1a1a2e; margin-bottom: 2px; }
.agent-info span { font-size: 12px; color: #909399; }
.agent-check { color: #67c23a; font-size: 20px; }
.agent-loading-icon { color: #667eea; font-size: 20px; animation: spin 1s linear infinite; }
.agent-error .agent-error-icon { color: #f56c6c; font-size: 20px; }
.agent-arrow { color: #c0c4cc; font-size: 20px; margin: 4px 0; }

@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

/* 结果列表 */
.result-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 16px; }
.result-card {
  background: #fff; border-radius: 12px; padding: 14px 18px;
  border: 1px solid #eef0f4; cursor: pointer; transition: all 0.2s;
}
.result-card:hover { border-color: #c4c9ff; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.result-header { display: flex; align-items: center; gap: 12px; }
.result-icon { font-size: 24px; }
.result-meta { flex: 1; }
.result-meta strong { display: block; font-size: 14px; color: #1a1a2e; }
.result-type { font-size: 12px; color: #909399; }
.result-actions { flex-shrink: 0; }

/* 预览卡片 */
.preview-card {
  background: #fff; border-radius: 16px; padding: 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
  max-height: 600px; overflow-y: auto;
}
.preview-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 16px; padding-bottom: 12px;
  border-bottom: 1px solid #f2f3f7;
  font-weight: 600; color: #1a1a2e;
}
.preview-body { font-size: 14px; line-height: 1.8; color: #4a4f5e; }

/* 内容样式 */
.markdown-body { word-break: break-word; }
.markdown-body :deep(h1) { font-size: 22px; margin: 16px 0 10px; color: #1a1a2e; }
.markdown-body :deep(h2) { font-size: 19px; margin: 14px 0 8px; color: #1a1a2e; }
.markdown-body :deep(h3) { font-size: 16px; margin: 12px 0 6px; }
.markdown-body :deep(h4) { font-size: 14px; margin: 10px 0 6px; }
.markdown-body :deep(code) {
  background: #f5f6fa; padding: 2px 6px; border-radius: 4px;
  font-size: 13px; font-family: 'Consolas', 'Monaco', monospace;
}
.markdown-body :deep(pre.code-block) {
  background: #1e1e2e; color: #cdd6f4; padding: 16px 20px;
  border-radius: 10px; overflow-x: auto; font-size: 13px;
  line-height: 1.6;
}
.markdown-body :deep(ul) { padding-left: 20px; }
.markdown-body :deep(li) { margin: 4px 0; }

.code-block {
  background: #1e1e2e; color: #cdd6f4; padding: 16px 20px;
  border-radius: 10px; overflow-x: auto; font-size: 13px;
  font-family: 'Consolas', 'Monaco', monospace;
  line-height: 1.6; white-space: pre-wrap; word-break: break-all;
}

.html-frame { border-radius: 10px; overflow: hidden; border: 1px solid #eef0f4; }
.html-iframe { width: 100%; height: 500px; border: none; }

.text-preview { white-space: pre-wrap; word-break: break-word; }

@media (max-width: 900px) {
  .resource-grid { grid-template-columns: repeat(4, 1fr); }
}
@media (max-width: 600px) {
  .resource-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>