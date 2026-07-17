<template>
  <div class="kg-container">
    <!-- Toolbar -->
    <div class="kg-toolbar">
      <h2>🧠 知识图谱</h2>
      <div class="kg-toolbar-right">
        <el-input v-model="searchText" placeholder="搜索知识点..." size="small" clearable
          style="width:240px" :prefix-icon="Search" @input="onSearch" />
        <el-select v-model="filterCategory" size="small" clearable placeholder="分类筛选" style="width:160px"
          @change="onFilter">
          <el-option v-for="cat in categories" :key="cat" :label="cat" :value="cat" />
        </el-select>
        <span class="kg-stats">{{ visibleNodes.length }} 节点 · {{ visibleEdges.length }} 边</span>
        <el-button size="small" :icon="Refresh" @click="loadGraph">刷新</el-button>
      </div>
    </div>

    <!-- Graph -->
    <div class="kg-chart" ref="chartRef">
      <div v-if="loading" class="kg-loading">
        <el-icon class="is-loading" :size="36"><Loading /></el-icon>
        <p>加载知识图谱...</p>
      </div>
      <v-chart v-else-if="chartOption" :option="chartOption" autoresize style="width:100%;height:100%" />
      <div v-else class="kg-error">
        <p>暂无数据，请检查 Neo4j 连接</p>
      </div>
    </div>

    <!-- Node detail sidebar -->
    <transition name="slide">
      <div v-if="selectedNode" class="kg-sidebar">
        <div class="sidebar-header">
          <h3>{{ selectedNode.name }}</h3>
          <el-button :icon="Close" text @click="selectedNode = null" />
        </div>
        <div class="sidebar-body">
          <div class="sidebar-field">
            <span class="field-label">分类</span>
            <el-tag size="small">{{ selectedNode.category }}</el-tag>
          </div>
          <div class="sidebar-field">
            <span class="field-label">难度</span>
            <span>{{ '⭐'.repeat(selectedNode.difficulty || 1) }}</span>
          </div>
          <div class="sidebar-field" v-if="selectedNode.description">
            <span class="field-label">简介</span>
            <p>{{ selectedNode.description }}</p>
          </div>
          <div class="sidebar-field" v-if="selectedNode.prerequisites?.length">
            <span class="field-label">前置依赖</span>
            <el-tag v-for="p in selectedNode.prerequisites" :key="p" size="small" type="warning"
              style="margin:2px">{{ p }}</el-tag>
          </div>
          <div class="sidebar-field" v-if="selectedNode.successors?.length">
            <span class="field-label">后继知识</span>
            <el-tag v-for="s in selectedNode.successors" :key="s" size="small" type="success"
              style="margin:2px">{{ s }}</el-tag>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Search, Refresh, Loading, Close } from '@element-plus/icons-vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import request from '@/api/request'

use([CanvasRenderer, GraphChart, TooltipComponent, LegendComponent])

const loading = ref(true)
const searchText = ref('')
const filterCategory = ref('')
const selectedNode = ref(null)
const chartRef = ref(null)

const allNodes = ref([])
const allEdges = ref([])
const categories = ref([])

const categoryColors = [
  '#667eea', '#764ba2', '#3b82f6', '#22c55e', '#e6a23c',
  '#f56c6c', '#06b6d4', '#ec4899', '#8b5cf6', '#f97316'
]

const visibleNodes = computed(() => {
  let nodes = allNodes.value
  if (filterCategory.value) {
    nodes = nodes.filter(n => n.category === filterCategory.value)
  }
  if (searchText.value) {
    const q = searchText.value.toLowerCase()
    nodes = nodes.filter(n => n.name.toLowerCase().includes(q) || n.id.toLowerCase().includes(q))
  }
  return nodes
})

const visibleNodeIds = computed(() => new Set(visibleNodes.value.map(n => n.id)))

const visibleEdges = computed(() => {
  return allEdges.value.filter(e =>
    visibleNodeIds.value.has(e.source) && visibleNodeIds.value.has(e.target))
})

const chartOption = computed(() => {
  if (!visibleNodes.value.length) return null

  return {
    tooltip: {
      formatter: (params) => {
        if (params.dataType === 'node') {
          const n = params.data
          return `<strong>${n.name}</strong><br/>分类: ${n.category}<br/>难度: ${'⭐'.repeat(n.difficulty || 1)}`
        }
        return `${params.data.source} → ${params.data.target}<br/>${params.data.relation || ''}`
      }
    },
    legend: { bottom: 0, data: categories.value },
    series: [{
      type: 'graph',
      layout: 'force',
      animation: true,
      data: visibleNodes.value.map(n => ({
        name: n.id,
        displayName: n.name,
        category: n.category,
        symbolSize: 8 + (n.difficulty || 1) * 4,
        itemStyle: { color: categoryColors[categories.value.indexOf(n.category) % categoryColors.length] },
        ...n
      })),
      links: visibleEdges.value.map(e => ({
        source: e.source,
        target: e.target,
        label: { show: false },
        lineStyle: {
          color: e.relation === 'REQUIRES' ? '#e6a23c' : e.relation === 'CONTAINS' ? '#667eea' : '#909399',
          width: e.relation === 'REQUIRES' ? 1.5 : 1,
          curveness: 0.1
        }
      })),
      categories: categories.value.map((c, i) => ({
        name: c,
        itemStyle: { color: categoryColors[i % categoryColors.length] }
      })),
      roam: true,
      draggable: true,
      force: { repulsion: 300, edgeLength: [60, 250], gravity: 0.1 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
      label: { show: true, fontSize: 10, formatter: p => p.data.displayName }
    }]
  }
})

function onSearch() {
  // reactive via computed
}

function onFilter() {
  // reactive via computed
}

async function loadGraph() {
  loading.value = true
  try {
    const res = await request.get('/knowledge-graph/overview')
    const data = res.data?.data
    if (data) {
      allNodes.value = data.nodes || []
      allEdges.value = data.edges || []

      // Extract unique categories
      const catSet = new Set(allNodes.value.map(n => n.category))
      categories.value = [...catSet].sort()
    }
  } catch (e) {
    console.error('KnowledgeGraph: load failed:', e)
  } finally {
    loading.value = false
  }
}

// Handle chart click to show node detail
const handleChartClick = (params) => {
  if (params.dataType === 'node') {
    const n = allNodes.value.find(x => x.id === params.name)
    if (n) {
      // Find prerequisites and successors from edges
      const prereqs = allEdges.value
        .filter(e => e.target === n.id && e.relation === 'REQUIRES')
        .map(e => e.source)
      const succs = allEdges.value
        .filter(e => e.source === n.id && e.relation === 'REQUIRES')
        .map(e => e.target)
      selectedNode.value = { ...n, prerequisites: prereqs, successors: succs }
    }
  } else {
    selectedNode.value = null
  }
}

onMounted(loadGraph)
</script>

<style scoped>
.kg-container {
  height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  background: #f5f7fb;
}

.kg-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  flex-shrink: 0;
}
.kg-toolbar h2 { margin: 0; font-size: 18px; color: #1a1a2e; }
.kg-toolbar-right { display: flex; align-items: center; gap: 10px; }
.kg-stats { font-size: 12px; color: #909399; }

.kg-chart {
  flex: 1;
  position: relative;
}

.kg-loading, .kg-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #909399;
}

.kg-sidebar {
  position: absolute;
  top: 0;
  right: 0;
  width: 300px;
  height: 100%;
  background: #fff;
  box-shadow: -2px 0 12px rgba(0,0,0,0.08);
  z-index: 10;
  overflow-y: auto;
}
.sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #f0f2f5;
}
.sidebar-header h3 { margin: 0; font-size: 16px; }
.sidebar-body { padding: 16px; }
.sidebar-field { margin-bottom: 14px; }
.field-label { display: block; font-size: 12px; color: #909399; margin-bottom: 4px; }
.sidebar-field p { margin: 4px 0; font-size: 13px; color: #5b6270; line-height: 1.6; }

.slide-enter-active, .slide-leave-active { transition: transform 0.25s ease; }
.slide-enter-from, .slide-leave-to { transform: translateX(100%); }
</style>
