<template>
  <div class="wb-page">
    <!-- Header -->
    <div class="wb-header">
      <div class="wb-header-left">
        <div class="wb-header-icon">
          <span class="wb-icon-glyph">📝</span>
        </div>
        <div>
          <h1>错题集</h1>
          <p>{{ wrongAnswers.length }} 道错题 · 温故而知新</p>
        </div>
      </div>
      <div class="wb-header-right">
        <el-button text @click="$router.push('/profile')">← 返回个人中心</el-button>
        <el-button v-if="wrongAnswers.length" size="small" type="danger" text @click="clearAll">
          清空全部
        </el-button>
      </div>
    </div>

    <!-- Empty state -->
    <div v-if="!wrongAnswers.length" class="wb-empty">
      <div class="wb-empty-art">
        <span class="wb-empty-glyph">✨</span>
      </div>
      <h2>还没有错题</h2>
      <p>完成练习后，答错的题目会自动收集到这里</p>
      <el-button type="primary" round @click="$router.push('/push')">去练习 →</el-button>
    </div>

    <!-- Card grid -->
    <div v-else class="wb-grid">
      <div
        v-for="(item, idx) in wrongAnswers"
        :key="item.id"
        class="wb-card"
        :style="{ animationDelay: idx * 0.04 + 's' }"
      >
        <!-- Card front: question -->
        <div class="wb-card-inner">
          <div class="wb-card-index">{{ idx + 1 }}</div>
          <div class="wb-card-body">
            <p class="wb-question">{{ item.question }}</p>

            <!-- Options -->
            <div class="wb-options" v-if="item.options && item.options.length">
              <span
                v-for="(opt, oi) in item.options" :key="oi"
                :class="[
                  'wb-opt',
                  { 'opt-mistake': showAnswer[item.id] && item.userAnswer === optLetter(opt) },
                  { 'opt-answer': showAnswer[item.id] && isCorrectOpt(item, opt) },
                  { 'redo-pick': redoPick[item.id] === optLetter(opt) },
                  { 'redo-ok': redoResult[item.id] === 'correct' && redoPick[item.id] === optLetter(opt) },
                  { 'redo-bad': redoResult[item.id] === 'incorrect' && redoPick[item.id] === optLetter(opt) }
                ]"
                @click="handleOptionClick(item, opt)"
              >
                <span class="wb-opt-letter">{{ optLetter(opt) }}</span>
                <span class="wb-opt-text">{{ stripLetter(opt) }}</span>
              </span>
            </div>

            <!-- Meta bar -->
            <div class="wb-meta">
              <span class="wb-meta-tag">{{ item.knowledgePoint || '未知知识点' }}</span>
              <span class="wb-meta-tag">{{ item.quizTitle || '' }}</span>
              <span v-if="redone[item.id]" class="wb-redone-badge">✓ 已重做</span>
            </div>

            <!-- Action bar -->
            <div class="wb-actions">
              <button
                :class="['wb-action-btn', { active: !showAnswer[item.id] }]"
                @click="toggleAnswer(item.id)"
              >
                {{ showAnswer[item.id] === false ? '👁 显示答案' : '🙈 重做此题' }}
              </button>
              <button
                v-if="showAnswer[item.id] === false"
                class="wb-action-btn primary"
                @click="resetRedo(item.id)"
              >
                🔄 重新选择
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Bottom stats -->
    <div v-if="wrongAnswers.length" class="wb-footer">
      <span>{{ redoneCount }} / {{ wrongAnswers.length }} 已重做</span>
      <el-progress
        :percentage="Math.round(redoneCount / wrongAnswers.length * 100)"
        :stroke-width="4"
        :show-text="false"
        color="#22c55e"
        style="width:120px"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/api/request'

const wrongAnswers = ref([])
const showAnswer = ref({})     // per-card answer visibility (default: show)
const redone = ref({})         // per-card redo completed flag (persisted to localStorage)
const redoPick = ref({})       // per-card current redo selection
const redoResult = ref({})     // per-card redo result

const redoneCount = computed(() => Object.values(redone.value).filter(Boolean).length)

const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.id || 'anonymous'
  } catch { return 'anonymous' }
}

// Persist redone state to localStorage
const redoneStorageKey = () => `wrongAnswerRedone_${getStudentId()}`

const loadRedoneFromStorage = () => {
  try {
    const stored = localStorage.getItem(redoneStorageKey())
    if (stored) redone.value = JSON.parse(stored)
  } catch { redone.value = {} }
}

const saveRedoneToStorage = () => {
  localStorage.setItem(redoneStorageKey(), JSON.stringify(redone.value))
}

const loadWrongAnswers = async () => {
  try {
    const res = await request.get('/quiz/wrong-answers/' + getStudentId())
    const list = (res.data?.data || []).slice(0, 50)
    wrongAnswers.value = list
    // Restore persisted redone state
    loadRedoneFromStorage()
    // Default: show answers
    list.forEach(item => { showAnswer.value[item.id] = true })
  } catch { wrongAnswers.value = [] }
}

const toggleAnswer = (id) => {
  showAnswer.value = { ...showAnswer.value, [id]: !showAnswer.value[id] }
}

const handleOptionClick = (item, opt) => {
  if (showAnswer.value[item.id] !== false) return
  const letter = optLetter(opt)
  if (!letter) return
  redoPick.value = { ...redoPick.value, [item.id]: letter }

  const correctAnswer = (item.correctAnswer || '').trim()
  // Match by letter (A/B/C/D) — works for multiple choice
  const letterMatch = letter.toUpperCase() === correctAnswer.toUpperCase()
  // Match by content — for 判断题 where correctAnswer is "正确"/"对" etc.
  const content = stripLetter(opt).trim()
  const contentMatch = content && correctAnswer &&
    (content === correctAnswer || content.includes(correctAnswer) || correctAnswer.includes(content))

  const correct = letterMatch || contentMatch
  redoResult.value = { ...redoResult.value, [item.id]: correct ? 'correct' : 'incorrect' }
  if (correct) {
    redone.value = { ...redone.value, [item.id]: true }
    saveRedoneToStorage()
  }
}

const resetRedo = (id) => {
  // Clear current pick/result but keep redone badge permanently
  redoPick.value = { ...redoPick.value, [id]: null }
  redoResult.value = { ...redoResult.value, [id]: null }
}

const clearAll = async () => {
  try {
    await ElMessageBox.confirm('确定要清空所有错题吗？', '确认', { type: 'warning' })
    await request.delete('/quiz/wrong-answers/' + getStudentId())
    wrongAnswers.value = []
    ElMessage.success('已清空')
  } catch { /* cancelled */ }
}

const optLetter = (opt) => {
  if (!opt) return ''
  const m = opt.match(/^([A-Z])[.)]/)
  return m ? m[1] : ''
}

const stripLetter = (opt) => {
  if (!opt) return opt
  return opt.replace(/^[A-Z][.)]\s*/, '')
}

const isCorrectOpt = (item, opt) => {
  return optLetter(opt).toUpperCase() === (item.correctAnswer || '').trim().toUpperCase()
}

onMounted(loadWrongAnswers)
</script>

<style scoped>
.wb-page {
  max-width: 900px; margin: 0 auto; padding: 32px 24px 60px;
  min-height: 100vh;
}

/* ---- header ---- */
.wb-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  margin-bottom: 32px;
}
.wb-header-left { display: flex; align-items: center; gap: 16px; }
.wb-header-icon {
  width: 52px; height: 52px; border-radius: 16px; display: flex;
  align-items: center; justify-content: center; flex-shrink: 0;
  background: linear-gradient(135deg, #fef3c7, #fde68a);
  box-shadow: 0 4px 12px rgba(234,179,8,0.15);
}
.wb-icon-glyph { font-size: 26px; line-height: 1; }
.wb-header h1 { font-size: 26px; font-weight: 800; color: #1a1a2e; margin: 0 0 2px; }
.wb-header p { font-size: 13px; color: #909399; margin: 0; }
.wb-header-right { display: flex; gap: 8px; align-items: center; flex-shrink: 0; }

/* ---- empty ---- */
.wb-empty {
  text-align: center; padding: 80px 20px;
}
.wb-empty-art {
  width: 80px; height: 80px; border-radius: 24px; margin: 0 auto 20px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #f0fdf4, #dcfce7);
}
.wb-empty-glyph { font-size: 36px; }
.wb-empty h2 { font-size: 18px; font-weight: 700; color: #1a1a2e; margin: 0 0 6px; }
.wb-empty p { font-size: 13px; color: #909399; margin: 0 0 20px; }

/* ---- grid ---- */
.wb-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 18px;
}
@media (max-width: 700px) { .wb-grid { grid-template-columns: 1fr; } }

.wb-card {
  background: #fff; border-radius: 18px; overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04), 0 4px 14px rgba(0,0,0,0.03);
  border: 1px solid #f0f2f5;
  animation: card-in 0.4s ease both;
  transition: all 0.2s;
}
.wb-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06), 0 6px 20px rgba(0,0,0,0.04); }
@keyframes card-in {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

.wb-card-inner { display: flex; gap: 14px; padding: 20px; }
.wb-card-index {
  width: 30px; height: 30px; border-radius: 10px; display: flex;
  align-items: center; justify-content: center; flex-shrink: 0;
  background: #fef3c7; color: #b45309; font-size: 13px; font-weight: 800;
  font-family: 'Georgia', 'Times New Roman', serif;
}
.wb-card-body { flex: 1; min-width: 0; }
.wb-question { font-size: 14px; font-weight: 600; color: #1a1a2e; line-height: 1.6; margin: 0 0 12px; }

/* ---- options ---- */
.wb-options { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.wb-opt {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 14px; border-radius: 10px; cursor: default;
  background: #f8f9fe; border: 1px solid #eef0f4;
  transition: all 0.12s; font-size: 13px;
}
.wb-opt-letter {
  width: 22px; height: 22px; border-radius: 6px; display: flex;
  align-items: center; justify-content: center; flex-shrink: 0;
  background: #eef0f4; color: #606266; font-size: 11px; font-weight: 700;
}
.wb-opt-text { flex: 1; color: #4a4f5e; }

.wb-opt.opt-mistake { background: #fef2f2; border-color: #fecaca; }
.wb-opt.opt-mistake .wb-opt-letter { background: #fecaca; color: #dc2626; }
.wb-opt.opt-answer { background: #f0fdf4; border-color: #bbf7d0; }
.wb-opt.opt-answer .wb-opt-letter { background: #bbf7d0; color: #15803d; }

/* redo states */
.wb-opt { cursor: default; }
.show-answer-false .wb-opt { cursor: pointer; }
.wb-opt.redo-pick { border-color: #667eea; background: #eef0ff; }
.wb-opt.redo-pick .wb-opt-letter { background: #667eea; color: #fff; }
.wb-opt.redo-ok { background: #f0fdf4; border-color: #86efac; }
.wb-opt.redo-ok .wb-opt-letter { background: #22c55e; color: #fff; }
.wb-opt.redo-bad { background: #fef2f2; border-color: #fca5a5; }
.wb-opt.redo-bad .wb-opt-letter { background: #ef4444; color: #fff; }

/* ---- meta ---- */
.wb-meta { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; align-items: center; }
.wb-meta-tag {
  font-size: 11px; padding: 2px 8px; border-radius: 6px;
  background: #f2f3f7; color: #909399;
}
.wb-redone-badge {
  font-size: 11px; color: #22c55e; font-weight: 600;
  padding: 2px 8px; border-radius: 6px; background: #f0fdf4;
}

/* ---- actions ---- */
.wb-actions { display: flex; gap: 8px; }
.wb-action-btn {
  font-size: 12px; padding: 6px 14px; border-radius: 8px; cursor: pointer;
  border: 1px solid #eef0f4; background: #fff; color: #606266;
  font-weight: 500; transition: all 0.15s;
}
.wb-action-btn:hover { background: #f8f9fe; border-color: #d0d5dd; }
.wb-action-btn.active { background: #fef2f2; border-color: #fecaca; color: #dc2626; }
.wb-action-btn.primary { background: #eef0ff; border-color: #c7d2fe; color: #4f46e5; }

/* ---- footer ---- */
.wb-footer {
  display: flex; align-items: center; gap: 12px; justify-content: center;
  margin-top: 32px; padding: 16px; border-radius: 14px;
  background: linear-gradient(135deg, rgba(34,197,94,0.04), rgba(34,197,94,0.01));
  font-size: 13px; color: #606266; font-weight: 500;
}
</style>
