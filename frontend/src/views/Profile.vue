<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <h1>📊 个人中心</h1>
        <p>6维学习画像 · 学习报告 · 历史记录</p>
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
            <el-progress :percentage="dim.confidence" :color="dim.color" :stroke-width="6">
              <span class="dim-confidence">{{ dim.confidence }}% 置信度</span>
            </el-progress>
          </div>
        </div>
        <div v-if="!profileExists" class="card-tip">
          还没有学习画像？<el-link type="primary" @click="$router.push('/profile/chat')">通过对话构建 →</el-link>
        </div>
      </section>

      <!-- 综合评分卡 -->
      <section class="section">
        <h3>📈 综合评分</h3>
        <div class="score-card">
          <div class="score-ring-wrap">
            <div class="score-ring">
              <svg viewBox="0 0 120 120" class="score-svg">
                <circle cx="60" cy="60" r="52" fill="none" stroke="#eef0f4" stroke-width="8" />
                <circle cx="60" cy="60" r="52" fill="none" stroke="url(#scoreGrad)" stroke-width="8"
                  stroke-linecap="round" :stroke-dasharray="ringDash" :stroke-dashoffset="ringOffset"
                  transform="rotate(-90 60 60)" style="transition: stroke-dashoffset 0.8s ease" />
                <defs>
                  <linearGradient id="scoreGrad" x1="0%" y1="0%" x2="100%" y2="0%">
                    <stop offset="0%" stop-color="#667eea" />
                    <stop offset="100%" stop-color="#764ba2" />
                  </linearGradient>
                </defs>
              </svg>
              <div class="score-inner">
                <span class="score-big">{{ assessment.compositeScore }}</span>
                <span class="score-unit">/100</span>
              </div>
            </div>
            <p class="score-desc">综合评分</p>
          </div>
          <div class="score-metrics">
            <div v-for="item in assessment.details" :key="item.label" class="metric-item">
              <div class="metric-head">
                <span class="metric-dot" :style="{ background: item.color }"></span>
                <span class="metric-label">{{ item.label }}</span>
                <span class="metric-val">{{ item.value }}%</span>
              </div>
              <el-progress :percentage="item.value" :stroke-width="6" :show-text="false" :color="item.color" />
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- ====== 学习报告仪表盘 ====== -->
    <section class="section" v-if="profileExists">
      <div class="report-header">
        <h3>📋 学习报告</h3>
        <span class="report-date">生成于 {{ reportDate }}</span>
        <el-button size="small" text @click="loadReport">刷新</el-button>
      </div>

      <!-- 无数据 -->
      <div v-if="stats.quizCount === 0" class="report-empty">
        <el-empty description="还没有学习记录，完成首次练习后将自动生成学习报告" :image-size="100">
          <el-button type="primary" @click="$router.push('/learning')">去学习 →</el-button>
        </el-empty>
      </div>

      <template v-else>
        <!-- Row 1: 综合评分 + 投入度数字 -->
        <div class="stats-row">
          <div class="stat-card composite">
            <span class="stat-card-num">{{ reportData.compositeScore || stats.compositeScore || '—' }}</span>
            <span class="stat-card-label">综合评分</span>
          </div>
          <div class="stat-card">
            <span class="stat-card-num">{{ reportData.learningInput?.activeDays || 0 }}</span>
            <span class="stat-card-label">活跃天数</span>
          </div>
          <div class="stat-card">
            <span class="stat-card-num">{{ reportData.learningInput?.resourceViews || 0 }}</span>
            <span class="stat-card-label">浏览资源</span>
          </div>
          <div class="stat-card">
            <span class="stat-card-num">{{ reportData.learningInput?.chatRounds || 0 }}</span>
            <span class="stat-card-label">AI 对话</span>
          </div>
          <div class="stat-card">
            <span class="stat-card-num">{{ reportData.learningInput?.quizTotal || 0 }}</span>
            <span class="stat-card-label">答题数</span>
          </div>
        </div>

        <!-- Row 2: 雷达图 + 折线图 -->
        <div class="chart-row">
          <div class="chart-box" v-if="(reportData.knowledgeRadar || []).length">
            <v-chart :option="radarOption" autoresize style="height:320px" />
          </div>
          <div class="chart-box" v-if="(reportData.growthTrend || []).length">
            <v-chart :option="growthOption" autoresize style="height:320px" />
          </div>
          <div v-if="!(reportData.knowledgeRadar || []).length && !(reportData.growthTrend || []).length"
               class="chart-box chart-empty">
            <p>完成练习后，这里将展示知识点掌握雷达图与成长趋势</p>
          </div>
        </div>

        <!-- Row 3: 学习进度 + 投入度柱状图 -->
        <div class="chart-row">
          <div class="chart-box" v-if="reportData.learningProgress?.totalNodes">
            <h4>📂 学习进度</h4>
            <el-progress
              :percentage="Math.round((reportData.learningProgress.completedNodes || 0) / (reportData.learningProgress.totalNodes || 1) * 100)"
              :stroke-width="16" color="#667eea" />
            <p class="progress-label">
              已完成 {{ reportData.learningProgress.completedNodes }} /
              共 {{ reportData.learningProgress.totalNodes }} 个知识点
            </p>
          </div>
          <div class="chart-box" v-if="(reportData.learningInput?.weeklyTrend || []).length">
            <v-chart :option="inputOption" autoresize style="height:280px" />
          </div>
        </div>

        <!-- Row 4: 薄弱环节标签云 -->
        <div class="tag-cloud" v-if="weakPoints.length">
          <h4>📉 薄弱环节</h4>
          <span v-for="wp in weakPoints" :key="wp.concept"
                :style="{ fontSize: 12 + (1 - wp.proficiency) * 12 + 'px',
                         color: wp.proficiency < 0.4 ? '#f56c6c' : wp.proficiency < 0.6 ? '#e6a23c' : '#909399' }"
                class="tag-cloud-item">{{ wp.concept }}</span>
        </div>

        <!-- 评分说明 (keep existing) -->
        <el-collapse class="score-explainer">
          <el-collapse-item title="📐 评分依据说明" name="1">
            <div class="explainer-content">
              <p><strong>综合评分 = 有效掌握度均值 × 0.6 + 置信度均值 × 0.4</strong></p>
              <table>
                <thead><tr><th>指标</th><th>计算方式</th><th>说明</th></tr></thead>
                <tbody>
                  <tr><td><strong>原始正确率</strong></td><td><code>correctQuestions / totalQuestions</code></td><td>基于答题记录统计</td></tr>
                  <tr><td><strong>有效掌握度</strong></td><td><code>原始正确率 × e<sup>(-天数 / 半衰期)</sup></code></td><td>艾宾浩斯遗忘衰减；半衰期 3~30 天</td></tr>
                  <tr><td><strong>置信度</strong></td><td><code>1 − e<sup>(−0.4 × 答题总数)</sup></code></td><td>仅基于答题数量；5 题 ≈ 86%</td></tr>
                </tbody>
              </table>
            </div>
          </el-collapse-item>
        </el-collapse>

        <!-- 学习建议 -->
        <h4>💡 学习建议</h4>
        <div class="tips-list">
          <div v-for="(tip, i) in learningTips" :key="i" :class="['tip-item', tip.level]">
            <span class="tip-icon">{{ tip.icon }}</span>
            <div class="tip-body">
              <strong>{{ tip.title }}</strong>
              <p>{{ tip.desc }}</p>
            </div>
          </div>
        </div>
      </template>
    </section>

    <!-- 未构建画像时的占位 -->
    <section class="section" v-if="!profileExists && stats.quizCount === 0">
      <div class="report-body markdown-body">
        <div class="report-empty">
          <el-empty description="构建学习画像并完成练习后，这里将展示完整的学习报告" :image-size="100">
            <el-button type="primary" @click="$router.push('/profile/chat')">构建画像 →</el-button>
            <el-button plain style="margin-left:8px" @click="$router.push('/learning')">去学习 →</el-button>
          </el-empty>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getProfileSummaryApi } from '../api/index.js'
import { ChatDotRound, Edit, UserFilled } from '@element-plus/icons-vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { RadarChart } from 'echarts/charts'
import { LineChart } from 'echarts/charts'
import { BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, RadarComponent } from 'echarts/components'
import { GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([CanvasRenderer, RadarChart, LineChart, BarChart,
     TitleComponent, TooltipComponent, LegendComponent, RadarComponent, GridComponent])

const userInfo = ref({})
const profileExists = ref(false)
const reportData = ref({
  compositeScore: 0,
  knowledgeRadar: [],
  learningProgress: { totalNodes: 0, completedNodes: 0, currentNode: '' },
  learningInput: { activeDays: 0, totalDurationMin: 0, resourceViews: 0, chatRounds: 0, quizTotal: 0, weeklyTrend: [] },
  growthTrend: []
})
const summaryText = ref('')
const weakPoints = ref([])
const strongPoints = ref([])
const evenPoints = ref([])

const stats = reactive({
  learningDays: 0,
  resourceCount: 0,
  quizCount: 0,
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

const dimensions = ref([
  { key: 'knowledge',   icon: '📖', label: '知识基础',   value: '', color: '#667eea', confidence: 0 },
  { key: 'cognitive',   icon: '🧩', label: '认知风格',   value: '', color: '#3b82f6', confidence: 0 },
  { key: 'error',       icon: '⚠️', label: '易错偏好',   value: '', color: '#f97316', confidence: 0 },
  { key: 'pace',        icon: '🏃', label: '学习步调',   value: '', color: '#22c55e', confidence: 0 },
  { key: 'preference',  icon: '🎯', label: '内容偏好',   value: '', color: '#ec4899', confidence: 0 },
  { key: 'goal',        icon: '🏆', label: '目标导向',   value: '', color: '#a855f7', confidence: 0 }
])

const reportDate = computed(() => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`
})

const ringDash = computed(() => {
  const score = typeof assessment.compositeScore === 'number' ? assessment.compositeScore : 0
  return `${(score / 100) * 327} 327`
})
const ringOffset = computed(() => 0)

const learningTips = computed(() => {
  const tips = []
  if (weakPoints.value.length > 0) {
    const names = weakPoints.value.slice(0, 3).map(w => '「' + w.concept + '」').join('、')
    const longUnreviewed = weakPoints.value.filter(w => w.daysSinceStudy > 14)
    tips.push({
      icon: '🔴', level: 'danger',
      title: '优先复习薄弱知识点',
      desc: `${names}掌握度较低。${longUnreviewed.length > 0 ? '其中 ' + longUnreviewed.length + ' 个知识点已超过 14 天未复习，遗忘衰减严重，建议尽快温习。' : '建议通过练习和资料阅读加强理解。'}`
    })
  }
  if (strongPoints.value.length > 0) {
    const names = strongPoints.value.slice(0, 2).map(s => '「' + s.concept + '」').join('、')
    tips.push({
      icon: '🟢', level: 'success',
      title: '巩固已有优势',
      desc: `${names}掌握良好，可以挑战更高难度的拓展内容以保持知识活跃度。`
    })
  }
  if (stats.quizCount < 10) {
    tips.push({
      icon: '🟡', level: 'warning',
      title: '增加练习量',
      desc: `当前仅完成 ${stats.quizCount} 道练习，数据样本较少。建议完成 10 道以上以获得稳定的评估结果。`
    })
  }
  tips.push({
    icon: '🔵', level: 'info',
    title: '保持学习节奏',
    desc: '定期复习是克服遗忘的最有效方法。建议每个知识点每周至少完成 1 次回顾练习。'
  })
  return tips
})

// ---- chart options ----
const radarOption = computed(() => {
  const data = reportData.value.knowledgeRadar || []
  if (!data.length) return {}
  return {
    title: { text: '知识掌握度', left: 'center', textStyle: { fontSize: 14, color: '#1a1a2e' } },
    tooltip: {},
    legend: { bottom: 0, data: ['有效掌握度'] },
    radar: {
      indicator: data.map(d => ({ name: d.concept, max: 1 })),
      radius: '60%'
    },
    series: [{
      type: 'radar',
      data: [{ value: data.map(d => d.proficiency), name: '有效掌握度' }],
      areaStyle: { color: 'rgba(102,126,234,0.2)' },
      lineStyle: { color: '#667eea' },
      itemStyle: { color: '#667eea' }
    }]
  }
})

const growthOption = computed(() => {
  const data = reportData.value.growthTrend || []
  if (!data.length) return {}
  return {
    title: { text: '能力成长趋势', left: 'center', textStyle: { fontSize: 14, color: '#1a1a2e' } },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: data.map(d => d.week) },
    yAxis: { type: 'value', min: 0, max: 1, axisLabel: { formatter: v => Math.round(v * 100) + '%' } },
    series: [{
      type: 'line', data: data.map(d => d.avgProficiency),
      smooth: true, lineStyle: { color: '#667eea' },
      itemStyle: { color: '#667eea' },
      areaStyle: { color: 'rgba(102,126,234,0.1)' }
    }]
  }
})

const inputOption = computed(() => {
  const data = (reportData.value.learningInput?.weeklyTrend || [])
  if (!data.length) return {}
  return {
    title: { text: '近4周投入度', left: 'center', textStyle: { fontSize: 14, color: '#1a1a2e' } },
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, data: ['测验数', '浏览数'] },
    xAxis: { type: 'category', data: data.map(d => d.week) },
    yAxis: { type: 'value' },
    series: [
      { name: '测验数', type: 'bar', data: data.map(d => d.quizzes), itemStyle: { color: '#667eea' } },
      { name: '浏览数', type: 'bar', data: data.map(d => d.views), itemStyle: { color: '#764ba2' } }
    ]
  }
})

// ---- methods ----
const loadReport = async () => {
  try {
    const res = await getProfileSummaryApi(getStudentId())
    const data = res.data
    if (data && data.exists) {
      profileExists.value = true
      stats.learningDays = data.learningDays || 0
      stats.resourceCount = data.resourceCount || 0
      stats.quizCount = data.quizCount || 0

      reportData.value = {
        compositeScore: data.compositeScore || 0,
        knowledgeRadar: data.knowledgeRadar || [],
        learningProgress: data.learningProgress || { totalNodes: 0, completedNodes: 0, currentNode: '' },
        learningInput: data.learningInput || { activeDays: 0, totalDurationMin: 0, resourceViews: 0, chatRounds: 0, quizTotal: 0, weeklyTrend: [] },
        growthTrend: data.growthTrend || []
      }

      const confMap = data.confidences || {}
      dimensions.value = [
        { key: 'knowledge', icon: '📖', label: '知识基础',
          value: data.knowledgeBaseLevel || '', confidence: Math.round((confMap.knowledge || 0) * 100), color: '#667eea' },
        { key: 'cognitive', icon: '🧩', label: '认知风格',
          value: data.cognitiveStyleType || '', confidence: Math.round((confMap.cognitive || 0) * 100), color: '#3b82f6' },
        { key: 'error', icon: '⚠️', label: '易错偏好',
          value: (data.errorPatternTags || []).join('、') || '', confidence: Math.round((confMap.error || 0) * 100), color: '#f97316' },
        { key: 'pace', icon: '🏃', label: '学习步调',
          value: data.learningPaceType || '', confidence: Math.round((confMap.pace || 0) * 100), color: '#22c55e' },
        { key: 'preference', icon: '🎯', label: '内容偏好',
          value: data.contentPreferenceType || '', confidence: 50, color: '#ec4899' },
        { key: 'goal', icon: '🏆', label: '目标导向',
          value: data.goalOrientationType || '', confidence: Math.round((confMap.goal || 0) * 100), color: '#a855f7' }
      ]

      weakPoints.value = (data.weakPoints || []).map(wp => ({
        concept: wp.concept, proficiency: wp.proficiency, confidence: wp.confidence,
        totalQuestions: wp.totalQuestions || 0, correctQuestions: wp.correctQuestions || 0,
        daysSinceStudy: wp.daysSinceStudy || 0
      }))
      strongPoints.value = (data.strongPoints || []).map(sp => ({
        concept: sp.concept, proficiency: sp.proficiency, confidence: sp.confidence
      }))
      summaryText.value = data.summary || ''
    } else {
      profileExists.value = false
    }
  } catch (e) { /* silent */ }
}

const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.id || 'anonymous'
  } catch { return 'anonymous' }
}

const fmtPct = (val) => {
  if (val == null) return '0%'
  return Math.round(val * 100) + '%'
}

onMounted(async () => {
  const info = localStorage.getItem('userInfo')
  if (info) { try { userInfo.value = JSON.parse(info) } catch { userInfo.value = {} } }
  await loadReport()
  window.addEventListener('report-updated', loadReport)
})

onUnmounted(() => {
  window.removeEventListener('report-updated', loadReport)
})
</script>

<style scoped>
.page-container { max-width: 1000px; margin: 0 auto; padding: 32px 24px 60px; }

/* ---- header ---- */
.page-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  background: #fff; padding: 28px 32px; border-radius: 20px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.header-left h1 { font-size: 26px; font-weight: 700; color: #1a1a2e; margin: 0 0 6px; }
.header-left p { font-size: 14px; color: #8890a0; margin: 0; }
.header-actions { display: flex; gap: 10px; flex-shrink: 0; }

/* ---- section ---- */
.section { margin-top: 24px; }
.section h3 { font-size: 18px; font-weight: 600; color: #1a1a2e; margin: 0 0 14px; display: flex; align-items: center; gap: 10px; }
.section-badge { font-size: 11px; padding: 2px 10px; border-radius: 10px; background: #f2f3f7; color: #909399; font-weight: 400; }
.section-badge.ready { background: #eef0ff; color: #667eea; }

/* ---- profile card ---- */
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

/* ---- dimension card ---- */
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

/* ---- score card (right column) ---- */
.score-card {
  background: #fff; padding: 24px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
  display: flex; gap: 28px; align-items: center;
}
.score-ring-wrap { display: flex; flex-direction: column; align-items: center; gap: 8px; flex-shrink: 0; }
.score-ring { position: relative; width: 120px; height: 120px; }
.score-svg { width: 120px; height: 120px; }
.score-inner {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center;
}
.score-big { font-size: 28px; font-weight: 800; color: #667eea; line-height: 1; }
.score-unit { font-size: 12px; color: #909399; }
.score-desc { font-size: 12px; color: #667eea; font-weight: 600; margin: 0; }
.score-metrics { flex: 1; display: flex; flex-direction: column; gap: 14px; }
.metric-item { display: flex; flex-direction: column; gap: 4px; }
.metric-head { display: flex; align-items: center; gap: 8px; }
.metric-dot { width: 8px; height: 8px; border-radius: 50%; }
.metric-label { font-size: 13px; color: #4a4f5e; flex: 1; }
.metric-val { font-size: 13px; font-weight: 600; color: #1a1a2e; }

/* ---- report ---- */
.report-header {
  display: flex; align-items: baseline; justify-content: space-between;
  margin-bottom: 14px;
}
.report-header h3 { margin: 0; }
.report-date { font-size: 12px; color: #c0c4cc; }
.report-body {
  background: #fff; padding: 28px 32px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
  font-size: 14px; line-height: 1.8; color: #2c3142;
}
.report-body h4 { font-size: 16px; margin: 28px 0 14px; color: #1a1a2e; }
.report-body h4:first-child { margin-top: 0; }
.report-body blockquote {
  margin: 16px 0; padding: 14px 18px;
  background: #f8f7ff; border-left: 4px solid #667eea; border-radius: 0 8px 8px 0;
  color: #4a4f5e; font-size: 14px;
}
.report-body blockquote p { margin: 0; }
.report-body table {
  width: 100%; border-collapse: collapse; margin: 12px 0;
  font-size: 13px;
}
.report-body th {
  background: #f8f9fe; color: #1a1a2e; font-weight: 600;
  padding: 10px 14px; text-align: left; border-bottom: 2px solid #eef0f4;
}
.report-body td { padding: 10px 14px; border-bottom: 1px solid #f2f3f7; }
.report-body tr.row-weak { background: #fef5f5; }
.report-body tr.row-strong { background: #f0f9eb; }
.report-body tr.row-even { background: #fefce8; }
.proficiency-badge {
  display: inline-block; padding: 2px 10px; border-radius: 10px;
  font-size: 12px; font-weight: 600;
}
.proficiency-badge.weak { background: #fee2e2; color: #dc2626; }
.proficiency-badge.strong { background: #dcfce7; color: #16a34a; }
.proficiency-badge.even { background: #fef9c3; color: #ca8a04; }

/* ---- score explainer ---- */
.score-explainer { margin-bottom: 20px; }
.score-explainer :deep(.el-collapse-item__header) {
  font-size: 14px; font-weight: 600; color: #667eea; padding: 8px 0;
}
.explainer-content p { margin: 0 0 12px; font-size: 13px; }
.explainer-content table { font-size: 12px; }
.explainer-content th, .explainer-content td { padding: 8px 10px; }
.explainer-content code { background: #f0f2f5; padding: 1px 5px; border-radius: 3px; font-size: 12px; }

/* ---- tips ---- */
.tips-list { display: flex; flex-direction: column; gap: 12px; }
.tip-item {
  display: flex; gap: 14px; padding: 16px 18px; border-radius: 12px;
  border: 1px solid #eef0f4; transition: all 0.2s;
}
.tip-item:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.tip-item.danger { border-left: 4px solid #f56c6c; }
.tip-item.warning { border-left: 4px solid #e6a23c; }
.tip-item.success { border-left: 4px solid #67c23a; }
.tip-item.info { border-left: 4px solid #667eea; }
.tip-icon { font-size: 22px; flex-shrink: 0; }
.tip-body strong { display: block; font-size: 14px; color: #1a1a2e; margin-bottom: 4px; }
.tip-body p { margin: 0; font-size: 13px; color: #606266; line-height: 1.6; }

/* ---- path placeholder ---- */
.path-placeholder {
  background: #fafbfc; border: 1px dashed #dcdfe6; border-radius: 12px;
  padding: 20px;
}

/* ---- empty ---- */
.report-empty { padding: 32px 0; }

@media (max-width: 800px) {
  .two-col { grid-template-columns: 1fr; }
  .profile-card { flex-direction: column; text-align: center; }
  .score-card { flex-direction: column; }
  .page-header { flex-direction: column; gap: 12px; }
}

.stats-row { display: flex; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.stat-card {
  flex: 1; min-width: 100px; text-align: center;
  padding: 16px 12px; border-radius: 14px;
  background: linear-gradient(135deg, rgba(102,126,234,0.06), rgba(118,75,162,0.04));
  border: 1px solid rgba(102,126,234,0.12);
}
.stat-card.composite { background: linear-gradient(135deg, #667eea, #764ba2); color: #fff; border: none; }
.stat-card-num { display: block; font-size: 28px; font-weight: 800; }
.stat-card.composite .stat-card-num { color: #fff; }
.stat-card-label { font-size: 12px; color: #909399; margin-top: 4px; display: block; }
.stat-card.composite .stat-card-label { color: rgba(255,255,255,0.8); }

.chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 24px; }
.chart-box {
  background: #fff; border-radius: 14px; padding: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
}
.chart-empty {
  display: flex; align-items: center; justify-content: center;
  min-height: 200px; color: #909399; font-size: 14px;
}
.progress-label { text-align: center; color: #909399; font-size: 13px; margin-top: 10px; }

.tag-cloud { padding: 16px; margin-bottom: 20px; text-align: center; }
.tag-cloud h4 { margin-bottom: 12px; }
.tag-cloud-item {
  display: inline-block; margin: 4px 8px; cursor: default;
  font-weight: 600; transition: transform 0.15s;
}
.tag-cloud-item:hover { transform: scale(1.1); }

@media (max-width: 768px) {
  .chart-row { grid-template-columns: 1fr; }
  .stat-card { min-width: 70px; }
}
</style>
