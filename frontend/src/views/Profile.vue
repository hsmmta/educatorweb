<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <h1>📊 个人中心</h1>
        <p>6维学习画像可视化 · 学习效果评估 · 历史记录</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" :icon="ChatDotRound" @click="$router.push('/profile/chat')">
          构建/更新画像
        </el-button>
        <el-button plain :icon="Edit" @click="$router.push('/profile/edit')">编辑资料</el-button>
      </div>
    </div>

    <!-- 基本信息 -->
    <section class="section">
      <div class="profile-card">
        <el-avatar :size="72" :icon="UserFilled" />
        <div class="profile-info">
          <h2>{{ userInfo?.nickname || '同学' }}</h2>
          <span class="profile-meta">{{ userInfo?.phone || '' }}</span>
          <span class="profile-meta">{{ userInfo?.email || '' }}</span>
        </div>
        <div class="profile-stats">
          <div class="p-stat">
            <span class="p-stat-num">{{ stats.learningDays }}</span>
            <span class="p-stat-label">学习天数</span>
          </div>
          <div class="p-stat">
            <span class="p-stat-num">{{ stats.resourceCount }}</span>
            <span class="p-stat-label">生成资源</span>
          </div>
          <div class="p-stat">
            <span class="p-stat-num">{{ stats.quizCount }}</span>
            <span class="p-stat-label">练习题目</span>
          </div>
        </div>
      </div>
    </section>

    <div class="two-col">
      <!-- 6维学习画像 -->
      <section class="section">
        <h3>
          🧠 6维学习画像
          <span v-if="!profileExists" class="section-badge">待构建</span>
          <span v-else class="section-badge ready">已构建</span>
        </h3>
        <div class="dimension-card">
          <div v-for="dim in dimensions" :key="dim.key" class="dim-item">
            <div class="dim-header">
              <span class="dim-icon">{{ dim.icon }}</span>
              <span class="dim-label">{{ dim.label }}</span>
              <span :class="['dim-value', { placeholder: !dim.value }]">
                {{ dim.value || '待构建' }}
              </span>
            </div>
            <el-progress
              :percentage="dim.confidence"
              :color="dim.color"
              :stroke-width="6"
            >
              <span class="dim-confidence">{{ dim.confidence }}% 置信度</span>
            </el-progress>
          </div>
        </div>
        <div v-if="!profileExists" class="card-tip">
          还没有学习画像？
          <el-link type="primary" @click="$router.push('/profile/chat')">通过对话构建 →</el-link>
        </div>
      </section>

      <!-- 学习效果评估 -->
      <section class="section">
        <h3>📈 学习效果评估</h3>
        <div class="assessment-card">
          <div class="assess-overview">
            <div class="assess-score">
              <span class="score-num">{{ assessment.compositeScore }}</span>
              <span class="score-label">综合评分</span>
            </div>
            <div class="assess-detail">
              <div v-for="item in assessment.details" :key="item.label" class="assess-row">
                <span>{{ item.label }}</span>
                <el-progress :percentage="item.value" :stroke-width="8" :show-text="false" :color="item.color" />
              </div>
            </div>
          </div>
          <div class="assess-tip">
            💡 {{ profileExists
              ? '在学习过程中评估将实时更新'
              : '完成首次学习后，系统将自动生成评估报告' }}
          </div>
        </div>
      </section>
    </div>

    <!-- 学习历史 -->
    <section class="section">
      <h3>📋 学习记录</h3>
      <div class="history-card">
        <el-empty v-if="stats.resourceCount === 0" description="还没有学习记录" :image-size="80">
          <el-button type="primary" @click="$router.push('/learning')">去生成资源</el-button>
        </el-empty>
        <div v-else class="history-placeholder">
          <p>共生成 <strong>{{ stats.resourceCount }}</strong> 项学习资源，完成 <strong>{{ stats.quizCount }}</strong> 道练习题</p>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Edit, UserFilled, ChatDotRound } from '@element-plus/icons-vue'
import request from '@/api/request'
import { getProfileSummaryApi } from '../api/index.js'

const userInfo = ref({})
const profileLoading = ref(false)
const profileExists = ref(false)

const stats = reactive({
  learningDays: 0,
  resourceCount: 0,
  quizCount: 0
})

const assessment = reactive({
  compositeScore: '--',
  details: [
    { label: '知识掌握', value: 0, color: '#667eea' },
    { label: '练习正确率', value: 0, color: '#67c23a' },
    { label: '学习投入度', value: 0, color: '#e6a23c' },
    { label: '资源利用率', value: 0, color: '#f56c6c' }
  ]
})

const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.studentId || ''
  } catch { return '' }
}

onMounted(async () => {
  const info = localStorage.getItem('userInfo')
  if (info) {
    try { userInfo.value = JSON.parse(info) } catch { userInfo.value = {} }
  }
  await fetchProfile()
  // Load profile summary from feature/patch2 (table-based profile update)
  try {
    const summary = await getProfileSummaryApi(getStudentId())
    profileExists.value = summary?.data?.exists || false
    if (summary?.data) {
      stats.learningDays = summary.data.learningDays || 0
      stats.resourceCount = summary.data.resourceCount || 0
      stats.quizCount = summary.data.quizCount || 0
      if (summary.data.compositeScore) assessment.compositeScore = summary.data.compositeScore
      if (summary.data.details) assessment.details = summary.data.details
    }
  } catch { profileExists.value = false }
})

const fetchProfile = async () => {
  const studentId = getStudentId()
  if (!studentId) return
  profileLoading.value = true
  try {
    const res = await request.get(`/profile/${studentId}`)
    const p = res.data
    dimensions.value[0].value = p.knowledgeBaseLevel || ''
    dimensions.value[0].confidence = parseConfidence(p.knowledgeBaseConfidence)
    dimensions.value[1].value = p.cognitiveStyleType || ''
    dimensions.value[1].confidence = parseConfidence(p.cognitiveStyleConfidence)
    dimensions.value[2].value = (p.errorPatternTags || []).join('、') || ''
    dimensions.value[2].confidence = parseConfidence(p.errorPatternConfidence)
    dimensions.value[3].value = p.learningPaceType || ''
    dimensions.value[3].confidence = parseConfidence(p.learningPaceConfidence)
    dimensions.value[4].value = p.contentPreferenceType || ''
    dimensions.value[4].confidence = parseConfidence(p.contentPreferenceRatio ? 50 : 0)
    dimensions.value[5].value = p.goalOrientationType || ''
    dimensions.value[5].confidence = parseConfidence(p.goalOrientationConfidence)
  } catch (e) {
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

</script>

<style scoped>
.page-container { max-width: 1000px; margin: 0 auto; padding: 32px 24px 60px; }

.page-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  background: #fff; padding: 28px 32px; border-radius: 20px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.header-left h1 { font-size: 26px; font-weight: 700; color: #1a1a2e; margin: 0 0 6px; }
.header-left p { font-size: 14px; color: #8890a0; margin: 0; }
.header-actions { display: flex; gap: 10px; flex-shrink: 0; }

.section { margin-top: 24px; }
.section h3 { font-size: 18px; font-weight: 600; color: #1a1a2e; margin: 0 0 14px; display: flex; align-items: center; gap: 10px; }
.section-badge { font-size: 11px; padding: 2px 10px; border-radius: 10px; background: #f2f3f7; color: #909399; font-weight: 400; }
.section-badge.ready { background: #eef0ff; color: #667eea; }

/* 个人信息卡片 */
.profile-card {
  display: flex; align-items: center; gap: 20px;
  background: #fff; padding: 28px 32px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
}
.profile-info { flex: 1; }
.profile-info h2 { font-size: 22px; font-weight: 700; color: #1a1a2e; margin: 0 0 6px; }
.profile-meta { display: inline-block; font-size: 13px; color: #909399; margin-right: 16px; }
.profile-stats { display: flex; gap: 24px; }
.p-stat { text-align: center; }
.p-stat-num { display: block; font-size: 24px; font-weight: 700; color: #667eea; }
.p-stat-label { font-size: 12px; color: #909399; }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }

/* 6维画像 */
.dimension-card {
  background: #fff; padding: 20px 24px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
  display: flex; flex-direction: column; gap: 18px;
}
.dim-header { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.dim-icon { font-size: 18px; }
.dim-label { font-size: 14px; font-weight: 600; color: #1a1a2e; }
.dim-value { margin-left: auto; font-size: 13px; color: #667eea; font-weight: 500; }
.dim-value.placeholder { color: #c0c4cc; }
.dim-confidence { font-size: 11px; color: #909399; }
.card-tip { text-align: center; font-size: 13px; color: #909399; margin-top: 4px; }

/* 评估 */
.assessment-card {
  background: #fff; padding: 24px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
}
.assess-overview { display: flex; gap: 24px; align-items: center; }
.assess-score {
  display: flex; flex-direction: column; align-items: center;
  padding: 16px 24px; background: #f8f7ff; border-radius: 14px;
}
.score-num { font-size: 36px; font-weight: 800; color: #667eea; }
.score-label { font-size: 12px; color: #909399; margin-top: 4px; }
.assess-detail { flex: 1; display: flex; flex-direction: column; gap: 12px; }
.assess-row { display: flex; align-items: center; gap: 10px; }
.assess-row span { font-size: 13px; color: #4a4f5e; width: 80px; flex-shrink: 0; }
.assess-row .el-progress { flex: 1; }
.assess-tip { margin-top: 16px; font-size: 13px; color: #909399; text-align: center; }

/* 历史 */
.history-card {
  background: #fff; padding: 32px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
}
.history-placeholder { text-align: center; color: #8890a0; font-size: 14px; }

@media (max-width: 800px) {
  .two-col { grid-template-columns: 1fr; }
  .profile-card { flex-direction: column; text-align: center; }
  .assess-overview { flex-direction: column; }
  .page-header { flex-direction: column; gap: 12px; }
}
</style>