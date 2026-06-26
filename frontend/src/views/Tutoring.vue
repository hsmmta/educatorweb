<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <h1>🎓 智能辅导</h1>
        <p>三级检索链路：私有智库 → 知识图谱 → AI大模型，确保答案准确可靠</p>
      </div>
    </div>

    <!-- 检索链路可视化 -->
    <div class="retrieval-chain">
      <div :class="['chain-node', { active: currentActiveStep === 'L1' }]">
        <div class="node-icon">📚</div>
        <div class="node-label">私有智库</div>
        <div class="node-badge">优先</div>
      </div>
      <div class="chain-line"></div>
      <div :class="['chain-node', { active: currentActiveStep === 'L2' }]">
        <div class="node-icon">🧩</div>
        <div class="node-label">知识图谱</div>
        <div class="node-badge">次选</div>
      </div>
      <div class="chain-line"></div>
      <div :class="['chain-node', { active: currentActiveStep === 'L3' }]">
        <div class="node-icon">🤖</div>
        <div class="node-label">AI大模型</div>
        <div class="node-badge">兜底</div>
      </div>
    </div>

    <!-- 对话区域 -->
    <div class="chat-area">
      <!-- 问题输入 -->
      <div class="question-input">
        <el-input
          v-model="question"
          type="textarea"
          :rows="3"
          placeholder="输入你的问题，AI 将优先从你的私人智库中检索答案。例如：请帮我总结《机器学习》第三章的核心概念..."
          size="large"
          @keyup.enter.ctrl="askQuestion"
        />
        <div class="input-actions">
          <div class="input-tips">
            <el-tag size="small" type="info">Ctrl + Enter 发送</el-tag>
            <el-tag size="small" v-if="materialCount > 0" type="success">私有智库: {{ materialCount }} 份资料</el-tag>
            <el-tag size="small" v-else type="warning">私有智库为空，将直接检索知识图谱</el-tag>
          </div>
          <el-button
            type="primary"
            :icon="Promotion"
            :loading="asking"
            @click="askQuestion"
            :disabled="!question.trim()"
          >
            提问
          </el-button>
        </div>
      </div>

      <!-- 检索过程 -->
      <div v-if="retrievalSteps.length > 0" class="retrieval-log">
        <div v-for="step in retrievalSteps" :key="step.id" :class="['log-item', `log-${step.status}`]">
          <el-icon v-if="step.status === 'done'" class="log-icon"><CircleCheckFilled /></el-icon>
          <el-icon v-else-if="step.status === 'loading'" class="log-icon loading"><Loading /></el-icon>
          <el-icon v-else-if="step.status === 'error'" class="log-icon error-icon"><WarningFilled /></el-icon>
          <span class="log-text">{{ step.text }}</span>
          <span class="log-source" v-if="step.source">{{ step.source }}</span>
        </div>
      </div>

      <!-- 回答区域 -->
      <div v-if="answer" class="answer-card">
        <div class="answer-header">
          <span class="answer-source">
            答案来源：
            <el-tag :type="answerSourceType" size="small">{{ answerSourceLabel }}</el-tag>
          </span>
        </div>
        <div class="answer-body" v-html="renderedAnswer"></div>
        <div class="answer-footer">
          <el-button text :icon="DocumentCopy" @click="copyAnswer">复制</el-button>
          <el-button text :icon="RefreshRight" @click="askQuestion">重新生成</el-button>
        </div>
        <div v-if="answerSources.length > 0" class="answer-refs">
          <h4>📎 参考来源</h4>
          <div v-for="(src, i) in answerSources" :key="i" class="ref-item">
            <span class="ref-idx">[{{ i + 1 }}]</span>
            <span class="ref-text">{{ (src.text || '').substring(0, 120) }}...</span>
            <span class="ref-source">{{ src.source }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, CircleCheckFilled, Loading, WarningFilled, DocumentCopy, RefreshRight, CircleCheck } from '@element-plus/icons-vue'
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
const currentActiveStep = ref('L1')

/** Get studentId from login info stored in localStorage */
const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.studentId || 'anonymous'
  } catch { return 'anonymous' }
}

const renderedAnswer = ref('')

function renderMarkdownSimple(content) {
  if (!content) return ''
  let html = content
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    // 代码块
    .replace(/```(\w*)\n([\s\S]*?)```/g, '<pre class="answer-code"><code>$2</code></pre>')
    .replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>')
    // 标题
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    // 加粗/斜体
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    // LaTeX 公式 (简单保护)
    .replace(/\$\$(.+?)\$\$/g, '<span class="latex-block">$$$1$$</span>')
    .replace(/\$(.+?)\$/g, '<span class="latex-inline">$$$1$$</span>')
    // 列表
    .replace(/^- (.+)$/gm, '<li>$1</li>')
    .replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>')
    .replace(/^\d+\.\s(.+)$/gm, '<li>$1</li>')
    // 换行
    .replace(/\n\n/g, '</p><p>')
    .replace(/\n/g, '<br>')
  return '<p>' + html + '</p>'
}

const askQuestion = async () => {
  if (!question.value.trim()) return
  asking.value = true
  answer.value = ''
  answerSources.value = []
  retrievalSteps.value = [
    { id: 1, text: '正在检索知识库并生成回答...', status: 'loading', source: '' }
  ]

  // 初始化检索状态
  currentActiveStep.value = 'L1'

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
    renderedAnswer.value = renderMarkdownSimple(data.answer || '')

    // Show sources if available
    answerSources.value = data.sources || []
    if (answerSources.value.length > 0) {
      answerSourceLabel.value = `RAG · ${answerSources.value.length} 条参考`
      answerSourceType.value = 'success'
    } else {
      answerSourceLabel.value = 'AI 回答'
      answerSourceType.value = 'info'
    }

    // Show retrieval steps with level highlighting (from feature/patch2)
    if (data.retrievalSteps) {
      retrievalSteps.value = data.retrievalSteps.map(s => ({
        id: s.id, text: s.text, status: s.status, source: s.source
      }))
      const lastDone = [...retrievalSteps.value].reverse().find(s => s.status === 'done')
      if (lastDone) {
        if (lastDone.id === 'L3_LLM') currentActiveStep.value = 'L3'
        else if (lastDone.id === 'L2_KG') currentActiveStep.value = 'L2'
        else currentActiveStep.value = 'L1'
      }
    } else {
      retrievalSteps.value = [
        { id: 1, text: '知识库检索完成', status: 'done', source: `${answerSources.value.length} 条参考文档` }
      ]
    }
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
const copyAnswer = () => {
  if (answer.value) {
    navigator.clipboard.writeText(answer.value.replace(/<[^>]*>/g, ''))
      .then(() => ElMessage.success('已复制到剪贴板'))
      .catch(() => ElMessage.warning('复制失败'))
  }
}
</script>

<style scoped>
.page-container { max-width: 900px; margin: 0 auto; padding: 32px 24px 60px; }

.page-header {
  background: #fff; padding: 28px 32px; border-radius: 20px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.header-left h1 { font-size: 26px; font-weight: 700; color: #1a1a2e; margin: 0 0 6px; }
.header-left p { font-size: 14px; color: #8890a0; margin: 0; }

/* 检索链路 */
.retrieval-chain {
  display: flex; align-items: center; justify-content: center; gap: 0;
  margin-top: 20px; padding: 20px 32px;
  background: #fff; border-radius: 14px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
}
.chain-node {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  position: relative; transition: all 0.3s;
}
.chain-node .node-icon { font-size: 28px; }
.chain-node .node-label { font-size: 13px; font-weight: 500; color: #4a4f5e; }
.chain-node .node-badge {
  font-size: 11px; padding: 1px 8px; border-radius: 10px;
  background: #f2f3f7; color: #909399;
}
.chain-node.active .node-badge { background: #eef0ff; color: #667eea; font-weight: 600; }
.chain-node.active .node-label { color: #667eea; font-weight: 600; }
.chain-line {
  width: 60px; height: 2px; background: linear-gradient(90deg, #dcdfe6, #c0c4cc);
  margin: 0 16px 20px;
}

/* 对话区域 */
.chat-area { margin-top: 24px; }
.question-input {
  background: #fff; padding: 24px; border-radius: 16px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.input-actions { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.input-tips { display: flex; gap: 8px; }

/* 检索日志 */
.retrieval-log {
  margin-top: 16px; padding: 16px 20px;
  background: #fafbfc; border-radius: 12px; border: 1px solid #eef0f4;
}
.log-item { display: flex; align-items: center; gap: 10px; padding: 6px 0; }
.log-icon { font-size: 16px; }
.log-done .log-icon { color: #67c23a; }
.log-loading .log-icon { color: #667eea; animation: spin 1s linear infinite; }
.log-error .error-icon { color: #f56c6c; }
.log-text { font-size: 13px; color: #4a4f5e; }
.log-source { margin-left: auto; font-size: 12px; color: #909399; }
.log-pending { opacity: 0.4; }

@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

/* 回答 */
.answer-card {
  margin-top: 20px; background: #fff; border-radius: 16px;
  padding: 24px; box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.answer-header { margin-bottom: 16px; }
.answer-source { font-size: 13px; color: #8890a0; }
.answer-body { font-size: 15px; line-height: 1.8; color: #1a1a2e; }
.answer-body :deep(h1) { font-size: 20px; margin: 16px 0 10px; color: #1a1a2e; }
.answer-body :deep(h2) { font-size: 18px; margin: 14px 0 8px; color: #1a1a2e; }
.answer-body :deep(h3) { font-size: 16px; margin: 12px 0 6px; }
.answer-body :deep(ul), .answer-body :deep(ol) { padding-left: 20px; }
.answer-body :deep(li) { margin: 6px 0; }
.answer-body :deep(.answer-code) {
  background: #1e1e2e; color: #cdd6f4; padding: 14px 18px;
  border-radius: 8px; overflow-x: auto; font-size: 13px;
  font-family: 'Consolas', 'Monaco', monospace;
  line-height: 1.6; margin: 10px 0;
}
.answer-body :deep(.inline-code) {
  background: #f5f6fa; padding: 2px 6px; border-radius: 4px;
  font-size: 13px; font-family: 'Consolas', 'Monaco', monospace;
}
.answer-body :deep(.latex-block), .answer-body :deep(.latex-inline) {
  color: #667eea; font-family: 'Georgia', serif;
}
.answer-footer {
  display: flex; gap: 8px; margin-top: 20px;
  padding-top: 16px; border-top: 1px solid #f2f3f7;
}
.answer-refs { margin-top: 16px; padding-top: 16px; border-top: 1px solid #f2f3f7; }
.answer-refs h4 { font-size: 13px; color: #8890a0; margin: 0 0 10px; font-weight: 500; }
.ref-item { display: flex; gap: 8px; font-size: 12px; color: #606266; margin-bottom: 6px; }
.ref-idx { color: #667eea; font-weight: 600; flex-shrink: 0; }
.ref-text { flex: 1; line-height: 1.5; }
.ref-source { color: #909399; flex-shrink: 0; }
</style>
