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
          <span class="result-icon">📄</span>
          <div>
            <strong>{{ result.title }}</strong>
            <span class="result-meta">{{ result.type }} · {{ result.size }}</span>
          </div>
          <el-button type="primary" plain :icon="Download">下载</el-button>
        </div>
        <div class="result-preview" v-html="result.preview"></div>
      </div>
    </section>
  </div>
</template>

<script setup>
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
.result-header strong { display: block; font-size: 16px; color: #1a1a2e; }
.result-meta { font-size: 12px; color: #909399; }
.result-header .el-button { margin-left: auto; }
.result-preview { font-size: 14px; color: #4a4f5e; line-height: 1.7; }
.result-preview :deep(p) { margin: 6px 0; }

@media (max-width: 800px) {
  .resource-grid { grid-template-columns: repeat(4, 1fr); }
}
</style>
