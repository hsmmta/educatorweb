<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <h1>📊 个人中心</h1>
        <p>6维学习画像可视化 · 学习效果评估 · 历史记录</p>
      </div>
      <el-button plain :icon="Edit" @click="$router.push('/profile/edit')">编辑资料</el-button>
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
            <span class="p-stat-num">0</span>
            <span class="p-stat-label">学习天数</span>
          </div>
          <div class="p-stat">
            <span class="p-stat-num">0</span>
            <span class="p-stat-label">生成资源</span>
          </div>
          <div class="p-stat">
            <span class="p-stat-num">0</span>
            <span class="p-stat-label">练习题目</span>
          </div>
        </div>
      </div>
    </section>

    <div class="two-col">
      <!-- 6维学习画像 -->
      <section class="section">
        <h3>🧠 6维学习画像</h3>
        <div class="dimension-card">
          <div v-for="dim in dimensions" :key="dim.key" class="dim-item">
            <div class="dim-header">
              <span class="dim-icon">{{ dim.icon }}</span>
              <span class="dim-label">{{ dim.label }}</span>
              <span class="dim-value">{{ dim.value || '待构建' }}</span>
            </div>
            <el-progress
              :percentage="dim.confidence || 0"
              :color="dim.color"
              :stroke-width="6"
            >
              <span class="dim-confidence">{{ dim.confidence || 0 }}% 置信度</span>
            </el-progress>
          </div>
        </div>
      </section>

      <!-- 学习效果评估 -->
      <section class="section">
        <h3>📈 学习效果评估</h3>
        <div class="assessment-card">
          <div class="assess-overview">
            <div class="assess-score">
              <span class="score-num">--</span>
              <span class="score-label">综合评分</span>
            </div>
            <div class="assess-detail">
              <div class="assess-row">
                <span>知识掌握</span>
                <el-progress :percentage="0" :stroke-width="8" :show-text="false" />
              </div>
              <div class="assess-row">
                <span>练习正确率</span>
                <el-progress :percentage="0" :stroke-width="8" :show-text="false" color="#67c23a" />
              </div>
              <div class="assess-row">
                <span>学习投入度</span>
                <el-progress :percentage="0" :stroke-width="8" :show-text="false" color="#e6a23c" />
              </div>
              <div class="assess-row">
                <span>资源利用率</span>
                <el-progress :percentage="0" :stroke-width="8" :show-text="false" color="#f56c6c" />
              </div>
            </div>
          </div>
          <div class="assess-tip">
            💡 完成首次学习后，系统将自动生成绩效评估报告
          </div>
        </div>
      </section>
    </div>

    <!-- 学习历史 -->
    <section class="section">
      <h3>📋 学习记录</h3>
      <div class="history-card">
        <el-empty description="还没有学习记录" :image-size="80">
          <el-button type="primary" @click="$router.push('/learning')">去生成资源</el-button>
        </el-empty>
      </div>
    </section>
  </div>
</template>

<script setup>
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
  const info = localStorage.getItem('userInfo')
  if (info) {
    try { userInfo.value = JSON.parse(info) } catch { userInfo.value = {} }
  }
  await fetchProfile()
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

.section { margin-top: 24px; }
.section h3 { font-size: 18px; font-weight: 600; color: #1a1a2e; margin: 0 0 14px; }

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
.dim-confidence { font-size: 11px; color: #909399; }

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

@media (max-width: 800px) {
  .two-col { grid-template-columns: 1fr; }
  .profile-card { flex-direction: column; text-align: center; }
  .assess-overview { flex-direction: column; }
}
</style>
