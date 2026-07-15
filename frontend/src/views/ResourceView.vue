<template>
  <div class="rv-container">
    <!-- Loading -->
    <div v-if="loading" class="rv-loading">
      <el-icon class="rv-spin"><Loading /></el-icon>
      <p>加载中...</p>
    </div>

    <!-- Generating -->
    <div v-else-if="resource.status === 'GENERATING'" class="rv-loading">
      <div class="generating-pulse" />
      <p>资源正在生成中，请稍候...</p>
      <p class="rv-hint">{{ resource.title }}</p>
      <el-button @click="pollStatus" :loading="polling">刷新状态</el-button>
    </div>

    <!-- Failed -->
    <div v-else-if="resource.status === 'FAILED'" class="rv-error">
      <span class="rv-error-icon">⚠️</span>
      <h3>资源生成失败</h3>
      <p>{{ resource.errorMsg || '未知错误' }}</p>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <!-- Ready: render by type -->
    <template v-else-if="resource.status === 'READY'">
      <!-- DOC: Markdown -->
      <div v-if="resource.resourceType === 'DOC'" class="rv-doc">
        <div class="rv-header">
          <h2>📄 {{ resource.title }}</h2>
          <span class="rv-topic">话题: {{ resource.topic }}</span>
        </div>
        <div class="markdown-body" v-html="renderedHtml" />
      </div>

      <!-- QUIZ: interactive -->
      <div v-else-if="resource.resourceType === 'QUIZ'" class="rv-quiz">
        <div class="rv-header">
          <h2>📝 {{ resource.title }}</h2>
          <span class="rv-topic">话题: {{ resource.topic }}</span>
        </div>
        <div class="quiz-toolbar">
          <el-button size="small" @click="showAnswers = !showAnswers">
            {{ showAnswers ? '隐藏解析' : '显示全部解析' }}
          </el-button>
        </div>
        <div v-if="quizData">
          <div v-for="(q, i) in quizData.questions" :key="i" class="quiz-item">
            <div class="quiz-q">
              <strong>{{ i + 1 }}. {{ q.question || q.stem }}</strong>
              <el-tag v-if="q.type === 'MC' || q.type === 'multiple_choice'" size="small" type="info">单选</el-tag>
              <el-tag v-else-if="q.type === 'TF' || q.type === 'true_false'" size="small" type="warning">判断</el-tag>
            </div>
            <div v-if="q.options" class="quiz-options">
              <div v-for="(opt, oi) in q.options" :key="oi" :class="['quiz-opt', {
                correct: showAnswers && (oi === q.correctIndex || opt === q.answer),
                wrong: showAnswers && selectedAnswers[i] === oi && oi !== q.correctIndex && opt !== q.answer
              }]">
                <label>
                  <input v-model="selectedAnswers[i]" type="radio" :value="oi" :name="'q' + i" />
                  {{ String.fromCharCode(65 + oi) }}. {{ typeof opt === 'string' ? opt : (opt.text || JSON.stringify(opt)) }}
                </label>
              </div>
            </div>
            <div v-if="showAnswers && (q.explanation || q.answerExplain)" class="quiz-explain">
              💡 {{ q.explanation || q.answerExplain }}
            </div>
          </div>
        </div>
        <div v-else class="rv-fallback">
          <pre>{{ resource.content }}</pre>
        </div>
      </div>

      <!-- MINDMAP: Mermaid -->
      <div v-else-if="resource.resourceType === 'MINDMAP'" class="rv-mindmap">
        <div class="rv-header">
          <h2>🧩 {{ resource.title }}</h2>
          <span class="rv-topic">话题: {{ resource.topic }}</span>
        </div>
        <div v-if="mindmapSvg" class="mindmap-svg" v-html="mindmapSvg" />
        <div v-else class="rv-fallback">
          <pre>{{ resource.content }}</pre>
        </div>
      </div>

      <!-- CODE: syntax highlight + sandbox -->
      <div v-else-if="resource.resourceType === 'CODE'" class="rv-code">
        <div class="rv-header">
          <h2>💻 {{ resource.title }}</h2>
          <span class="rv-topic">话题: {{ resource.topic }}</span>
        </div>
        <div class="code-block-container">
          <div class="code-bar">
            <span>Python</span>
            <el-button size="small" @click="copyCode">复制</el-button>
          </div>
          <pre class="code-pre"><code>{{ extractCode(resource.content) }}</code></pre>
        </div>
      </div>

      <!-- HTML: iframe -->
      <div v-else-if="resource.resourceType === 'HTML'" class="rv-html">
        <div class="rv-header">
          <h2>🌐 {{ resource.title }}</h2>
          <span class="rv-topic">话题: {{ resource.topic }}</span>
        </div>
        <iframe :srcdoc="resource.content" sandbox="allow-scripts allow-same-origin" class="html-frame" />
      </div>

      <!-- PPT / VIDEO: download -->
      <div v-else-if="resource.resourceType === 'PPT' || resource.resourceType === 'VIDEO'" class="rv-file">
        <div class="rv-header">
          <h2>{{ resource.resourceType === 'PPT' ? '📊' : '🎬' }} {{ resource.title }}</h2>
          <span class="rv-topic">话题: {{ resource.topic }}</span>
        </div>
        <p>此资源为二进制文件，已保存至本地。</p>
        <p class="rv-filepath">{{ resource.filePath }}</p>
      </div>

      <!-- Fallback: raw content -->
      <div v-else class="rv-fallback">
        <div class="rv-header">
          <h2>{{ resource.title }}</h2>
          <span class="rv-topic">话题: {{ resource.topic }}</span>
        </div>
        <pre>{{ resource.content }}</pre>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { marked } from 'marked'
import katex from 'katex'
import 'katex/dist/katex.min.css'
import mermaid from 'mermaid'
import { getResourceApi, checkResourceStatusApi } from '../api/index.js'

mermaid.initialize({ startOnLoad: false, theme: 'default' })

const route = useRoute()
const id = computed(() => route.params.id)

const loading = ref(true)
const polling = ref(false)
const resource = ref({ status: 'GENERATING' })
const showAnswers = ref(false)
const selectedAnswers = ref({})
const mindmapSvg = ref('')

// ---- rendered HTML for DOC type ----
const renderedHtml = computed(() => {
  if (!resource.value.content) return ''
  try {
    return marked(resource.value.content, { breaks: true })
  } catch {
    return resource.value.content
  }
})

// ---- quiz data parser ----
const quizData = computed(() => {
  if (resource.value.resourceType !== 'QUIZ' || !resource.value.content) return null
  try {
    return JSON.parse(resource.value.content)
  } catch {
    return null
  }
})

// ---- mindmap rendering ----
watch(() => resource.value.content, async (val) => {
  if (resource.value.resourceType !== 'MINDMAP' || !val) return
  try {
    const { svg } = await mermaid.render('mindmap-svg', val)
    mindmapSvg.value = svg
  } catch {
    mindmapSvg.value = ''
  }
})

// ---- init ----
onMounted(async () => {
  await loadResource()
})

async function loadResource() {
  loading.value = true
  try {
    const res = await getResourceApi(id.value)
    resource.value = res.data?.data || { status: 'FAILED', errorMsg: 'Empty response' }
  } catch (e) {
    resource.value = { status: 'FAILED', errorMsg: e.response?.data?.message || e.message }
  } finally {
    loading.value = false
  }
}

async function pollStatus() {
  polling.value = true
  try {
    const res = await checkResourceStatusApi(id.value)
    const data = res.data?.data
    if (data) {
      resource.value.status = data.status
      if (data.status === 'READY') {
        await loadResource()
      }
    }
  } catch (e) {
    ElMessage.error('状态检查失败')
  } finally {
    polling.value = false
  }
}

// ---- code helpers ----
function extractCode(content) {
  if (!content) return ''
  // Try extracting from markdown code block
  const m = content.match(/```\w*\n([\s\S]*?)```/)
  return m ? m[1] : content
}

function copyCode() {
  const code = extractCode(resource.value.content)
  navigator.clipboard.writeText(code).then(() => ElMessage.success('已复制'))
    .catch(() => ElMessage.error('复制失败'))
}
</script>

<style scoped>
.rv-container {
  max-width: 960px;
  margin: 0 auto;
  padding: 32px 24px 60px;
}

.rv-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: #667eea;
}
.rv-spin { font-size: 48px; animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

.generating-pulse {
  width: 64px; height: 64px; border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  animation: pulse 1.5s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { transform: scale(1); opacity: 0.6; }
  50% { transform: scale(1.15); opacity: 1; }
}
.rv-hint { font-size: 13px; color: #909399; margin-top: 4px; }

.rv-error {
  display: flex; flex-direction: column; align-items: center;
  justify-content: center; min-height: 400px;
}
.rv-error-icon { font-size: 48px; }
.rv-error h3 { color: #e6a23c; margin: 12px 0 4px; }
.rv-error p { color: #909399; font-size: 13px; }

/* header */
.rv-header {
  display: flex; justify-content: space-between; align-items: baseline;
  margin-bottom: 24px; padding-bottom: 16px;
  border-bottom: 2px solid #f0f2f5;
}
.rv-header h2 { margin: 0; font-size: 22px; color: #1a1a2e; }
.rv-topic { font-size: 13px; color: #909399; }

/* DOC */
.rv-doc { background: #fff; border-radius: 16px; padding: 32px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.markdown-body { line-height: 1.8; color: #303133; }

/* QUIZ */
.rv-quiz { background: #fff; border-radius: 16px; padding: 32px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.quiz-toolbar { margin-bottom: 16px; }
.quiz-item {
  border: 1px solid #f0f2f5; border-radius: 12px;
  padding: 18px; margin-bottom: 14px;
}
.quiz-q { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.quiz-options { display: flex; flex-direction: column; gap: 6px; }
.quiz-opt {
  padding: 8px 12px; border-radius: 8px; border: 1px solid #e8e8e8;
  transition: all 0.15s;
}
.quiz-opt label { display: flex; align-items: center; gap: 6px; cursor: pointer; }
.quiz-opt.correct { background: #f0f9eb; border-color: #67c23a; }
.quiz-opt.wrong { background: #fef0f0; border-color: #f56c6c; }
.quiz-explain {
  margin-top: 10px; padding: 10px 14px;
  background: #fafbff; border-radius: 8px;
  font-size: 13px; color: #667eea;
}

/* MINDMAP */
.rv-mindmap { background: #fff; border-radius: 16px; padding: 32px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.mindmap-svg { text-align: center; overflow-x: auto; }

/* CODE */
.rv-code { background: #fff; border-radius: 16px; padding: 32px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.code-block-container {
  border: 1px solid #e8e8e8; border-radius: 10px; overflow: hidden;
}
.code-bar {
  display: flex; justify-content: space-between; align-items: center;
  padding: 8px 16px; background: #f5f7fa;
  font-size: 13px; color: #909399;
}
.code-pre {
  padding: 16px; margin: 0; overflow-x: auto;
  background: #1e1e2e; color: #cdd6f4;
  font-family: 'Fira Code', monospace; font-size: 14px; line-height: 1.7;
  max-height: 600px; overflow-y: auto;
}

/* HTML */
.rv-html { background: #fff; border-radius: 16px; padding: 32px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.html-frame { width: 100%; min-height: 500px; border: 1px solid #e8e8e8; border-radius: 10px; }

/* FILE */
.rv-file { background: #fff; border-radius: 16px; padding: 32px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.rv-filepath { font-family: monospace; font-size: 12px; color: #909399; word-break: break-all; }

/* FALLBACK */
.rv-fallback { background: #fff; border-radius: 16px; padding: 32px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.rv-fallback pre { white-space: pre-wrap; font-size: 13px; color: #303133; }
</style>
