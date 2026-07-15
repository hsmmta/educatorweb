# 资源推送页面重新设计 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `/push` 页面从"需要手动输入"改造为"系统推送 + 自主探索"双卡片布局,首页新增"资源推送"按钮,SSE 推送刷新联动。

**Architecture:** 后端新增一个 `/api/push/context` 聚合接口(最近学过 + 薄弱点 + 搜索补全),前端 ResourcePush.vue 完全重写为双卡片 + 底部面板,SFC `<script setup>` + 页面级 ref/computed 管理状态。

**Tech Stack:** Vue 3 SFC, Element Plus (el-tabs/el-input/el-tag/el-badge/el-timeline), Spring Boot WebFlux + JPA, existing SSE 推送通道。

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/java/.../topicpush/api/PushContextController.java` | **Create** | 聚合 context API（最近学过 + 薄弱点 + 搜索补全） |
| `frontend/src/api/index.js` | **Modify** | 新增 `getPushContextApi`, `getAutocompleteApi` |
| `frontend/src/views/ResourcePush.vue` | **Rewrite** | 新双卡片 + 底部面板布局 |
| `frontend/src/views/Home.vue` | **Modify** | 两按钮 → 三按钮,加"资源推送" |
| `frontend/src/views/MainLayout.vue` | **Modify** | SSE 通知时若当前在 /push 则刷新页面数据 |

---

### Task 1: 后端 PushContextController — 聚合 context API

**Files:**
- Create: `src/main/java/org/example/educatorweb/topicpush/api/PushContextController.java`

**Why one endpoint instead of three:** 页面加载时"最近学过"+"薄弱点"两个数据是一次请求就能搞定的,搜索补全的候选列表也和它们共享 TopicCache 数据,拆成三个会多两个 HTTP 往返,不必要的延迟。

- [ ] **Step 1: 创建 PushContextController**

```java
package org.example.educatorweb.topicpush.api;

import org.example.educatorweb.dto.ResponseResult;
import org.example.educatorweb.knowledgegraph.model.KnowledgePoint;
import org.example.educatorweb.knowledgegraph.repository.KnowledgePointRepository;
import org.example.educatorweb.profile.model.StudentKnowledgeProficiency;
import org.example.educatorweb.profile.repository.StudentKnowledgeProficiencyRepository;
import org.example.educatorweb.topicpush.model.TopicCache;
import org.example.educatorweb.topicpush.repository.TopicCacheRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/push")
public class PushContextController {

    private final TopicCacheRepository topicCacheRepo;
    private final StudentKnowledgeProficiencyRepository kpProficiencyRepo;
    private final KnowledgePointRepository kpRepo;

    public PushContextController(TopicCacheRepository topicCacheRepo,
                                 StudentKnowledgeProficiencyRepository kpProficiencyRepo,
                                 KnowledgePointRepository kpRepo) {
        this.topicCacheRepo = topicCacheRepo;
        this.kpProficiencyRepo = kpProficiencyRepo;
        this.kpRepo = kpRepo;
    }

    /**
     * 聚合返回页面初始化和搜索补全所需的数据。
     * GET /api/push/context?studentId=xxx&q=搜索词（可选）
     */
    @GetMapping("/context")
    public ResponseResult<Map<String, Object>> getContext(
            @RequestParam String studentId,
            @RequestParam(required = false) String q) {

        // 1. 最近学过（TopicCache 中该用户最近 5 个不重复话题标签,按 endedAt 倒序）
        List<String> recentTopics = topicCacheRepo
            .findByUserIdOrderByEndedAtDesc(studentId, PageRequest.of(0, 50))
            .stream()
            .map(TopicCache::getTopicLabel)
            .distinct()
            .limit(5)
            .toList();

        // 2. 薄弱知识点（StudentKnowledgeProficiency 中熟练度 < 0.6 的前 5 个）
        List<Map<String, Object>> weaknessTopics = kpProficiencyRepo
            .findByStudentId(studentId)
            .stream()
            .filter(d -> d.getProficiency() != null
                && d.getProficiency().compareTo(new java.math.BigDecimal("0.6")) < 0)
            .sorted(Comparator.comparing(d -> d.getProficiency() != null
                ? d.getProficiency() : java.math.BigDecimal.ONE))
            .map(d -> Map.of(
                "concept", (Object) (d.getConcept() != null ? d.getConcept() : ""),
                "proficiency", (Object) (d.getProficiency() != null
                    ? d.getProficiency().toString() : "0")
            ))
            .toList();

        // 3. 搜索补全候选（如果传了 q）
        List<String> suggestions = List.of();
        if (q != null && !q.isBlank()) {
            // Neo4j 知识图谱节点名匹配
            List<String> kpNames = kpRepo.findAll().stream()
                .map(KnowledgePoint::getName)
                .filter(n -> n != null && n.toLowerCase().contains(q.toLowerCase()))
                .distinct()
                .collect(java.util.stream.Collectors.toList());

            // TopicCache 话题名匹配（用户自己的排前面）
            List<String> topicNames = topicCacheRepo
                .findByUserIdAndTopicLabelContainingIgnoreCase(studentId, q)
                .stream()
                .map(TopicCache::getTopicLabel)
                .distinct()
                .collect(java.util.stream.Collectors.toList());

            // 合并：用户自己的话题优先，图谱中其他知识点排后面（去重）
            Set<String> seen = new LinkedHashSet<>(topicNames);
            seen.addAll(kpNames);
            suggestions = new ArrayList<>(seen);
        }

        return ResponseResult.success(Map.of(
            "recentTopics", (Object) recentTopics,
            "weaknessTopics", (Object) weaknessTopics,
            "suggestions", (Object) suggestions
        ));
    }
}
```

- [ ] **Step 2: 补充 TopicCacheRepository 缺少的查询方法**

Modify: `src/main/java/org/example/educatorweb/topicpush/repository/TopicCacheRepository.java`

```java
// 新增两个查询方法到已有接口中（加到 class 方法声明区）：

/** 按用户 ID 和结束时间倒序获取话题缓存（用于"最近学过"） */
List<TopicCache> findByUserIdOrderByEndedAtDesc(String userId, PageRequest pageRequest);

/** 按用户 ID 和话题名模糊匹配（用于搜索补全） */
@Query("SELECT DISTINCT t.topicLabel FROM TopicCache t WHERE t.userId = :userId AND LOWER(t.topicLabel) LIKE LOWER(CONCAT('%', :q, '%'))")
List<String> findDistinctTopicLabelsByUserIdAndLabelContaining(String userId, String q);
```

但是 JPA 的 PageRequest 型参需要确认兼容性。更稳妥的写法：

```java
// 用 @Query 替代 Spring Data 方法名推导,避免参数解析歧义
@Query("SELECT t FROM TopicCache t WHERE t.userId = :userId ORDER BY t.endedAt DESC")
List<TopicCache> findRecentByUserId(@Param("userId") String userId, Pageable pageable);

@Query("SELECT DISTINCT t.topicLabel FROM TopicCache t WHERE t.userId = :userId AND LOWER(t.topicLabel) LIKE LOWER(CONCAT('%', :q, '%'))")
List<String> findTopicLabelsByUserIdAndQuery(@Param("userId") String userId, @Param("q") String q);
```

并在 Controller 中改为：
```java
List<String> recentTopics = topicCacheRepo
    .findRecentByUserId(studentId, PageRequest.of(0, 50))
    ...
```

和：
```java
List<String> topicNames = topicCacheRepo
    .findTopicLabelsByUserIdAndQuery(studentId, q);
List<String> kpNames = kpRepo.findAll().stream()
    .map(KnowledgePoint::getName)
    .filter(n -> n != null && n.toLowerCase().contains(q.toLowerCase()))
    .distinct()
    .collect(Collectors.toList());
```

- [ ] **Step 3: 编译验证**

Run: `export JAVA_HOME="/c/Users/x/.Neo4jDesktop2/Cache/runtime/zulu21.44.17-ca-jdk21.0.8-win_x64" && cd E:/educatorweb/educatorweb && mvn compile -q`
Expected: BUILD SUCCESS (无编译错误)

---

### Task 2: 前端 API 函数 — 新增 getPushContextApi

**Files:**
- Modify: `frontend/src/api/index.js:65-77`（在话题推送区域新增）

- [ ] **Step 1: 新增 API 函数**

在 `frontend/src/api/index.js` 的 "// ==================== 话题推送 (Topic Push) ====================" 区域,`getLatestPushApi` 之后新增:

```js
/** 获取页面上下文（最近学过 + 薄弱点 + 搜索补全） */
export const getPushContextApi = (studentId, q) =>
  request.get('/push/context', { params: { studentId, q } })
```

并且确认 `getRecommendationsApi` 正确使用 `/push/recommend/{studentId}` 路径,以及现有 `getPushResultsApi`、`getLatestPushApi` 已存在（行 72-77,不需要改动）。

---

### Task 3: 重写 ResourcePush.vue — 核心

**Files:**
- Rewrite: `frontend/src/views/ResourcePush.vue`

**设计说明:** 将整个文件替换为新的双卡片 + 底部面板布局。保留 `<script setup>` + composable 风格,和项目其他 Vue 组件一致。

- [ ] **Step 1: 创建新 ResourcePush.vue**

```vue
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
        <h3 class="card-title">
          {{ pushTitle }}
        </h3>

        <!-- 无推送 -->
        <div v-if="!latestPush" class="empty-state">
          <span class="empty-icon">📭</span>
          <p>暂无推送 — 继续对话学习,系统会自动为你整理话题</p>
        </div>

        <!-- 有推送 -->
        <template v-else>
          <div
            v-for="(group, i) in visiblePushGroups"
            :key="group.topic"
            class="push-group"
          >
            <div class="push-group-label">
              <el-tag :type="group.isWeakness ? 'danger' : 'primary'" size="small">
                {{ group.isWeakness ? '🔴' : '📐' }} {{ group.topic }}
              </el-tag>
            </div>
            <div class="push-resource-tags">
              <el-tag
                v-for="res in group.resources"
                :key="res.resourceType"
                class="res-tag"
                :class="{ 'is-weak': group.isWeakness }"
                size="small"
                @click="goLearn(res)"
              >
                {{ iconForType(res.resourceType) }} {{ res.resourceTypeLabel || res.resourceType }}
              </el-tag>
            </div>
          </div>

          <!-- 折叠 / 展开控制 -->
          <div
            v-if="allPushGroups.length > 2"
            class="fold-toggle"
            @click="pushExpanded = !pushExpanded"
          >
            {{ pushExpanded ? '收起 ▲' : `展开 +${allPushGroups.length - 2} 个话题 ▾` }}
          </div>

          <!-- 查看推送历史 -->
          <div class="card-footer" @click="openPanel('history')">
            查看推送历史 →
          </div>
        </template>
      </section>

      <!-- ========== 右卡片：自主探索 ========== -->
      <section class="card explore-card">
        <h3 class="card-title">🔍 自主探索</h3>

        <!-- 搜索框 + 自动补全 -->
        <div class="search-row">
          <el-autocomplete
            v-model="searchText"
            :fetch-suggestions="fetchSuggestions"
            placeholder="输入知识点名称..."
            :trigger-on-focus="false"
            clearable
            class="search-input"
            @select="handleSearch"
            @keyup.enter="handleSearch"
          >
            <template #default="{ item }">
              <span>{{ item.value }}</span>
            </template>
          </el-autocomplete>
          <el-button type="primary" @click="handleSearch" :loading="searchLoading">
            规划
          </el-button>
        </div>

        <!-- 最近学过 -->
        <div v-if="recentTopics.length" class="quick-tags">
          <span class="quick-label">📋 最近学过</span>
          <el-tag
            v-for="t in recentTopics"
            :key="t"
            size="small"
            class="quick-tag"
            @click="searchText = t; handleSearch()"
          >{{ t }}</el-tag>
        </div>

        <!-- 需要巩固 -->
        <div v-if="weaknessTopics.length" class="quick-tags">
          <span class="quick-label">⚠️ 需要巩固</span>
          <el-tag
            v-for="w in weaknessTopics"
            :key="w.concept"
            size="small"
            type="danger"
            class="quick-tag"
            @click="searchText = w.concept; handleSearch()"
          >
            {{ w.concept }}
            <span class="weak-pct">{{ Math.round(w.proficiency * 100) }}%</span>
          </el-tag>
        </div>

        <!-- 新用户：什么都没有 -->
        <div v-if="!recentTopics.length && !weaknessTopics.length" class="empty-state">
          <span class="empty-icon">🔍</span>
          <p>输入你想学的知识点,系统为你规划个性化学习路径</p>
        </div>
      </section>
    </div>

    <!-- ========== 底部面板（搜索 / 推送历史共用） ========== -->
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
                <!-- 路径时间线 -->
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
                        :timestamp="'第' + (node.order + 1) + '步'"
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
                <!-- 推荐资源 -->
                <div class="search-resources">
                  <h4>🎯 推荐资源</h4>
                  <div v-if="searchResult.allRecommendations?.length">
                    <div
                      v-for="item in searchResult.allRecommendations"
                      :key="item.title + item.resourceType"
                      class="rec-item"
                      @click="goLearn(item)"
                    >
                      <span class="rec-icon">{{ iconForType(item.resourceType) }}</span>
                      <div class="rec-info">
                        <strong>{{ item.title }}</strong>
                        <span class="rec-meta">{{ item.reason || item.resourceType }}</span>
                      </div>
                      <el-button size="small" type="primary" plain @click.stop="goLearn(item)">
                        学习
                      </el-button>
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
                <div v-if="!pushHistory.length" class="empty-state">
                  <p>暂无推送记录</p>
                </div>
                <div
                  v-for="record in pushHistory"
                  :key="record.id"
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
                <div v-if="!selectedHistory" class="empty-state">
                  <p>选择左侧推送记录查看详情</p>
                </div>
                <div v-else>
                  <div
                    v-for="(group, gi) in selectedHistory.resources"
                    :key="gi"
                    class="history-group"
                  >
                    <div class="history-group-label">
                      <el-tag :type="group.isWeakness ? 'danger' : 'primary'" size="small">
                        {{ group.isWeakness ? '🔴' : '💬' }} {{ group.topic }}
                      </el-tag>
                    </div>
                    <div class="push-resource-tags">
                      <el-tag
                        v-for="res in group.resources"
                        :key="res.resourceType"
                        size="small"
                        class="res-tag"
                        @click="goLearn(res)"
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
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Close } from '@element-plus/icons-vue'
import {
  getRecommendationsApi, getPushResultsApi, getLatestPushApi, getPushContextApi
} from '../api/index.js'

// ---------- state ----------
const searchText = ref('')
const searchLoading = ref(false)
const searchResult = ref(null)
const panelMode = ref(null)         // null | 'search' | 'history'

// push card
const latestPush = ref(null)
const pushExpanded = ref(false)
const pushHistory = ref([])
const selectedHistoryId = ref(null)

// explore card context
const recentTopics = ref([])
const weaknessTopics = ref([])
const suggestPool = ref([])        // 搜索补全候选列表

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
  const type = latestPush.value.triggerType
  return type === 'COUNT' ? '📬 今日推送 · 话题触发' : '📬 每日推送 · 定时推送'
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

function goLearn(res) {
  if (res.resourceType === 'HTML') {
    window.open('/learning?topic=' + encodeURIComponent(searchText.value), '_blank')
  } else {
    window.location.href = '/learning?topic=' + encodeURIComponent(searchText.value)
  }
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

// ---------- lifecycle ----------
onMounted(() => {
  loadLatestPush()
  loadContext()
})

// 监听 SSE 全局事件（MainLayout 触发）
// 使用自定义事件,MainLayout SSE onmessage 里 dispatch
window.addEventListener('push-refresh', () => {
  loadLatestPush()
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
  min-height: 300px;
}
.card-title { font-size: 16px; font-weight: 600; color: #1a1a2e; margin: 0 0 16px; }

/* ---- push card ---- */
.push-group { margin-bottom: 14px; }
.push-group-label { margin-bottom: 8px; }
.push-resource-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.res-tag { cursor: pointer; transition: all 0.15s; }
.res-tag:hover { opacity: 0.8; transform: translateY(-1px); }

.fold-toggle {
  text-align: center; font-size: 13px; color: #667eea;
  padding: 10px 0; cursor: pointer; border-top: 1px dashed #e8e8e8;
  margin-top: 6px;
  user-select: none;
}
.fold-toggle:hover { color: #4a5dc7; }

.card-footer {
  text-align: center; font-size: 13px; color: #667eea;
  padding-top: 12px; margin-top: auto; cursor: pointer;
  border-top: 1px solid #f0f2f5;
}
.card-footer:hover { color: #4a5dc7; }

/* ---- explore card ---- */
.search-row { display: flex; gap: 8px; margin-bottom: 16px; }
.search-input { flex: 1; }

.quick-tags { margin-bottom: 14px; }
.quick-label { font-size: 12px; font-weight: 600; color: #909399; display: block; margin-bottom: 6px; }
.quick-tag { cursor: pointer; margin-right: 6px; margin-bottom: 4px; }
.quick-tag:hover { opacity: 0.8; }
.weak-pct { font-size: 10px; opacity: 0.7; margin-left: 4px; }

.empty-state { text-align: center; padding: 32px 16px; flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.empty-icon { font-size: 40px; margin-bottom: 10px; }
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

/* panel slide animation */
.panel-slide-enter-active { transition: all 0.3s ease-out; }
.panel-slide-leave-active { transition: all 0.2s ease-in; }
.panel-slide-enter-from { opacity: 0; transform: translateY(20px); }
.panel-slide-leave-to { opacity: 0; transform: translateY(10px); }

/* ---- search result layout ---- */
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

/* ---- history layout ---- */
.history-layout { display: grid; grid-template-columns: 280px 1fr; gap: 24px; min-height: 300px; }
.history-list { border-right: 1px solid #f0f2f5; padding-right: 12px; }
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
  .two-cards { grid-template-columns: 1fr; }
  .search-result-layout { grid-template-columns: 1fr; }
  .history-layout { grid-template-columns: 1fr; }
}
</style>
```

- [ ] **Step 2: 构建验证**

Run: `cd E:/educatorweb/educatorweb/frontend && npm run build 2>&1 | tail -5`
Expected: `✓ built in XXXms`

---

### Task 4: Home.vue — 首页加"资源推送"按钮

**Files:**
- Modify: `frontend/src/views/Home.vue:22-32`

- [ ] **Step 1: 在两按钮间插入第三个按钮**

```html
<div class="hero-actions">
  <button class="btn-primary" @click="$router.push('/chat')">
    <span class="btn-icon">💬</span>
    智能辅导
  </button>
  <button class="btn-primary" @click="$router.push('/push')" style="background: linear-gradient(135deg, #764ba2, #667eea);">
    <span class="btn-icon">📬</span>
    资源推送
  </button>
  <button class="btn-secondary" @click="$router.push('/profile')">
    <span class="btn-icon">👤</span>
    个人中心
  </button>
</div>
```

- [ ] **Step 2: 构建验证**

Run: `cd E:/educatorweb/educatorweb/frontend && npm run build 2>&1 | tail -5`
Expected: `✓ built in XXXms`

---

### Task 5: MainLayout.vue — SSE 通知刷新 /push 页面

**Files:**
- Modify: `frontend/src/views/MainLayout.vue:78-90`

- [ ] **Step 1: 在 SSE onmessage 中增加 push-refresh 事件**

将当前的 `es.onmessage` 回调（行 78-89）替换为:

```js
es.onmessage = (event) => {
  try {
    const data = JSON.parse(event.data)
    pushNotificationCount.value++
    ElNotification({
      title: '资源推送',
      message: `已为你推送 ${data.resourceCount} 个学习资源（${data.triggerType === 'COUNT' ? '话题触发' : '定时推送'}）`,
      type: 'info',
      duration: 5000,
      onClick: goToPush
    })
    // 刷新 /push 页面（如果用户当前正在该页面）
    window.dispatchEvent(new CustomEvent('push-refresh'))
  } catch { /* ignore parse errors */ }
}
```

并且需要新增一行 import:

```js
import { useRoute } from 'vue-router'
const route = useRoute()
```

但这行改动不必要——`window.dispatchEvent` 不需要条件判断,因为 `ResourcePush.vue` 的 `onMounted` 里已经 `addEventListener('push-refresh', ...)`，而且在 `onUnmounted` 里也没移除此监听器。为了干净起见,在 ResourcePush.vue 的 `<script setup>` 里加一个 `onUnmounted`:

在 ResourcePush.vue 的 script 区域加:

```js
import { ref, computed, onMounted, onUnmounted } from 'vue'

const refreshHandler = () => { loadLatestPush() }

onMounted(() => {
  loadLatestPush()
  loadContext()
  window.addEventListener('push-refresh', refreshHandler)
})

onUnmounted(() => {
  window.removeEventListener('push-refresh', refreshHandler)
})
```

- [ ] **Step 2: 构建验证**

Run: `cd E:/educatorweb/educatorweb/frontend && npm run build 2>&1 | tail -5`
Expected: `✓ built in XXXms`

---

### Task 6: 整体验证 & 提交

- [ ] **Step 1: 前端构建**

Run: `cd E:/educatorweb/educatorweb/frontend && npm run build 2>&1 | tail -5`
Expected: `✓ built in XXXms`

- [ ] **Step 2: 后端编译**

Run: `export JAVA_HOME="...zulu21..." && cd E:/educatorweb/educatorweb && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 后端测试**

Run: `export JAVA_HOME="...zulu21..." && cd E:/educatorweb/educatorweb && mvn test`
Expected: Tests run: 33, Failures: 0, Errors: 0

- [ ] **Step 4: 提交所有改动**

```bash
git add .
git commit -m "live2d更新"
```
