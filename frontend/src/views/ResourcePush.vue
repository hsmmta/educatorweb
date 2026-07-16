<template>
  <div class="page-container">
    <!-- 页头 -->
    <div class="page-header">
      <div class="header-left">
        <h1>📬 资源推送</h1>
        <p>个性化学习资源 — 系统推送 · 自主探索</p>
      </div>
      <el-button :icon="Refresh" text @click="refreshAll">刷新</el-button>
    </div>

    <!-- 双卡片区域 -->
    <div class="two-cards">
      <!-- ========== 左卡片：系统推送 ========== -->
      <section class="card push-card">
        <h3 class="card-title">{{ pushTitle }}</h3>

        <div v-if="!latestPush" class="empty-state">
          <span class="empty-icon">📭</span>
          <p>暂无推送 — 继续对话学习,系统会自动为你整理话题</p>
        </div>

        <template v-else>
          <div v-for="(group, i) in visiblePushGroups" :key="i" class="push-group">
            <div class="push-group-label">
              <el-tag :type="group.isWeakness ? 'danger' : 'primary'" size="small">
                {{ group.isWeakness ? '🔴' : '📐' }} {{ group.topic }}
              </el-tag>
            </div>
            <div class="push-resource-tags">
              <el-tag
                v-for="(res, ri) in group.resources"
                :key="ri"
                size="small"
                class="res-tag"
                @click="goLearn(res, group.topic)"
              >
                {{ iconForType(res.resourceType) }} {{ res.resourceTypeLabel || res.resourceType }}
              </el-tag>
            </div>
          </div>

          <div
            v-if="allPushGroups.length > 2"
            class="fold-toggle"
            @click="pushExpanded = !pushExpanded"
          >
            {{ pushExpanded ? '收起 ▲' : `展开 +${allPushGroups.length - 2} 个话题 ▾` }}
          </div>

          <div class="card-footer" @click="openPanel('history')">
            查看推送历史 →
          </div>
        </template>
      </section>

      <!-- ========== 右卡片：自主探索（分类浏览） ========== -->
      <section class="card explore-card">
        <h3 class="card-title">🔍 自主探索</h3>

        <div class="card-body">
          <!-- 搜索框：前端即时过滤 -->
          <el-input
            v-model="kpFilterText"
            placeholder="过滤知识点..."
            clearable
            size="small"
            class="kp-filter"
            :prefix-icon="Search"
          />

          <!-- 分类选择 -->
          <el-select
            v-model="activeCategory"
            size="small"
            class="kp-cat-select"
            v-if="kpCategories.length"
          >
            <el-option
              v-for="cat in kpCategories" :key="cat.name"
              :label="cat.name + ' (' + cat.points.length + ')'"
              :value="cat.name"
            />
          </el-select>

          <!-- 知识点列表（紧凑网格） -->
          <div class="kp-grid" v-if="filteredPoints.length">
            <span
              v-for="kp in filteredPoints" :key="kp.id"
              class="kp-chip"
              @click="handleKpClick(kp)"
              :title="kp.name + ' · 难度 ' + (kp.difficulty || 3)"
            >
              {{ kp.name }}
            </span>
          </div>

          <div v-else-if="kpCategories.length" class="empty-state">
            <p>没有匹配 "{{ kpFilterText }}" 的知识点</p>
          </div>

          <div v-else class="empty-state">
            <span class="empty-icon">📚</span>
            <p>知识点加载中...</p>
          </div>
        </div>
      </section>
    </div>

    <!-- ========== 学习路径（全宽） ========== -->
    <section class="card path-card">
      <h3 class="card-title">📐 我的学习路径</h3>

      <div v-if="!savedPath" class="empty-state">
        <span class="empty-icon">🗺️</span>
        <p>暂未规划学习路径</p>
        <el-button size="small" type="primary" @click="panelMode='search'">
          搜索知识点规划路径 →
        </el-button>
      </div>

      <template v-else>
        <div class="path-summary-header">
          <span>目标：<strong>{{ savedPath.targetKnowledgePoint }}</strong></span>
          <span>{{ savedPath.completedNodes || 0 }}/{{ savedPath.totalNodes }}</span>
        </div>

        <div class="path-node-list">
          <div v-for="(node, i) in (savedPath.nodes || [])" :key="i"
            :class="['path-node-item', {
              completed: node.status === 'COMPLETED',
              current: node.status === 'CURRENT',
              locked: !nodeClickable(node, i)
            }]"
            @click="nodeClickable(node, i) && goLearnFromPath(node)">
            <span class="path-node-status">
              {{ node.status === 'COMPLETED' ? '✅' : node.status === 'CURRENT' ? '🔵' : '⚪' }}
            </span>
            <span class="path-node-name">{{ node.knowledgePointName }}</span>
            <span class="path-node-progress" v-if="node.proficiency > 0">
              {{ Math.round(node.proficiency * 100) }}%
            </span>
            <span class="path-node-label">{{ statusLabel(node) }}</span>
          </div>
        </div>

        <el-button size="small" text @click="panelMode='search'" style="margin-top:12px">
          🔄 重新规划目标
        </el-button>
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
              <el-skeleton :rows="3" animated />
            </div>
            <template v-else-if="searchResult">
              <div class="search-result-layout">
                <div class="search-path">
                  <h4>📐 学习路径</h4>
                  <div v-if="searchResult.learningPath?.nodes?.length">
                    <div class="path-summary">
                      <span>共 <strong>{{ searchResult.learningPath.totalNodes }}</strong> 节点</span>
                      <span>已完成 <strong>{{ searchResult.learningPath.completedNodes }}</strong></span>
                      <span>预计 <strong>{{ searchResult.learningPath.estimatedTotalDays }}</strong> 天</span>
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
                  <div v-else class="empty-state"><p>未找到相关知识点</p></div>
                </div>
                <div class="search-resources">
                  <h4>🎯 推荐资源</h4>
                  <div v-if="searchResult.allRecommendations?.length">
                    <div
                      v-for="(item, ri) in searchResult.allRecommendations"
                      :key="ri"
                      class="rec-item"
                      @click="goLearn(item, searchText)"
                    >
                      <span class="rec-icon">{{ iconForType(item.resourceType) }}</span>
                      <div class="rec-info">
                        <strong>{{ item.title }}</strong>
                        <span class="rec-meta">{{ item.reason || item.resourceType }}</span>
                      </div>
                      <el-button size="small" type="primary" plain @click.stop="goLearn(item, searchText)">学习</el-button>
                    </div>
                  </div>
                  <div v-else class="empty-state"><p>暂无推荐资源</p></div>
                </div>
              </div>
            </template>
            <div v-else class="empty-state"><p>未找到相关内容</p></div>
          </template>

          <!-- 模式 2: 推送历史 -->
          <template v-if="panelMode === 'history'">
            <div class="history-layout">
              <div class="history-list">
                <div v-if="!pushHistory.length" class="empty-state"><p>暂无推送记录</p></div>
                <div v-else class="history-list-actions">
                  <el-button size="small" type="danger" text @click="clearHistory">
                    🗑 清空全部历史
                  </el-button>
                </div>
                <div
                  v-for="record in pushHistory" :key="record.id"
                  :class="['history-item', { active: selectedHistoryId === record.id }]"
                  @click="selectedHistoryId = record.id"
                >
                  <div class="history-item-header">
                    <el-tag size="small" :type="record.triggerType === 'COUNT' ? 'success' : 'warning'">
                      {{ record.triggerType === 'COUNT' ? '话题触发' : '定时推送' }}
                    </el-tag>
                    <span class="history-time">{{ formatTime(record.createdAt) }}</span>
                  </div>
                  <span class="history-count">{{ (record.resources || []).length }} 个话题</span>
                </div>
              </div>
              <div class="history-detail">
                <div v-if="!selectedHistory" class="empty-state"><p>选择左侧推送记录查看详情</p></div>
                <div v-else>
                  <div v-for="(group, gi) in selectedHistory.resources" :key="gi" class="history-group">
                    <div class="history-group-label">
                      <el-tag :type="group.isWeakness ? 'danger' : 'primary'" size="small">
                        {{ group.isWeakness ? '🔴' : '💬' }} {{ group.topic }}
                      </el-tag>
                    </div>
                    <div class="push-resource-tags">
                      <el-tag
                        v-for="(res, ri) in group.resources" :key="ri"
                        size="small" class="res-tag"
                        @click="goLearn(res, group.topic)"
                      >
                        {{ iconForType(res.resourceType) }} {{ res.resourceTypeLabel || res.resourceType }}
                      </el-tag>
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
  window.location.href = '/chat?topic=' + encodeURIComponent(topic) + '&mode=html'
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

/* ---- header ---- */
.page-header {
  display: flex; justify-content: space-between; align-items: center;
  background: #fff; padding: 24px 32px; border-radius: 20px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04); margin-bottom: 24px;
}
.page-header h1 { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0 0 4px; }
.page-header p { font-size: 13px; color: #8890a0; margin: 0; }

/* ---- two cards ---- */
.two-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
.card {
  background: #fff; border-radius: 20px; padding: 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
  display: flex; flex-direction: column;
  min-height: 280px;
}
.card-title { font-size: 16px; font-weight: 600; color: #1a1a2e; margin: 0 0 16px; }

/* ---- push ---- */
.push-group { margin-bottom: 14px; }
.push-group-label { margin-bottom: 8px; }
.push-resource-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.res-tag { cursor: pointer; transition: all 0.15s; }
.res-tag:hover { opacity: 0.8; transform: translateY(-1px); }

.fold-toggle {
  text-align: center; font-size: 13px; color: #667eea;
  padding: 10px 0; cursor: pointer; border-top: 1px dashed #e8e8e8;
  margin-top: 6px; user-select: none;
}
.fold-toggle:hover { color: #4a5dc7; }

.card-footer {
  text-align: center; font-size: 13px; color: #667eea;
  padding-top: 12px; margin-top: auto; cursor: pointer;
  border-top: 1px solid #f0f2f5;
}
.card-footer:hover { color: #4a5dc7; }

/* ---- explore ---- */
.explore-card .card-body { flex: 1; overflow: hidden; display: flex; flex-direction: column; }
.kp-filter { margin-bottom: 8px; }
.kp-cat-select { width: 100%; margin-bottom: 8px; flex-shrink: 0; }
.kp-grid {
  flex: 1; overflow-y: auto; overflow-x: hidden;
  display: flex; flex-wrap: wrap; align-content: flex-start;
  gap: 6px; padding: 4px 0; max-height: calc(100vh - 380px); min-height: 120px;
}
.kp-chip {
  display: inline-block; padding: 4px 10px; border-radius: 6px;
  font-size: 13px; cursor: pointer; white-space: nowrap;
  background: #f0f2f5; color: #303133; border: 1px solid #e8e8e8;
  transition: all 0.12s;
}
.kp-chip:hover { background: #667eea; color: #fff; border-color: #667eea; }

.search-row { display: flex; gap: 8px; margin-bottom: 16px; }
.search-input { flex: 1; }

.quick-tags { margin-bottom: 14px; }
.quick-label { font-size: 12px; font-weight: 600; color: #909399; display: block; margin-bottom: 6px; }
.quick-tag { cursor: pointer; margin-right: 6px; margin-bottom: 4px; }
.quick-tag:hover { opacity: 0.8; }
.weak-pct { font-size: 10px; opacity: 0.7; margin-left: 4px; }

.empty-state {
  text-align: center; padding: 24px 12px; flex: 1;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.empty-icon { font-size: 36px; margin-bottom: 8px; }
.empty-state p { font-size: 13px; color: #909399; margin: 0; }

/* ---- bottom panel ---- */
.bottom-panel {
  margin-top: 24px; background: #fff; border-radius: 20px;
  box-shadow: 0 -4px 24px rgba(0,0,0,0.06); overflow: hidden;
}
.panel-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 24px; border-bottom: 1px solid #f0f2f5;
}
.panel-header h3 { font-size: 16px; font-weight: 600; color: #1a1a2e; margin: 0; }
.panel-body { padding: 20px 24px; max-height: 480px; overflow-y: auto; }

.panel-slide-enter-active { transition: all 0.3s ease-out; }
.panel-slide-leave-active { transition: all 0.2s ease-in; }
.panel-slide-enter-from, .panel-slide-leave-to { opacity: 0; transform: translateY(16px); }

/* ---- search result ---- */
.search-result-layout { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
.search-path h4, .search-resources h4 { font-size: 15px; font-weight: 600; color: #1a1a2e; margin: 0 0 12px; }
.path-summary {
  display: flex; gap: 12px; padding: 10px 14px; margin-bottom: 14px;
  background: #f8f7ff; border-radius: 10px; font-size: 13px; color: #667eea;
}
.path-desc { font-size: 12px; color: #8890a0; display: block; margin: 2px 0; }
.path-meta { margin-top: 4px; }

.rec-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 14px; border-radius: 10px; cursor: pointer;
  border: 1px solid #f0f2f5; margin-bottom: 8px; transition: all 0.15s;
}
.rec-item:hover { background: #fafbff; border-color: #d0d5dd; }
.rec-icon { font-size: 24px; flex-shrink: 0; }
.rec-info { flex: 1; }
.rec-info strong { display: block; font-size: 14px; color: #1a1a2e; }
.rec-meta { font-size: 12px; color: #909399; }

/* ---- history ---- */
.history-layout { display: grid; grid-template-columns: 260px 1fr; gap: 24px; min-height: 280px; }
.history-list { border-right: 1px solid #f0f2f5; padding-right: 12px; overflow-y: auto; max-height: 420px; }
.history-list-actions { text-align: right; margin-bottom: 8px; }
.history-item {
  padding: 10px 14px; border-radius: 10px; cursor: pointer;
  border: 1px solid #f0f2f5; margin-bottom: 6px; transition: all 0.15s;
}
.history-item:hover, .history-item.active { background: #f0eeff; border-color: #667eea; }
.history-item-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.history-time { font-size: 12px; color: #909399; }
.history-count { font-size: 12px; color: #667eea; }
.history-group { margin-bottom: 16px; }
.history-group-label { margin-bottom: 8px; }

.loading-area { padding: 24px; }

@media (max-width: 800px) {
  .two-cards, .search-result-layout, .history-layout { grid-template-columns: 1fr; }
}

.path-card { margin-top: 24px; min-height: 200px; }
.path-summary-header {
  display: flex; justify-content: space-between;
  font-size: 13px; color: #909399; margin-bottom: 12px;
  padding-bottom: 10px; border-bottom: 1px solid #f0f2f5;
}
.path-node-list { display: flex; flex-direction: column; gap: 6px; }
.path-node-item {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border-radius: 10px;
  border: 1px solid #f0f2f5; cursor: pointer;
  transition: all 0.15s; font-size: 14px;
}
.path-node-item:hover:not(.locked) {
  background: #f0eeff; border-color: #667eea; transform: translateX(2px);
}
.path-node-item.completed { background: #f0f9eb; border-color: #c0e0b0; }
.path-node-item.current { background: #eef0ff; border-color: #667eea; font-weight: 600; }
.path-node-item.locked { opacity: 0.4; cursor: not-allowed; pointer-events: none; }
.path-node-status { font-size: 16px; width: 24px; text-align: center; }
.path-node-name { flex: 1; }
.path-node-label { font-size: 12px; color: #909399; }
</style>
