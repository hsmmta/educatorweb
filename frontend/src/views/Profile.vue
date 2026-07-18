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
        <div class="profile-avatar-wrap">
          <el-avatar :size="68" :icon="UserFilled" />
          <div class="avatar-ring"></div>
        </div>
        <div class="profile-info">
          <h2>{{ userInfo?.nickname || '同学' }}</h2>
          <div class="profile-meta-row">
            <span class="profile-meta" v-if="userInfo?.phone">{{ userInfo.phone }}</span>
            <span class="profile-meta" v-if="userInfo?.email">{{ userInfo.email }}</span>
          </div>
        </div>
        <div class="profile-divider"></div>
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
          <div class="p-stat accent" v-if="reportData.compositeScore != null">
            <span class="p-stat-num">{{ reportData.compositeScore }}</span>
            <span class="p-stat-label">综合评分</span>
          </div>
        </div>
      </div>
    </section>

    <div class="two-col">
      <!-- 左：6维学习画像 -->
      <section class="section">
        <h3>
          🧠 6维学习画像
          <span v-if="!profileExists" class="section-badge">待构建</span>
          <span v-else class="section-badge ready">已构建</span>
        </h3>
        <div class="dimension-card">
          <div v-for="dim in dimensions" :key="dim.key" class="dim-item">
            <div class="dim-header">
              <span class="dim-icon-pill" :style="{ background: dim.color + '18', color: dim.color }">
                {{ dim.icon }}
              </span>
              <div class="dim-info">
                <span class="dim-label">{{ dim.label }}</span>
                <span :class="['dim-value', { placeholder: !dim.value }]">
                  {{ dim.value || '待构建' }}
                </span>
              </div>
            </div>
            <div class="dim-bar-wrap">
              <div class="dim-bar" :style="{ width: dim.confidence + '%', background: dim.color }">
                <div class="dim-bar-sheen"></div>
              </div>
            </div>
            <span class="dim-confidence">{{ dim.confidence }}%</span>
          </div>
        </div>
        <div v-if="!profileExists" class="card-tip">
          还没有学习画像？<el-link type="primary" @click="$router.push('/profile/chat')">通过对话构建 →</el-link>
        </div>
      </section>

      <!-- 右：综合评分 -->
      <section class="section">
        <h3>📈 综合评分</h3>
        <div class="score-card-v2">
          <!-- 环 + 指标 -->
          <div class="score-top">
            <div class="score-ring-wrap">
              <div class="score-ring-glow"></div>
              <svg viewBox="0 0 140 140" class="score-svg-v2">
                <defs>
                  <linearGradient id="scoreGradV2" x1="0%" y1="0%" x2="100%" y2="100%">
                    <stop offset="0%" stop-color="#667eea" />
                    <stop offset="50%" stop-color="#8b5cf6" />
                    <stop offset="100%" stop-color="#764ba2" />
                  </linearGradient>
                </defs>
                <!-- background ring -->
                <circle cx="70" cy="70" r="58" fill="none" stroke="#eef0f4" stroke-width="10" />
                <!-- decorative dash ring -->
                <circle cx="70" cy="70" r="58" fill="none" stroke="#eef0f4" stroke-width="2"
                  stroke-dasharray="3 6" opacity="0.6" transform="rotate(15 70 70)" />
                <!-- progress arc -->
                <circle cx="70" cy="70" r="58" fill="none" stroke="url(#scoreGradV2)" stroke-width="10"
                  stroke-linecap="round" :stroke-dasharray="ringDashV2" :stroke-dashoffset="ringOffsetV2"
                  transform="rotate(-90 70 70)" style="transition: stroke-dashoffset 1s cubic-bezier(0.4, 0, 0.2, 1)" />
              </svg>
              <div class="score-inner-v2">
                <span class="score-big-v2">{{ assessment.compositeScore }}</span>
                <span class="score-unit-v2">分</span>
              </div>
            </div>
            <div class="score-metrics-v2">
              <div v-for="item in assessment.details" :key="item.label" class="metric-item-v2">
                <div class="metric-icon-v2" :style="{ background: item.color + '18', color: item.color }">
                  <template v-if="item.label === '知识掌握'">📖</template>
                  <template v-else-if="item.label === '练习正确率'">✅</template>
                  <template v-else-if="item.label === '学习投入度'">🔥</template>
                  <template v-else>📦</template>
                </div>
                <div class="metric-body-v2">
                  <div class="metric-head-v2">
                    <span class="metric-label-v2">{{ item.label }}</span>
                    <span class="metric-val-v2">{{ item.value }}%</span>
                  </div>
                  <div class="metric-bar-wrap-v2">
                    <div class="metric-bar-v2"
                      :style="{ width: item.value + '%', background: item.color }"></div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 学习脉动：本周活动 mini 条 -->
          <div class="score-pulse" v-if="(reportData.learningInput?.weeklyTrend || []).length">
            <div class="pulse-header">
              <span class="pulse-title">📡 学习脉动</span>
              <span class="pulse-sub">近4周活跃趋势</span>
            </div>
            <div class="pulse-bars">
              <div v-for="(w, wi) in reportData.learningInput.weeklyTrend.slice(-4)" :key="wi"
                   class="pulse-bar-col">
                <div class="pulse-bar-stack">
                  <div class="pulse-bar quiz" :style="{ height: barHeight(w.quizzes || 0, maxPulseQ) + '%' }"></div>
                  <div class="pulse-bar view" :style="{ height: barHeight(w.views || 0, maxPulseV) + '%' }"></div>
                </div>
                <span class="pulse-label">{{ w.week }}</span>
              </div>
            </div>
            <div class="pulse-legend">
              <span><i class="pl-dot" style="background:#667eea"></i>测验</span>
              <span><i class="pl-dot" style="background:#c4b5fd"></i>浏览</span>
            </div>
          </div>
          <div class="score-pulse score-pulse-empty" v-else>
            <p>完成练习后，这里将展示每周学习脉动</p>
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
            <span class="stat-card-num">{{ reportData.compositeScore != null ? reportData.compositeScore : '—' }}</span>
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

        <!-- Row 4: 薄弱环节 + 强项 双栏 -->
        <div class="chart-row" v-if="weakPoints.length || strongPoints.length">
          <div class="weakness-card" v-if="weakPoints.length">
            <div class="weakness-header">
              <span class="weakness-icon-wrap"><span class="weakness-icon">!</span></span>
              <div>
                <h4>薄弱环节</h4>
                <p class="weakness-sub">共 {{ weakPoints.length }} 个知识点需要加强</p>
              </div>
            </div>
            <div class="weakness-list">
              <div v-for="wp in weakPoints.slice(0, 5)" :key="wp.concept" class="weakness-row">
                <div class="wr-top">
                  <span class="wr-name">{{ wp.concept }}</span>
                  <span class="wr-meta">
                    {{ wp.totalQuestions }}题 / 正确{{ wp.correctQuestions }}题
                    <template v-if="wp.daysSinceStudy > 0">
                      · <span :class="wp.daysSinceStudy > 14 ? 'stale' : 'fresh'">{{ wp.daysSinceStudy }}天未复习</span>
                    </template>
                  </span>
                </div>
                <div class="wr-bar-wrap">
                  <div class="wr-bar" :style="{ width: Math.round(wp.proficiency * 100) + '%', background: wp.proficiency < 0.4 ? 'linear-gradient(90deg, #f56c6c, #f87171)' : wp.proficiency < 0.6 ? 'linear-gradient(90deg, #e6a23c, #fbbf24)' : 'linear-gradient(90deg, #667eea, #818cf8)' }"></div>
                </div>
                <div class="wr-bottom">
                  <span class="wr-pct" :class="wp.proficiency < 0.4 ? 'danger' : wp.proficiency < 0.6 ? 'warn' : ''">
                    掌握度 {{ Math.round(wp.proficiency * 100) }}%
                  </span>
                  <span class="wr-conf">置信 {{ Math.round(wp.confidence * 100) }}%</span>
                </div>
              </div>
            </div>
          </div>

          <div class="strength-card" v-if="strongPoints.length">
            <div class="strength-header">
              <span class="strength-icon-wrap"><span class="strength-icon">✓</span></span>
              <div>
                <h4>优势知识点</h4>
                <p class="strength-sub">{{ strongPoints.length }} 个知识点掌握良好</p>
              </div>
            </div>
            <div class="strength-list">
              <div v-for="sp in strongPoints.slice(0, 5)" :key="sp.concept" class="strength-row">
                <span class="sr-dot"></span>
                <span class="sr-name">{{ sp.concept }}</span>
                <span class="sr-pct">{{ Math.round(sp.proficiency * 100) }}%</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Row 5: 学习建议 + 评分说明 -->
        <div class="chart-row">
          <div class="tips-panel">
            <div class="tips-panel-header">
              <span class="tips-panel-icon">💡</span>
              <div>
                <h4>个性化学习建议</h4>
                <p class="tips-panel-sub">基于你的学习数据智能生成</p>
              </div>
            </div>
            <div class="tips-cards">
              <div v-for="(tip, i) in learningTips.slice(0, 4)" :key="i" :class="['tip-card', tip.level]">
                <div class="tip-card-step">{{ i + 1 }}</div>
                <div class="tip-card-body">
                  <div class="tip-card-head">
                    <strong>{{ tip.title }}</strong>
                    <span :class="['tip-card-badge', tip.level]">
                      {{ tip.level === 'danger' ? '优先' : tip.level === 'warning' ? '建议' : tip.level === 'success' ? '保持' : '提醒' }}
                    </span>
                  </div>
                  <p>{{ tip.desc }}</p>
                </div>
              </div>
            </div>
          </div>

          <div class="explainer-panel" v-if="weakPoints.length || strongPoints.length">
            <div class="explainer-panel-header">
              <span class="explainer-panel-icon">📐</span>
              <h4>评分依据</h4>
            </div>
            <div class="explainer-formula">
              <div class="formula-line">
                <span class="formula-label">综合评分</span>
                <span class="formula-eq">= 有效掌握度均值 × 0.6 + 置信度均值 × 0.4</span>
              </div>
            </div>
            <div class="explainer-rows">
              <div class="explainer-row">
                <div class="er-dot" style="background:#667eea"></div>
                <div class="er-info">
                  <strong>原始正确率</strong>
                  <code>正确题数 / 总题数</code>
                </div>
              </div>
              <div class="explainer-row">
                <div class="er-dot" style="background:#e6a23c"></div>
                <div class="er-info">
                  <strong>有效掌握度</strong>
                  <code>原始正确率 × e<sup>(-天数/半衰期)</sup></code>
                  <span class="er-note">艾宾浩斯遗忘衰减</span>
                </div>
              </div>
              <div class="explainer-row">
                <div class="er-dot" style="background:#67c23a"></div>
                <div class="er-info">
                  <strong>置信度</strong>
                  <code>1 − e<sup>(−0.4 × 答题数)</sup></code>
                  <span class="er-note">仅基于答题数量</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 错题预览 -->
        <div class="section" v-if="wrongPreview.length" style="margin-top:24px">
          <div class="wrong-preview-head">
            <h3>📝 错题回顾</h3>
            <span class="wrong-preview-count">{{ wrongTotal }} 道</span>
          </div>
          <div class="wrong-preview-cards">
            <div
              v-for="item in wrongPreview" :key="item.id"
              class="wrong-preview-card"
              @click="$router.push('/wrong-answers')"
            >
              <span class="wpc-num">{{ item.id }}</span>
              <span class="wpc-text">{{ item.question }}</span>
              <span class="wpc-arrow">→</span>
            </div>
          </div>
          <div v-if="wrongTotal > 2" class="wrong-preview-more" @click="$router.push('/wrong-answers')">
            查看全部 {{ wrongTotal }} 道错题 →
          </div>
        </div>

        <!-- 学习路径摘要 -->
        <div class="chart-box" v-if="savedPath" style="margin-top:20px">
          <h4>📐 当前学习路径</h4>
          <p style="color:#909399;font-size:13px;margin:8px 0">
            目标：{{ savedPath.targetKnowledgePoint }} ·
            {{ savedPath.completedNodes || 0 }}/{{ savedPath.totalNodes }} 节点已完成
          </p>
          <el-progress
            :percentage="savedPath.totalNodes ? Math.round((savedPath.completedNodes || 0) / savedPath.totalNodes * 100) : 0"
            :stroke-width="10" color="#667eea" style="margin-bottom:12px" />
          <el-button size="small" @click="$router.push('/push')">查看完整路径 →</el-button>
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
import request from '../api/request.js'
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
const savedPath = ref(null)

const loadSavedPathData = async () => {
  try {
    const res = await request.get('/push/path/' + getStudentId() + '/saved')
    const data = res.data?.data
    savedPath.value = data?.exists ? data.path : null
  } catch { savedPath.value = null }
}

const summaryText = ref('')
const weakPoints = ref([])
const strongPoints = ref([])
const evenPoints = ref([])

// ---- wrong answer preview ----
const wrongPreview = ref([])
const wrongTotal = ref(0)

const loadWrongPreview = async () => {
  try {
    const res = await request.get('/quiz/wrong-answers/' + getStudentId())
    const list = res.data?.data || []
    wrongTotal.value = list.length
    wrongPreview.value = list.slice(0, 2)
  } catch { wrongTotal.value = 0; wrongPreview.value = [] }
}

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

const ringDashV2 = computed(() => {
  const score = typeof assessment.compositeScore === 'number' ? assessment.compositeScore : 0
  const circumference = 2 * Math.PI * 58  // r=58 → ~364.4
  return `${(score / 100) * circumference} ${circumference}`
})
const ringOffsetV2 = computed(() => 0)

// ---- pulse bar helpers ----
const maxPulseQ = computed(() => {
  const vals = (reportData.value.learningInput?.weeklyTrend || []).map(w => w.quizzes || 0)
  return Math.max(1, ...vals)
})
const maxPulseV = computed(() => {
  const vals = (reportData.value.learningInput?.weeklyTrend || []).map(w => w.views || 0)
  return Math.max(1, ...vals)
})
const barHeight = (val, max) => {
  if (!max || max === 0) return 0
  return Math.max(4, Math.round((val / max) * 100))
}

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
        compositeScore: data.compositeScore != null ? data.compositeScore : 0,
        knowledgeRadar: data.knowledgeRadar || [],
        learningProgress: data.learningProgress || { totalNodes: 0, completedNodes: 0, currentNode: '' },
        learningInput: data.learningInput || { activeDays: 0, totalDurationMin: 0, resourceViews: 0, chatRounds: 0, quizTotal: 0, weeklyTrend: [] },
        growthTrend: data.growthTrend || []
      }

      // 更新右上角综合评分环状图
      assessment.compositeScore = data.compositeScore != null ? data.compositeScore : '--'
      assessment.details = data.details || assessment.details

      const confMap = data.confidences || {}
      dimensions.value = [
        { key: 'knowledge', icon: '📖', label: '知识基础',
          value: translateDim('knowledgeBase', data.knowledgeBaseLevel), confidence: Math.round((confMap.knowledge || 0) * 100), color: '#667eea' },
        { key: 'cognitive', icon: '🧩', label: '认知风格',
          value: translateDim('cognitiveStyle', data.cognitiveStyleType), confidence: Math.round((confMap.cognitive || 0) * 100), color: '#3b82f6' },
        { key: 'error', icon: '⚠️', label: '易错偏好',
          value: (data.errorPatternTags || []).map(t => translateDim('errorTag', t)).join('、') || '', confidence: Math.round((confMap.error || 0) * 100), color: '#f97316' },
        { key: 'pace', icon: '🏃', label: '学习步调',
          value: translateDim('learningPace', data.learningPaceType), confidence: Math.round((confMap.pace || 0) * 100), color: '#22c55e' },
        { key: 'preference', icon: '🎯', label: '内容偏好',
          value: translateDim('contentPref', data.contentPreferenceType), confidence: 50, color: '#ec4899' },
        { key: 'goal', icon: '🏆', label: '目标导向',
          value: translateDim('goalOrientation', data.goalOrientationType), confidence: Math.round((confMap.goal || 0) * 100), color: '#a855f7' }
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
    loadSavedPathData()
    loadWrongPreview()
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

// 将 LLM 生成的英文维度值映射为中文（LLM prompt已要求中文，但有时仍输出英文）
const translateDim = (dim, val) => {
  if (!val) return ''
  const key = (val || '').toLowerCase().trim()
  const maps = {
    knowledgeBase: {
      // prompt 要求: 薄弱/一般/扎实，但 LLM 可能输出以下英文
      'needs_diagnosis': '待诊断', beginner: '入门', novice: '新手', elementary: '初级',
      basic: '基础', intermediate: '中等', moderate: '一般', average: '一般',
      advanced: '进阶', expert: '专家', proficient: '熟练', mastery: '精通',
      weak: '薄弱', poor: '薄弱', 'below average': '偏弱', 'above average': '偏强',
      strong: '扎实', solid: '扎实', excellent: '优秀', outstanding: '突出',
      none: '零基础', 'no knowledge': '零基础', 'some knowledge': '有一定基础',
      foundational: '基础', 'good foundation': '基础扎实', limited: '有限',
      sufficient: '足够', adequate: '合格', competent: '胜任',
      'needs improvement': '待提升', developing: '发展中', emerging: '初步形成'
    },
    cognitiveStyle: {
      // prompt 要求: 直觉型/分析型/视觉型/实践型
      visual: '视觉型', auditory: '听觉型', kinesthetic: '动手型',
      'reading/writing': '读写型', verbal: '语言型', logical: '逻辑型',
      social: '协作型', solitary: '独立型', multimodal: '混合型',
      intuitive: '直觉型', analytical: '分析型', analytic: '分析型',
      practical: '实践型', 'hands-on': '实践型', experiential: '体验型',
      reflective: '反思型', active: '活跃型', sensing: '感知型',
      sequential: '循序型', global: '全局型', holistic: '整体型',
      'visual-spatial': '视觉空间型', 'auditory-sequential': '听觉序列型',
      abstract: '抽象型', concrete: '具象型', 'field-dependent': '场依存型',
      'field-independent': '场独立型', impulsive: '冲动型', reflective_style: '审慎型'
    },
    errorTag: {
      // prompt 要求: ["概念混淆", "过度泛化", "基础薄弱"]
      'calculation error': '计算错误', 'concept misunderstanding': '概念混淆',
      'careless mistake': '粗心大意', 'logic error': '逻辑错误',
      'memory lapse': '记忆遗忘', 'application error': '应用错误',
      'overgeneralization': '过度泛化', 'overfitting': '过拟合',
      'underfitting': '欠拟合', 'data leakage': '数据泄露',
      'feature engineering': '特征工程错误', 'hyperparameter': '超参数调节问题',
      'gradient issue': '梯度问题', 'vanishing gradient': '梯度消失',
      'exploding gradient': '梯度爆炸', 'dimension mismatch': '维度不匹配',
      'loss function': '损失函数理解偏差', 'bias-variance': '偏差方差权衡问题',
      'regularization': '正则化理解不足', 'optimization': '优化器选择不当',
      'data preprocessing': '数据预处理问题', 'label encoding': '标签编码错误',
      'train test split': '训练测试划分问题', 'cross validation': '交叉验证理解不足',
      'evaluation metric': '评估指标选择不当', 'confusion matrix': '混淆矩阵理解问题',
      'model selection': '模型选择不当', 'ensemble': '集成学习理解不足',
      'weak foundation': '基础薄弱', 'lack of practice': '缺乏练习',
      'knowledge gap': '知识盲区', 'terminology confusion': '术语混淆',
      'syntax error': '语法错误', 'import error': '导入错误'
    },
    learningPace: {
      // prompt 要求: 稳扎稳打型/快速突击型/跳跃式
      slow: '稳健型', 'slow and steady': '稳扎稳打型', 'steady pace': '稳扎稳打型',
      stepwise: '循序渐进型', methodical: '有条不紊型', careful: '谨慎型',
      moderate: '适中型', 'moderate pace': '节奏适中型', balanced: '均衡型',
      fast: '快速型', 'fast learner': '学习迅速型', 'fast-paced': '快节奏型',
      rapid: '快速突击型', accelerated: '加速型', intensive: '高强度型',
      adaptive: '灵活适应型', flexible: '灵活型', 'self-paced': '自主型',
      structured: '结构化型', systematic: '系统型', iterative: '迭代型',
      'spiral learning': '螺旋上升型', 'just-in-time': '按需学习型',
      skipping: '跳跃式', nonlinear: '非线性型', 'burst learning': '突击型',
      consistent: '持续型', sporadic: '间歇型', irregular: '不规律型'
    },
    contentPref: {
      // prompt 要求: 视频学习/文档学习/代码实践/混合学习
      video: '视频学习', text: '文档学习', reading: '文档学习',
      document: '文档学习', documentation: '文档学习',
      interactive: '交互式学习', 'hands-on': '代码实践', coding: '代码实践',
      code: '代码实践', practice: '代码实践', programming: '代码实践',
      project: '项目驱动', 'project-based': '项目驱动',
      audio: '音频学习', podcast: '播客学习', lecture: '听课学习',
      visual: '可视化学习', diagram: '图表学习', infographic: '信息图学习',
      tutorial: '教程学习', 'case study': '案例分析', example: '示例学习',
      theoretical: '理论学习', discussion: '讨论学习', collaborative: '协作学习',
      'self-study': '自学', mentorship: '导师指导', workshop: '工作坊',
      mixed: '混合学习', blended: '混合学习', hybrid: '混合学习',
      multimodal: '多模态学习', diverse: '多样化学习', varied: '多样化学习'
    },
    goalOrientation: {
      // prompt 要求: 求职准备/考试备考/兴趣探索/项目实战
      exam: '考试备考', examination: '考试备考', 'test prep': '考试备考',
      'exam preparation': '考试备考', certification: '考证导向',
      certificate: '考证导向', qualification: '资格认证',
      'job preparation': '求职准备', job: '求职准备', career: '职业发展',
      'career advancement': '职业发展', employment: '就业导向',
      internship: '实习准备', interview: '面试准备',
      practical: '项目实战', 'project-based': '项目实战',
      'project completion': '项目完成', 'hands-on project': '项目实战',
      interest: '兴趣探索', 'personal interest': '兴趣驱动',
      hobby: '兴趣爱好', curiosity: '好奇心驱动', exploratory: '探索型',
      academic: '学术研究', research: '学术研究', 'academic research': '学术研究',
      'skill mastery': '技能精通', 'skill building': '技能提升',
      upskilling: '技能提升', reskilling: '转行学习',
      'knowledge expansion': '知识拓展', 'staying current': '保持前沿',
      'course requirement': '课程要求', assignment: '作业完成',
      'degree requirement': '学位要求', competition: '竞赛准备',
      'problem solving': '解决问题', 'specific task': '特定任务'
    }
  }
  const map = maps[dim] || {}
  return map[key] || val
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
  padding: 28px 32px; border-radius: 20px;
  background: linear-gradient(135deg, #fff 0%, #fafbff 50%, #f8f7ff 100%);
  box-shadow: 0 1px 3px rgba(0,0,0,0.04), 0 4px 16px rgba(102,126,234,0.06);
  position: relative; overflow: hidden;
}
.profile-card::before {
  content: ''; position: absolute; top: -60px; right: -40px;
  width: 200px; height: 200px; border-radius: 50%;
  background: radial-gradient(circle, rgba(102,126,234,0.06) 0%, transparent 70%);
  pointer-events: none;
}
.profile-avatar-wrap { position: relative; flex-shrink: 0; }
.avatar-ring {
  position: absolute; inset: -3px; border-radius: 50%;
  border: 2px solid transparent;
  background: linear-gradient(135deg, #667eea, #764ba2) border-box;
  -webkit-mask: linear-gradient(#fff 0 0) padding-box, linear-gradient(#fff 0 0);
  mask: linear-gradient(#fff 0 0) padding-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
}
.profile-info { flex: 1; position: relative; z-index: 1; }
.profile-info h2 { font-size: 22px; font-weight: 700; color: #1a1a2e; margin: 0 0 4px; }
.profile-meta-row { display: flex; gap: 16px; }
.profile-meta { font-size: 13px; color: #909399; }
.profile-divider { width: 1px; height: 48px; background: #eef0f4; flex-shrink: 0; }
.profile-stats { display: flex; gap: 28px; position: relative; z-index: 1; }
.p-stat { text-align: center; }
.p-stat-num { display: block; font-size: 26px; font-weight: 800; color: #1a1a2e; }
.p-stat.accent .p-stat-num { color: #667eea; }
.p-stat-label { font-size: 12px; color: #909399; }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }

/* ---- dimension card (left) ---- */
.dimension-card {
  background: #fff; padding: 18px 24px 20px; border-radius: 16px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
  display: flex; flex-direction: column; gap: 14px;
}
.dim-item { display: flex; align-items: center; gap: 10px; }
.dim-header { display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1; }
.dim-icon-pill {
  width: 36px; height: 36px; border-radius: 10px; display: flex;
  align-items: center; justify-content: center; flex-shrink: 0;
  font-size: 16px; line-height: 1;
}
.dim-info { min-width: 0; flex: 1; }
.dim-label { display: block; font-size: 13px; font-weight: 600; color: #1a1a2e; }
.dim-value { font-size: 11px; color: #667eea; font-weight: 500; }
.dim-value.placeholder { color: #c0c4cc; }

.dim-bar-wrap {
  height: 6px; border-radius: 3px; background: #f2f3f7;
  width: 100px; flex-shrink: 0; overflow: hidden;
}
.dim-bar {
  height: 100%; border-radius: 3px; position: relative;
  transition: width 0.8s cubic-bezier(0.4, 0, 0.2, 1);
  min-width: 0;
}
.dim-bar-sheen {
  position: absolute; top: 0; right: 0; bottom: 0; left: 0;
  background: linear-gradient(90deg, transparent 50%, rgba(255,255,255,0.3));
}
.dim-confidence { font-size: 11px; color: #909399; width: 32px; text-align: right; flex-shrink: 0; }
.card-tip { text-align: center; font-size: 13px; color: #909399; margin-top: 4px; }

/* ---- score card v2 (right column) ---- */
.score-card-v2 {
  background: #fff; border-radius: 16px; padding: 20px 24px 16px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
  display: flex; flex-direction: column; gap: 0;
}
.score-top { display: flex; gap: 20px; align-items: center; margin-bottom: 16px; }

/* ring */
.score-ring-wrap {
  position: relative; width: 130px; height: 130px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
}
.score-ring-glow {
  position: absolute; inset: -12px; border-radius: 50%;
  background: radial-gradient(circle, rgba(102,126,234,0.12) 0%, transparent 70%);
  animation: ring-pulse 3s ease-in-out infinite;
}
@keyframes ring-pulse {
  0%, 100% { transform: scale(1); opacity: 0.6; }
  50% { transform: scale(1.08); opacity: 1; }
}
.score-svg-v2 { width: 130px; height: 130px; position: relative; z-index: 1; }
.score-inner-v2 {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center; z-index: 1;
}
.score-big-v2 { font-size: 34px; font-weight: 800; color: #667eea; line-height: 1; }
.score-unit-v2 { font-size: 13px; color: #909399; font-weight: 500; }

/* metrics v2 */
.score-metrics-v2 { flex: 1; display: flex; flex-direction: column; gap: 10px; }
.metric-item-v2 { display: flex; align-items: center; gap: 10px; }
.metric-icon-v2 {
  width: 32px; height: 32px; border-radius: 9px; display: flex;
  align-items: center; justify-content: center; flex-shrink: 0;
  font-size: 14px;
}
.metric-body-v2 { flex: 1; min-width: 0; }
.metric-head-v2 { display: flex; justify-content: space-between; margin-bottom: 4px; }
.metric-label-v2 { font-size: 12px; color: #606266; }
.metric-val-v2 { font-size: 12px; font-weight: 700; color: #1a1a2e; }
.metric-bar-wrap-v2 { height: 5px; border-radius: 3px; background: #f2f3f7; overflow: hidden; }
.metric-bar-v2 {
  height: 100%; border-radius: 3px;
  transition: width 0.8s cubic-bezier(0.4, 0, 0.2, 1);
}

/* ---- pulse (weekly mini bars) ---- */
.score-pulse {
  border-top: 1px solid #f2f3f7; padding-top: 14px;
}
.score-pulse-empty {
  padding: 18px 0 6px; text-align: center;
}
.score-pulse-empty p { font-size: 12px; color: #c0c4cc; margin: 0; }
.pulse-header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 12px; }
.pulse-title { font-size: 13px; font-weight: 600; color: #1a1a2e; }
.pulse-sub { font-size: 11px; color: #c0c4cc; }
.pulse-bars { display: flex; gap: 12px; justify-content: center; align-items: flex-end; height: 72px; padding: 0 4px; }
.pulse-bar-col { display: flex; flex-direction: column; align-items: center; gap: 6px; flex: 1; }
.pulse-bar-stack {
  width: 22px; flex: 1; display: flex; flex-direction: column;
  justify-content: flex-end; gap: 2px; border-radius: 6px;
  overflow: hidden; background: #f8f9fe;
}
.pulse-bar {
  width: 100%; border-radius: 3px; transition: height 0.6s ease;
  min-height: 2px;
}
.pulse-bar.quiz { background: linear-gradient(180deg, #667eea, #818cf8); }
.pulse-bar.view { background: linear-gradient(180deg, #c4b5fd, #a78bfa); }
.pulse-label { font-size: 10px; color: #909399; font-weight: 500; }
.pulse-legend { display: flex; gap: 14px; justify-content: center; margin-top: 8px; }
.pulse-legend span { font-size: 11px; color: #909399; display: flex; align-items: center; gap: 4px; }
.pl-dot { display: inline-block; width: 6px; height: 6px; border-radius: 2px; }

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

/* ---- weaknesses & strengths ---- */
.weakness-card, .strength-card {
  background: #fff; border-radius: 16px; padding: 20px 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.weakness-header, .strength-header {
  display: flex; align-items: flex-start; gap: 14px; margin-bottom: 18px;
  padding-bottom: 16px; border-bottom: 1px solid #f2f3f7;
}
.weakness-header h4, .strength-header h4 { margin: 0 0 2px; font-size: 16px; color: #1a1a2e; }
.weakness-sub, .strength-sub { margin: 0; font-size: 12px; color: #909399; }
.weakness-icon-wrap, .strength-icon-wrap {
  width: 44px; height: 44px; border-radius: 14px; display: flex;
  align-items: center; justify-content: center; flex-shrink: 0;
}
.weakness-icon-wrap { background: linear-gradient(135deg, #fef2f2, #fee2e2); }
.strength-icon-wrap { background: linear-gradient(135deg, #f0fdf4, #dcfce7); }
.weakness-icon { font-size: 20px; font-weight: 800; color: #ef4444; font-family: 'Georgia', serif; }
.strength-icon { font-size: 20px; font-weight: 800; color: #22c55e; }

.weakness-list { display: flex; flex-direction: column; gap: 16px; }
.weakness-row { display: flex; flex-direction: column; gap: 6px; }
.wr-top { display: flex; justify-content: space-between; align-items: baseline; }
.wr-name { font-size: 14px; font-weight: 600; color: #1a1a2e; }
.wr-meta { font-size: 11px; color: #909399; }
.wr-meta .stale { color: #ef4444; font-weight: 600; }
.wr-meta .fresh { color: #22c55e; }

.wr-bar-wrap {
  height: 8px; border-radius: 4px; background: #f2f3f7;
  overflow: hidden; position: relative;
}
.wr-bar {
  height: 100%; border-radius: 4px;
  transition: width 1s cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
}
.wr-bar::after {
  content: ''; position: absolute; top: 0; right: 0; bottom: 0; left: 0;
  background: linear-gradient(90deg, transparent 60%, rgba(255,255,255,0.25));
}

.wr-bottom { display: flex; justify-content: space-between; }
.wr-pct { font-size: 12px; font-weight: 600; color: #667eea; }
.wr-pct.danger { color: #ef4444; }
.wr-pct.warn { color: #e6a23c; }
.wr-conf { font-size: 11px; color: #c0c4cc; }

/* strengths */
.strength-list { display: flex; flex-direction: column; gap: 10px; }
.strength-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border-radius: 10px;
  background: linear-gradient(135deg, rgba(34,197,94,0.04), rgba(34,197,94,0.01));
  transition: all 0.2s;
}
.strength-row:hover { background: linear-gradient(135deg, rgba(34,197,94,0.08), rgba(34,197,94,0.02)); }
.sr-dot { width: 8px; height: 8px; border-radius: 50%; background: #22c55e; flex-shrink: 0; }
.sr-name { flex: 1; font-size: 14px; font-weight: 500; color: #1a1a2e; }
.sr-pct { font-size: 13px; font-weight: 700; color: #22c55e; }

/* ---- tips panel ---- */
.tips-panel {
  background: #fff; border-radius: 16px; padding: 20px 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.tips-panel-header {
  display: flex; align-items: flex-start; gap: 12px; margin-bottom: 18px;
  padding-bottom: 16px; border-bottom: 1px solid #f2f3f7;
}
.tips-panel-header h4 { margin: 0 0 2px; font-size: 16px; color: #1a1a2e; }
.tips-panel-sub { margin: 0; font-size: 12px; color: #909399; }
.tips-panel-icon { font-size: 28px; line-height: 1; }

.tips-cards { display: flex; flex-direction: column; gap: 10px; }
.tip-card {
  display: flex; gap: 14px; padding: 14px 16px; border-radius: 14px;
  position: relative; transition: all 0.2s ease;
  background: #fafbfc; border: 1px solid transparent;
}
.tip-card:hover { border-color: #eef0f4; box-shadow: 0 4px 12px rgba(0,0,0,0.04); transform: translateY(-1px); }
.tip-card.danger { background: linear-gradient(135deg, #fef2f2, #fff); }
.tip-card.warning { background: linear-gradient(135deg, #fffbeb, #fff); }
.tip-card.success { background: linear-gradient(135deg, #f0fdf4, #fff); }
.tip-card.info { background: linear-gradient(135deg, #eff2ff, #fff); }

.tip-card-step {
  width: 30px; height: 30px; border-radius: 10px; display: flex;
  align-items: center; justify-content: center; flex-shrink: 0;
  font-size: 13px; font-weight: 800; color: #fff;
  font-family: 'Georgia', 'Times New Roman', serif;
}
.tip-card.danger .tip-card-step { background: #ef4444; }
.tip-card.warning .tip-card-step { background: #e6a23c; }
.tip-card.success .tip-card-step { background: #22c55e; }
.tip-card.info .tip-card-step { background: #667eea; }

.tip-card-body { flex: 1; min-width: 0; }
.tip-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.tip-card-head strong { font-size: 14px; color: #1a1a2e; }
.tip-card-body p { margin: 0; font-size: 13px; color: #606266; line-height: 1.65; }

.tip-card-badge {
  font-size: 10px; padding: 2px 8px; border-radius: 8px; font-weight: 600;
  flex-shrink: 0; letter-spacing: 0.5px;
}
.tip-card-badge.danger { background: #fee2e2; color: #dc2626; }
.tip-card-badge.warning { background: #fef3c7; color: #b45309; }
.tip-card-badge.success { background: #dcfce7; color: #15803d; }
.tip-card-badge.info { background: #eef0ff; color: #4f46e5; }

/* ---- explainer panel ---- */
.explainer-panel {
  background: #fff; border-radius: 16px; padding: 20px 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.explainer-panel-header {
  display: flex; align-items: center; gap: 10px; margin-bottom: 18px;
  padding-bottom: 16px; border-bottom: 1px solid #f2f3f7;
}
.explainer-panel-header h4 { margin: 0; font-size: 16px; color: #1a1a2e; }
.explainer-panel-icon { font-size: 22px; }

.explainer-formula {
  padding: 14px 16px; border-radius: 12px;
  background: linear-gradient(135deg, rgba(102,126,234,0.06), rgba(118,75,162,0.03));
  margin-bottom: 18px;
}
.formula-line { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
.formula-label { font-size: 12px; font-weight: 600; color: #667eea; flex-shrink: 0; }
.formula-eq { font-size: 13px; font-weight: 500; color: #1a1a2e; font-family: 'SF Mono', 'Cascadia Code', 'Consolas', monospace; }

.explainer-rows { display: flex; flex-direction: column; gap: 12px; }
.explainer-row { display: flex; gap: 12px; align-items: flex-start; }
.er-dot { width: 10px; height: 10px; border-radius: 50%; margin-top: 4px; flex-shrink: 0; }
.er-info { flex: 1; }
.er-info strong { display: block; font-size: 13px; color: #1a1a2e; margin-bottom: 2px; }
.er-info code {
  display: inline-block; padding: 2px 8px; border-radius: 6px;
  background: #f8f9fe; color: #667eea; font-size: 12px; margin-bottom: 2px;
}
.er-note { display: block; font-size: 11px; color: #909399; }

@media (max-width: 800px) {
  .two-col { grid-template-columns: 1fr; }
  .profile-card { flex-direction: column; text-align: center; }
  .profile-divider { width: 48px; height: 1px; }
  .score-top { flex-direction: column; }
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

/* ---- wrong answer inline preview ---- */
.wrong-preview-head {
  display: flex; align-items: baseline; justify-content: space-between;
  margin-bottom: 12px;
}
.wrong-preview-head h3 { margin: 0; font-size: 16px; }
.wrong-preview-count { font-size: 12px; color: #909399; }

.wrong-preview-cards { display: flex; flex-direction: column; gap: 8px; }
.wrong-preview-card {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; border-radius: 12px; cursor: pointer;
  background: #fff; border: 1px solid #f0f2f5;
  transition: all 0.15s;
}
.wrong-preview-card:hover { border-color: #d0d5dd; box-shadow: 0 2px 6px rgba(0,0,0,0.04); }
.wpc-num {
  width: 24px; height: 24px; border-radius: 7px; display: flex;
  align-items: center; justify-content: center; flex-shrink: 0;
  background: #fef2f2; color: #dc2626; font-size: 11px; font-weight: 700;
  font-family: Georgia, serif;
}
.wpc-text {
  flex: 1; font-size: 13px; color: #1a1a2e; font-weight: 500;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.wpc-arrow { font-size: 14px; color: #c0c4cc; flex-shrink: 0; }

.wrong-preview-more {
  text-align: center; font-size: 13px; color: #667eea; font-weight: 600;
  padding: 10px 0 4px; cursor: pointer;
}
.wrong-preview-more:hover { color: #4a5dc7; }

@media (max-width: 768px) {
  .chart-row { grid-template-columns: 1fr; }
  .stat-card { min-width: 70px; }
}

/* ---- path / empty ---- */
.path-placeholder {
  background: #fafbfc; border: 1px dashed #dcdfe6; border-radius: 12px; padding: 20px;
}
.report-empty { padding: 32px 0; }
</style>
