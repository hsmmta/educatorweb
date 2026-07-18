<template>
  <div class="page-container">
    <!-- 页头 -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-icon-wrap">
          <span class="header-icon">📬</span>
        </div>
        <div>
          <h1>资源推送</h1>
          <p>个性化学习资源 — 系统推送 · 自主探索</p>
        </div>
      </div>
      <el-button :icon="Refresh" text rounded @click="refreshAll">刷新</el-button>
    </div>

    <!-- 双卡片区域 -->
    <div class="two-cards">
      <!-- ========== 左卡片：系统推送 ========== -->
      <section class="card push-card">
        <div class="card-title-row">
          <h3 class="card-title">{{ pushTitle }}</h3>
          <span v-if="latestPush" class="push-badge">NEW</span>
        </div>

        <div v-if="!latestPush" class="empty-state">
          <span class="empty-icon-wrap">📭</span>
          <p class="empty-title">暂无推送</p>
          <p class="empty-desc">继续对话学习,系统会自动为你整理话题</p>
        </div>

        <template v-else>
          <div v-for="(group, i) in visiblePushGroups" :key="i" class="push-group">
            <div class="push-group-head">
              <span class="pg-dot" :class="{ weak: group.isWeakness }"></span>
              <span class="pg-topic">{{ group.topic }}</span>
              <el-tag :type="group.isWeakness ? 'danger' : ''" size="small" effect="plain" round>
                {{ group.isWeakness ? '薄弱' : '话题' }}
              </el-tag>
            </div>
            <div class="push-resource-tags">
              <span
                v-for="(res, ri) in group.resources"
                :key="ri"
                class="res-tag"
                :data-type="res.resourceType"
                @click="goLearn(res, group.topic)"
              >
                <span class="res-tag-icon">{{ iconForType(res.resourceType) }}</span>
                {{ res.resourceTypeLabel || res.resourceType }}
              </span>
            </div>
          </div>

          <div
            v-if="allPushGroups.length > 2"
            class="fold-toggle"
            @click="pushExpanded = !pushExpanded"
          >
            <span class="fold-arrow" :class="{ open: pushExpanded }">▾</span>
            {{ pushExpanded ? '收起' : `展开 +${allPushGroups.length - 2} 个话题` }}
          </div>

          <div class="card-footer" @click="openPanel('history')">
            <span>查看推送历史</span>
            <span class="cf-arrow">→</span>
          </div>
        </template>
      </section>

      <!-- ========== 右卡片：自主探索（分类浏览） ========== -->
      <section class="card explore-card">
        <h3 class="card-title">🔍 自主探索</h3>

        <div class="card-body">
          <!-- 搜索框 -->
          <el-input
            v-model="kpFilterText"
            placeholder="过滤知识点..."
            clearable
            size="small"
            class="kp-filter"
            :prefix-icon="Search"
          />

          <!-- 分类横向标签 -->
          <div class="kp-cat-tabs" v-if="kpCategories.length">
            <span
              v-for="cat in kpCategories" :key="cat.name"
              :class="['kp-cat-tab', { active: activeCategory === cat.name }]"
              @click="activeCategory = cat.name"
            >
              {{ cat.name }}
              <span class="cat-count">{{ cat.points.length }}</span>
            </span>
          </div>

          <!-- 知识点网格 -->
          <div class="kp-grid" v-if="filteredPoints.length">
            <span
              v-for="kp in filteredPoints" :key="kp.id"
              class="kp-chip"
              :class="'diff-' + (kp.difficulty || 3)"
              @click="handleKpClick(kp)"
            >
              <span class="kp-chip-dot"></span>
              {{ kp.name }}
            </span>
          </div>

          <div v-else-if="kpCategories.length" class="empty-state">
            <span class="empty-icon-wrap">🔎</span>
            <p class="empty-desc">没有匹配 "{{ kpFilterText }}" 的知识点</p>
          </div>

          <div v-else class="empty-state">
            <span class="empty-icon-wrap">📚</span>
            <p class="empty-desc">知识点加载中...</p>
          </div>
        </div>
      </section>
    </div>

    <!-- ========== 学习路径（全宽） ========== -->
    <section class="card path-card">
      <div class="card-title-row">
        <h3 class="card-title">📐 我的学习路径</h3>
        <el-button v-if="savedPath" size="small" text @click="panelMode='search'">🔄 重新规划</el-button>
      </div>

      <div v-if="!savedPath" class="empty-state">
        <span class="empty-icon-wrap">🗺️</span>
        <p class="empty-title">暂未规划学习路径</p>
        <p class="empty-desc">搜索知识点，系统会为你智能规划最优学习路径</p>
        <el-button size="small" type="primary" round @click="panelMode='search'">
          搜索知识点规划路径 →
        </el-button>
      </div>

      <template v-else>
        <div class="path-summary-header">
          <div class="psh-left">
            <span class="psh-label">目标知识点</span>
            <strong>{{ savedPath.targetKnowledgePoint }}</strong>
          </div>
          <div class="psh-right">
            <div class="psh-stat">
              <span class="psh-stat-num">{{ savedPath.completedNodes || 0 }}</span>
              <span class="psh-stat-label">已完成</span>
            </div>
            <div class="psh-divider"></div>
            <div class="psh-stat">
              <span class="psh-stat-num">{{ savedPath.totalNodes }}</span>
              <span class="psh-stat-label">总节点</span>
            </div>
          </div>
        </div>

        <div class="path-flow">
          <div v-for="(node, i) in (savedPath.nodes || [])" :key="i" class="path-flow-row">
            <!-- connector line + dot -->
            <div class="pf-connector">
              <div class="pf-line" :class="{ filled: node.status === 'COMPLETED', active: node.status === 'CURRENT' }"></div>
              <div :class="['pf-dot', {
                done: node.status === 'COMPLETED',
                active: node.status === 'CURRENT',
                pending: node.status !== 'COMPLETED' && node.status !== 'CURRENT'
              }]">
                <span v-if="node.status === 'COMPLETED'">✓</span>
                <span v-else>{{ i + 1 }}</span>
              </div>
            </div>
            <!-- node content -->
            <div
              :class="['pf-node', {
                completed: node.status === 'COMPLETED',
                current: node.status === 'CURRENT',
                locked: !nodeClickable(node, i)
              }]"
              @click="nodeClickable(node, i) && goLearnFromPath(node)"
            >
              <div class="pf-node-main">
                <span class="pf-node-name">{{ node.knowledgePointName }}</span>
                <span :class="['pf-node-badge', node.status]">{{ statusLabel(node) }}</span>
              </div>
              <div class="pf-node-bar-wrap">
                <div class="pf-node-bar" :class="node.status" :style="{ width: Math.round((node.proficiency || 0) * 100) + '%' }"></div>
              </div>
              <span class="pf-node-pct">{{ Math.round((node.proficiency || 0) * 100) }}%</span>
            </div>
          </div>
        </div>
      </template>
    </section>

    <!-- ========== 底部面板 ========== -->
    <transition name="panel-slide">
      <div v-if="panelMode" class="bottom-panel">
        <div class="panel-header">
          <h3>{{ panelMode === 'search' ? '📐 学习路径 & 推荐资源' : '📋 推送历史' }}</h3>
          <el-button :icon="Close" text @click="closePanel" />
        </div>
        <div class="panel-body">

          <!-- 模式 1: 搜索结果 -->
          <template v-if="panelMode === 'search'">
            <div v-if="searchLoading" class="loading-area">
              <el-skeleton :rows="4" animated />
            </div>
            <template v-else-if="searchResult">
              <div class="search-result-layout">
                <div class="search-path">
                  <div class="sr-section-head">
                    <span class="sr-section-icon">📐</span>
                    <h4>学习路径</h4>
                  </div>
                  <div v-if="searchResult.learningPath?.nodes?.length">
                    <div class="path-summary">
                      <div class="ps-item">
                        <span class="ps-item-num">{{ searchResult.learningPath.totalNodes }}</span>
                        <span class="ps-item-label">节点</span>
                      </div>
                      <div class="ps-item">
                        <span class="ps-item-num">{{ searchResult.learningPath.completedNodes }}</span>
                        <span class="ps-item-label">已完成</span>
                      </div>
                      <div class="ps-item">
                        <span class="ps-item-num">{{ searchResult.learningPath.estimatedTotalDays }}</span>
                        <span class="ps-item-label">预计天数</span>
                      </div>
                    </div>
                    <el-timeline>
                      <el-timeline-item
                        v-for="(node, i) in searchResult.learningPath.nodes"
                        :key="i"
                        :timestamp="'第' + ((node.order || i) + 1) + '步'"
                        :color="statusColor(node.status)"
                        :hollow="node.status === 'PENDING'"
                      >
                        <strong>{{ node.knowledgePointName }}</strong>
                        <span class="path-desc">{{ node.description }}</span>
                        <div class="path-meta">
                          <el-tag size="small" type="info">难度 {{ '⭐'.repeat(node.difficulty || 1) }}</el-tag>
                        </div>
                      </el-timeline-item>
                    </el-timeline>
                  </div>
                  <div v-else class="empty-state"><p class="empty-desc">未找到相关知识点</p></div>
                </div>
                <div class="search-resources">
                  <div class="sr-section-head">
                    <span class="sr-section-icon">🎯</span>
                    <h4>推荐资源</h4>
                  </div>
                  <div v-if="searchResult.allRecommendations?.length" class="rec-list">
                    <div
                      v-for="(item, ri) in searchResult.allRecommendations"
                      :key="ri"
                      class="rec-item"
                      @click="goLearn(item, searchText)"
                    >
                      <span class="rec-type-icon">{{ iconForType(item.resourceType) }}</span>
                      <div class="rec-info">
                        <strong>{{ item.title }}</strong>
                        <span class="rec-meta">{{ item.reason || item.resourceType }}</span>
                      </div>
                      <el-button size="small" type="primary" round @click.stop="goLearn(item, searchText)">学习</el-button>
                    </div>
                  </div>
                  <div v-else class="empty-state"><p class="empty-desc">暂无推荐资源</p></div>
                </div>
              </div>
            </template>
            <div v-else class="empty-state">
              <span class="empty-icon-wrap">🔍</span>
              <p class="empty-title">未找到相关内容</p>
              <p class="empty-desc">尝试搜索其他知识点关键词</p>
            </div>
          </template>

          <!-- 模式 2: 推送历史 -->
          <template v-if="panelMode === 'history'">
            <div class="history-timeline">
              <div class="history-list-actions" v-if="pushHistory.length">
                <el-button size="small" type="danger" text @click="clearHistory">🗑 清空全部历史</el-button>
              </div>
              <div v-if="!pushHistory.length" class="empty-state">
                <span class="empty-icon-wrap">📋</span>
                <p class="empty-title">暂无推送记录</p>
                <p class="empty-desc">系统推送的资源将按日期归档在这里</p>
              </div>

              <div v-for="dateGroup in groupedHistory" :key="dateGroup.date" class="history-date-group">
                <div class="history-date-header">
                  <div class="hdh-left">
                    <span class="hdh-dot"></span>
                    <span class="date-label">{{ dateGroup.date }}</span>
                  </div>
                  <span class="date-count">{{ dateGroup.topics.length }} 个话题</span>
                </div>
                <div class="history-topic-cards">
                  <div v-for="(topic, ti) in dateGroup.topics" :key="ti" :class="['topic-card', { weakness: topic.isWeakness }]">
                    <div class="topic-card-left" :class="{ weak: topic.isWeakness }"></div>
                    <div class="topic-card-body">
                      <div class="topic-card-header">
                        <strong>{{ topic.topic }}</strong>
                        <span class="topic-card-time">{{ topic.time }}</span>
                      </div>
                      <div class="topic-card-meta">
                        <span class="topic-trigger">{{ topic.triggerLabel }}</span>
                        <span v-if="topic.isWeakness" class="topic-weak-badge">薄弱环节</span>
                      </div>
                      <div class="push-resource-tags">
                        <span
                          v-for="(res, ri) in topic.resources" :key="ri"
                          class="res-tag"
                          :data-type="res.resourceType"
                          @click="goLearn(res, topic.topic)"
                        >
                          <span class="res-tag-icon">{{ iconForType(res.resourceType) }}</span>
                          {{ res.resourceTypeLabel || res.resourceType }}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>

        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Close, Search } from '@element-plus/icons-vue'
import request from '../api/request.js'
import {
  getRecommendationsApi, getPushResultsApi, getLatestPushApi, getPushContextApi,
  clearPushHistoryApi, getKnowledgePointsApi, logBrowseApi
} from '../api/index.js'

// ---------- state ----------
const searchText = ref('')
const searchLoading = ref(false)
const searchResult = ref(null)
const panelMode = ref(null)

const latestPush = ref(null)
const pushExpanded = ref(false)
const pushHistory = ref([])
const selectedHistoryId = ref(null)

const recentTopics = ref([])
const weaknessTopics = ref([])

// ---- knowledge point browse ----
const kpCategories = ref([])          // [{name, points: [{id, name, difficulty}]}]
const activeCategory = ref('')
const kpFilterText = ref('')

const savedPath = ref(null)
const savedPathLoading = ref(false)

const loadSavedPath = async () => {
  savedPathLoading.value = true
  try {
    const res = await request.get(`/push/path/${getStudentId()}/saved`)
    const data = res.data?.data
    if (data?.exists) {
      savedPath.value = data.path
    } else {
      savedPath.value = null
    }
  } catch { savedPath.value = null }
  finally { savedPathLoading.value = false }
}

const goLearnFromPath = (node) => {
  const topic = node.knowledgePointName
  window.location.href = '/chat?topic=' + encodeURIComponent(topic) + '&mode=quiz'
}

const statusLabel = (node) => {
  if (node.status === 'COMPLETED') return '已掌握'
  if (node.status === 'CURRENT') return '学习中'
  return '待学习'
}

const nodeClickable = (node, index) => {
  if (node.status === 'COMPLETED') return true
  if (node.status === 'CURRENT') return true
  const nodes = savedPath.value?.nodes || []
  const currentIdx = nodes.findIndex(n => n.status === 'CURRENT')
  if (currentIdx >= 0 && index === currentIdx + 1) return true
  return false
}

const filteredPoints = computed(() => {
  const cat = kpCategories.value.find(c => c.name === activeCategory.value)
  if (!cat) return []
  const q = kpFilterText.value.trim().toLowerCase()
  if (!q) return cat.points
  return cat.points.filter(p => p.name.toLowerCase().includes(q))
})

// ---------- computed ----------
const allPushGroups = computed(() => {
  if (!latestPush.value?.resources) return []
  return latestPush.value.resources
})

const visiblePushGroups = computed(() => {
  if (pushExpanded.value) return allPushGroups.value
  return allPushGroups.value.slice(0, 2)
})

const pushTitle = computed(() => {
  if (!latestPush.value) return '📬 系统推送'
  return latestPush.value.triggerType === 'COUNT'
    ? '📬 今日推送 · 话题触发'
    : '📬 每日推送 · 定时推送'
})

const selectedHistory = computed(() =>
  pushHistory.value.find(r => r.id === selectedHistoryId.value) || null
)

// ---------- helpers ----------
function getStudentId() {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.id || 'anonymous'
  } catch { return 'anonymous' }
}

function iconForType(t) {
  const map = { DOC: '📄', PPT: '📊', QUIZ: '📝', CODE: '💻', VIDEO: '🎬', MINDMAP: '🧩', HTML: '🌐' }
  return map[t] || '📄'
}

function statusColor(status) {
  if (status === 'COMPLETED') return '#67c23a'
  if (status === 'CURRENT') return '#667eea'
  return '#dcdfe6'
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const diff = now - d
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

function formatDateLabel(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const diff = now - d
  if (diff < 86400000) return '今天'
  if (diff < 172800000) return '昨天'
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })
}

const groupedHistory = computed(() => {
  // Flatten all records into topic entries grouped by date
  const dateMap = new Map()
  for (const record of pushHistory.value) {
    const dateLabel = formatDateLabel(record.createdAt)
    if (!dateMap.has(dateLabel)) {
      dateMap.set(dateLabel, { date: dateLabel, topics: [] })
    }
    const group = dateMap.get(dateLabel)
    const resources = record.resources || []
    for (const r of resources) {
      group.topics.push({
        topic: r.topic,
        isWeakness: r.isWeakness,
        resources: r.resources || [],
        triggerLabel: record.triggerType === 'COUNT' ? '话题触发' : '定时推送',
        time: formatTime(record.createdAt)
      })
    }
  }
  return [...dateMap.values()]
})

function goLearn(res, topic) {
  if (res.preGeneratedId) {
    // Navigate to pre-generated resource content
    window.location.href = '/resource/' + res.preGeneratedId
  } else {
    // Fallback: go to generation page if no pre-generated content
    const t = topic || searchText.value
    const title = res.title || res.resourceTypeLabel || res.resourceType || ''
    window.location.href = '/learning?topic=' + encodeURIComponent(t + ' - ' + title)
  }
}

// ---------- knowledge point browse ----------
async function loadKnowledgePoints() {
  try {
    const res = await getKnowledgePointsApi()
    const data = res.data?.data
    if (data?.categories) {
      kpCategories.value = data.categories
      if (data.categories.length && !activeCategory.value) {
        activeCategory.value = data.categories[0].name
      }
    }
  } catch { /* ignore */ }
}

function handleKpClick(kp) {
  // Log browse event (fire-and-forget)
  logBrowseApi({ studentId: getStudentId(), concept: kp.name }).catch(() => {})
  searchText.value = kp.name
  handleSearch()
}

// ---------- search ----------
async function fetchSuggestions(queryString, cb) {
  if (!queryString || queryString.length < 1) { cb([]); return }
  try {
    const res = await getPushContextApi(getStudentId(), queryString)
    const suggestions = (res.data?.data?.suggestions || []).map(s => ({ value: s }))
    cb(suggestions)
  } catch { cb([]) }
}

async function handleSearch() {
  const q = searchText.value.trim()
  if (!q) return

  searchLoading.value = true
  panelMode.value = 'search'
  try {
    const res = await getRecommendationsApi(getStudentId(), q)
    searchResult.value = res.data?.data || null
    // Reload saved path (planPath() was called on backend, path is now persisted)
    await loadSavedPath()
  } catch (e) {
    ElMessage.error('搜索失败: ' + (e.response?.data?.message || e.message))
    searchResult.value = null
  } finally {
    searchLoading.value = false
  }
}

// ---------- panel ----------
function openPanel(mode) {
  if (mode === 'history') {
    panelMode.value = 'history'
    loadPushHistory()
  }
}

function closePanel() {
  panelMode.value = null
  selectedHistoryId.value = null
}

// ---------- data loading ----------
async function loadLatestPush() {
  try {
    const res = await getLatestPushApi(getStudentId())
    latestPush.value = res.data?.data || null
  } catch { latestPush.value = null }
}

async function loadPushHistory() {
  try {
    const res = await getPushResultsApi(getStudentId())
    pushHistory.value = res.data?.data || []
  } catch { pushHistory.value = [] }
}

async function loadContext() {
  try {
    const res = await getPushContextApi(getStudentId())
    const data = res.data?.data
    if (data) {
      recentTopics.value = data.recentTopics || []
      weaknessTopics.value = data.weaknessTopics || []
    }
  } catch {
    recentTopics.value = []
    weaknessTopics.value = []
  }
}

async function refreshAll() {
  await Promise.all([loadLatestPush(), loadContext()])
  ElMessage.success('已刷新')
}

async function clearHistory() {
  try {
    await clearPushHistoryApi(getStudentId())
    pushHistory.value = []
    latestPush.value = null
    selectedHistoryId.value = null
    panelMode.value = null
    ElMessage.success('推送历史已清空')
  } catch (e) {
    ElMessage.error('清空失败: ' + (e.response?.data?.message || e.message))
  }
}

// ---------- SSE push-refresh handler ----------
const refreshHandler = () => { loadLatestPush() }

onMounted(() => {
  loadLatestPush()
  loadContext()
  loadKnowledgePoints()
  loadSavedPath()
  window.addEventListener('push-refresh', refreshHandler)
})

onUnmounted(() => {
  window.removeEventListener('push-refresh', refreshHandler)
})
</script>

<style scoped>
.page-container { max-width: 1100px; margin: 0 auto; padding: 32px 24px 60px; }

/* ===== header ===== */
.page-header {
  display: flex; justify-content: space-between; align-items: center;
  background: linear-gradient(135deg, #fff 0%, #fafbff 50%, #f8f7ff 100%);
  padding: 24px 32px; border-radius: 20px; margin-bottom: 24px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04), 0 4px 16px rgba(102,126,234,0.06);
  position: relative; overflow: hidden;
}
.page-header::after {
  content: ''; position: absolute; top: -30px; right: -20px;
  width: 140px; height: 140px; border-radius: 50%;
  background: radial-gradient(circle, rgba(102,126,234,0.05) 0%, transparent 70%);
  pointer-events: none;
}
.header-left { display: flex; align-items: center; gap: 14px; position: relative; z-index: 1; }
.header-icon-wrap {
  width: 44px; height: 44px; border-radius: 14px; display: flex;
  align-items: center; justify-content: center;
  background: linear-gradient(135deg, rgba(102,126,234,0.1), rgba(118,75,162,0.06));
  flex-shrink: 0;
}
.header-icon { font-size: 22px; }
.page-header h1 { font-size: 22px; font-weight: 700; color: #1a1a2e; margin: 0 0 2px; }
.page-header p { font-size: 12px; color: #909399; margin: 0; }

/* ===== cards ===== */
.two-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; align-items: start; }
.card {
  background: #fff; border-radius: 18px; padding: 22px 24px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.03), 0 4px 14px rgba(0,0,0,0.04);
  display: flex; flex-direction: column;
  transition: box-shadow 0.2s;
}
.card:hover { box-shadow: 0 2px 6px rgba(0,0,0,0.04), 0 8px 24px rgba(0,0,0,0.06); }

.card-title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.card-title { font-size: 16px; font-weight: 700; color: #1a1a2e; margin: 0; }

.push-badge {
  font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 8px;
  background: linear-gradient(135deg, #667eea, #8b5cf6);
  color: #fff; letter-spacing: 0.5px; animation: badge-pulse 2s ease-in-out infinite;
}
@keyframes badge-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

/* ===== push groups ===== */
.push-group {
  padding: 12px 14px; border-radius: 12px; margin-bottom: 10px;
  background: linear-gradient(135deg, #fafbff, #f8f9fe);
  border: 1px solid rgba(102,126,234,0.08);
  transition: all 0.15s;
}
.push-group:hover { border-color: rgba(102,126,234,0.2); background: #f8f7ff; }
.push-group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.pg-dot {
  width: 8px; height: 8px; border-radius: 50%; background: #667eea; flex-shrink: 0;
}
.pg-dot.weak { background: #ef4444; animation: dot-blink 1.5s ease-in-out infinite; }
@keyframes dot-blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
.pg-topic { font-size: 14px; font-weight: 600; color: #1a1a2e; }

.push-resource-tags { display: flex; flex-wrap: wrap; gap: 6px; }

.res-tag {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 12px; border-radius: 8px; font-size: 12px; font-weight: 500;
  cursor: pointer; transition: all 0.15s;
  background: #fff; border: 1px solid #eef0f4; color: #4a4f5e;
}
.res-tag:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
}
.res-tag[data-type="DOC"]:hover { border-color: #667eea; color: #667eea; }
.res-tag[data-type="QUIZ"]:hover { border-color: #e6a23c; color: #e6a23c; }
.res-tag[data-type="HTML"]:hover { border-color: #22c55e; color: #22c55e; }
.res-tag[data-type="CODE"]:hover { border-color: #3b82f6; color: #3b82f6; }
.res-tag[data-type="PPT"]:hover { border-color: #f56c6c; color: #f56c6c; }
.res-tag[data-type="VIDEO"]:hover { border-color: #ec4899; color: #ec4899; }
.res-tag[data-type="MINDMAP"]:hover { border-color: #8b5cf6; color: #8b5cf6; }
.res-tag-icon { font-size: 14px; line-height: 1; }

.fold-toggle {
  text-align: center; font-size: 12px; color: #667eea; font-weight: 600;
  padding: 10px 0 4px; cursor: pointer; user-select: none;
  display: flex; align-items: center; justify-content: center; gap: 4px;
}
.fold-toggle:hover { color: #4a5dc7; }
.fold-arrow { display: inline-block; transition: transform 0.2s; font-size: 10px; }
.fold-arrow.open { transform: rotate(180deg); }

.card-footer {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  font-size: 13px; color: #667eea; font-weight: 600;
  padding-top: 14px; margin-top: auto; cursor: pointer;
  border-top: 1px solid #f2f3f7;
}
.card-footer:hover { color: #4a5dc7; }
.card-footer:hover .cf-arrow { transform: translateX(3px); }
.cf-arrow { transition: transform 0.2s; }

/* ===== explore ===== */
.explore-card .card-body { flex: 1; overflow: hidden; display: flex; flex-direction: column; }
.kp-filter { margin-bottom: 10px; flex-shrink: 0; }

.kp-cat-tabs {
  display: flex; flex-wrap: wrap; gap: 5px; margin-bottom: 10px; flex-shrink: 0;
}
.kp-cat-tab {
  padding: 4px 10px; border-radius: 20px; font-size: 11px; font-weight: 500;
  white-space: nowrap; cursor: pointer; transition: all 0.15s;
  background: #f2f3f7; color: #606266; border: 1px solid transparent;
  display: flex; align-items: center; gap: 4px;
}
.kp-cat-tab:hover { background: #eef0ff; color: #667eea; }
.kp-cat-tab.active { background: #667eea; color: #fff; border-color: #667eea; }
.cat-count {
  font-size: 10px; padding: 0 5px; border-radius: 8px;
  background: rgba(0,0,0,0.08); font-weight: 600; line-height: 16px;
}
.kp-cat-tab.active .cat-count { background: rgba(255,255,255,0.25); }

.kp-grid {
  flex: 1; overflow-y: auto; overflow-x: hidden;
  display: flex; flex-wrap: wrap; align-content: flex-start;
  gap: 6px; padding: 2px 0; max-height: 240px; min-height: 80px;
}
.kp-chip {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 4px 10px; border-radius: 7px; font-size: 12px; font-weight: 500;
  cursor: pointer; white-space: nowrap; transition: all 0.12s;
  background: #f8f9fe; color: #4a4f5e; border: 1px solid #eef0f4;
}
.kp-chip-dot {
  width: 5px; height: 5px; border-radius: 50%; flex-shrink: 0;
  background: #909399;
}
.kp-chip.diff-1 .kp-chip-dot { background: #22c55e; }
.kp-chip.diff-2 .kp-chip-dot { background: #22c55e; }
.kp-chip.diff-3 .kp-chip-dot { background: #e6a23c; }
.kp-chip.diff-4 .kp-chip-dot { background: #f97316; }
.kp-chip.diff-5 .kp-chip-dot { background: #ef4444; }
.kp-chip:hover { background: #667eea; color: #fff; border-color: #667eea; transform: translateY(-1px); }
.kp-chip:hover .kp-chip-dot { background: #fff; }

/* ===== empty states ===== */
.empty-state {
  text-align: center; padding: 28px 12px; flex: 1;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.empty-icon-wrap {
  width: 52px; height: 52px; border-radius: 16px; display: flex;
  align-items: center; justify-content: center; font-size: 24px;
  background: #f8f9fe; margin-bottom: 12px;
}
.empty-title { font-size: 14px; font-weight: 600; color: #1a1a2e; margin: 0 0 4px; }
.empty-desc { font-size: 12px; color: #909399; margin: 0; }

/* ===== path card ===== */
.path-card { margin-top: 24px; min-height: 180px; }
.path-summary-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 18px; margin-bottom: 16px;
  background: linear-gradient(135deg, rgba(102,126,234,0.04), rgba(118,75,162,0.02));
  border-radius: 14px; border: 1px solid rgba(102,126,234,0.08);
}
.psh-left { display: flex; flex-direction: column; gap: 2px; }
.psh-label { font-size: 11px; color: #909399; }
.psh-left strong { font-size: 15px; color: #1a1a2e; }
.psh-right { display: flex; align-items: center; gap: 16px; }
.psh-stat { text-align: center; }
.psh-stat-num { display: block; font-size: 20px; font-weight: 800; color: #667eea; line-height: 1; }
.psh-stat-label { font-size: 10px; color: #909399; }
.psh-divider { width: 1px; height: 28px; background: #eef0f4; }

/* path flow */
.path-flow { display: flex; flex-direction: column; }
.path-flow-row { display: flex; gap: 0; align-items: stretch; }
.pf-connector {
  display: flex; flex-direction: column; align-items: center;
  width: 32px; flex-shrink: 0; position: relative;
}
.pf-line {
  flex: 1; width: 2px; background: #eef0f4; min-height: 12px;
  transition: background 0.3s;
}
.pf-line.filled { background: #22c55e; }
.pf-line.active { background: linear-gradient(180deg, #22c55e, #667eea); }
.path-flow-row:first-child .pf-line { background: transparent; }
.pf-dot {
  width: 26px; height: 26px; border-radius: 50%; display: flex;
  align-items: center; justify-content: center; font-size: 11px; font-weight: 700;
  flex-shrink: 0; transition: all 0.3s; border: 2px solid #eef0f4;
  background: #fff; color: #909399;
}
.pf-dot.done { background: #22c55e; border-color: #22c55e; color: #fff; }
.pf-dot.active { background: #667eea; border-color: #667eea; color: #fff; animation: dot-glow 2s ease-in-out infinite; }
@keyframes dot-glow { 0%, 100% { box-shadow: 0 0 0 0 rgba(102,126,234,0.4); } 50% { box-shadow: 0 0 0 6px rgba(102,126,234,0); } }

.pf-node {
  flex: 1; display: flex; align-items: center; gap: 12px;
  padding: 10px 14px; margin: 3px 0 3px 8px; border-radius: 10px;
  border: 1px solid #f0f2f5; cursor: pointer; transition: all 0.15s;
}
.pf-node:hover:not(.locked) { border-color: #d0d5dd; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.pf-node.completed { background: #f0fdf4; border-color: #bbf7d0; }
.pf-node.current { background: linear-gradient(135deg, #eef0ff, #f8f7ff); border-color: #c7d2fe; }
.pf-node.locked { opacity: 0.35; cursor: not-allowed; pointer-events: none; }

.pf-node-main { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
.pf-node-name { font-size: 13px; font-weight: 600; color: #1a1a2e; }
.pf-node-badge {
  font-size: 10px; padding: 2px 7px; border-radius: 6px; font-weight: 600; flex-shrink: 0;
}
.pf-node-badge.COMPLETED { background: #dcfce7; color: #15803d; }
.pf-node-badge.CURRENT { background: #eef0ff; color: #4f46e5; }
.pf-node-badge.PENDING { background: #f2f3f7; color: #909399; }

.pf-node-bar-wrap { width: 80px; height: 5px; border-radius: 3px; background: #f2f3f7; overflow: hidden; flex-shrink: 0; }
.pf-node-bar { height: 100%; border-radius: 3px; transition: width 0.8s ease; }
.pf-node-bar.COMPLETED { background: #22c55e; }
.pf-node-bar.CURRENT { background: linear-gradient(90deg, #667eea, #8b5cf6); }
.pf-node-bar.PENDING { background: #e0e0e0; }
.pf-node-pct { font-size: 11px; font-weight: 600; color: #909399; width: 30px; text-align: right; flex-shrink: 0; }
.pf-node.current .pf-node-pct { color: #667eea; }

/* ===== bottom panel ===== */
.bottom-panel {
  margin-top: 24px; background: #fff; border-radius: 18px; overflow: hidden;
  box-shadow: 0 -2px 20px rgba(0,0,0,0.06), 0 2px 12px rgba(0,0,0,0.03);
}
.panel-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 24px; border-bottom: 1px solid #f2f3f7;
  background: linear-gradient(180deg, #fafbff, #fff);
}
.panel-header h3 { font-size: 16px; font-weight: 700; color: #1a1a2e; margin: 0; }
.panel-body { padding: 20px 24px; max-height: 520px; overflow-y: auto; }

.panel-slide-enter-active { transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1); }
.panel-slide-leave-active { transition: all 0.2s ease-in; }
.panel-slide-enter-from, .panel-slide-leave-to { opacity: 0; transform: translateY(20px); }

/* ===== search result ===== */
.search-result-layout { display: grid; grid-template-columns: 1fr 1fr; gap: 28px; }
.sr-section-head { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.sr-section-icon { font-size: 18px; }
.search-path h4, .search-resources h4, .sr-section-head h4 { font-size: 15px; font-weight: 700; color: #1a1a2e; margin: 0; }

.path-summary {
  display: flex; gap: 4px; padding: 14px 16px; margin-bottom: 16px;
  background: linear-gradient(135deg, #f8f7ff, #fafbff);
  border-radius: 12px; border: 1px solid rgba(102,126,234,0.08);
}
.ps-item { flex: 1; text-align: center; }
.ps-item-num { display: block; font-size: 20px; font-weight: 800; color: #667eea; }
.ps-item-label { font-size: 11px; color: #909399; }
.path-desc { font-size: 12px; color: #8890a0; display: block; margin: 2px 0; }
.path-meta { margin-top: 4px; }

.rec-list { display: flex; flex-direction: column; gap: 8px; }
.rec-item {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; border-radius: 12px; cursor: pointer;
  border: 1px solid #f0f2f5; transition: all 0.15s;
  background: #fafbfc;
}
.rec-item:hover { background: #f8f7ff; border-color: #c7d2fe; box-shadow: 0 2px 8px rgba(102,126,234,0.06); }
.rec-type-icon {
  width: 36px; height: 36px; border-radius: 10px; display: flex;
  align-items: center; justify-content: center; font-size: 18px;
  background: #f8f9fe; flex-shrink: 0;
}
.rec-info { flex: 1; min-width: 0; }
.rec-info strong { display: block; font-size: 13px; color: #1a1a2e; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rec-meta { font-size: 11px; color: #909399; }

/* ===== history ===== */
.history-timeline { max-height: 520px; overflow-y: auto; }
.history-list-actions { text-align: right; margin-bottom: 14px; }
.history-date-group { margin-bottom: 22px; }
.history-date-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px 0 10px; margin-bottom: 12px;
  border-bottom: 2px solid #667eea;
}
.hdh-left { display: flex; align-items: center; gap: 8px; }
.hdh-dot { width: 6px; height: 6px; border-radius: 50%; background: #667eea; }
.date-label { font-size: 14px; font-weight: 700; color: #1a1a2e; }
.date-count { font-size: 12px; color: #909399; }

.history-topic-cards { display: flex; flex-direction: column; gap: 10px; }
.topic-card {
  display: flex; border-radius: 12px; overflow: hidden;
  border: 1px solid #eef0f4; background: #fafbfc;
  transition: all 0.15s;
}
.topic-card:hover { border-color: #d0d5dd; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.topic-card.weakness { background: linear-gradient(135deg, #fef5f5, #fff); }
.topic-card-left {
  width: 3px; flex-shrink: 0; background: #667eea;
}
.topic-card-left.weak { background: #ef4444; }
.topic-card-body { flex: 1; padding: 14px 18px; min-width: 0; }
.topic-card-header { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
.topic-card-header strong { font-size: 14px; color: #1a1a2e; }
.topic-card-time { font-size: 11px; color: #c0c4cc; margin-left: auto; }
.topic-card-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.topic-trigger { font-size: 11px; color: #909399; }
.topic-weak-badge {
  font-size: 10px; padding: 1px 7px; border-radius: 6px;
  background: #fee2e2; color: #dc2626; font-weight: 600;
}

.loading-area { padding: 24px; }

/* ===== responsive ===== */
@media (max-width: 800px) {
  .two-cards, .search-result-layout { grid-template-columns: 1fr; }
  .pf-node { flex-wrap: wrap; }
}
</style>
