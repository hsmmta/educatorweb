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
        <span class="kg-stats">{{ allNodes.length }} 节点 · {{ allEdges.length }} 边</span>
        <el-button size="small" type="primary" :icon="Upload" @click="showImport = true">导入</el-button>
        <el-button size="small" :icon="Refresh" @click="loadGraph">刷新</el-button>
      </div>
    </div>

    <!-- Graph -->
    <div class="kg-chart">
      <div v-if="loading" class="kg-loading">
        <el-icon class="is-loading" :size="36"><Loading /></el-icon>
        <p>加载知识图谱...</p>
      </div>
      <div v-else-if="allNodes.length" ref="graphRef" class="kg-g6-container"></div>
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
          <div class="sidebar-field" v-if="selectedNode.upstreamTree?.length">
            <span class="field-label">🔗 前置知识链（共 {{ selectedNode.upstreamCount }} 个）</span>
            <ul class="upstream-tree">
              <TreeNode v-for="node in selectedNode.upstreamTree" :key="node.id" :node="node" />
            </ul>
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

  <!-- Import dialog -->
  <el-dialog v-model="showImport" title="📤 导入知识图谱" width="520px" :close-on-click-modal="false">
    <div class="import-modes">
      <el-radio-group v-model="importMode" size="large">
        <el-radio-button value="text">📝 文本录入</el-radio-button>
        <el-radio-button value="file">📄 文件上传</el-radio-button>
        <el-radio-button value="dataset">📦 数据集</el-radio-button>
      </el-radio-group>
    </div>

    <div style="margin-top:20px">
      <template v-if="importMode === 'text'">
        <el-input v-model="importText" type="textarea" :rows="6"
          placeholder="输入知识点描述，如：反向传播是一种通过链式法则计算神经网络梯度的算法..." />
      </template>
      <template v-else-if="importMode === 'file'">
        <el-upload ref="uploadRef" drag :auto-upload="false" :limit="1"
          accept=".txt,.md,.json,.csv" @change="onFileChange">
          <el-icon :size="40"><UploadFilled /></el-icon>
          <div>拖拽文件到此处或点击上传</div>
          <template #tip><div class="el-upload__tip">支持 TXT/MD/JSON/CSV 文本文件</div></template>
        </el-upload>
      </template>
      <template v-else>
        <el-upload ref="datasetUploadRef" drag :auto-upload="false" :limit="1"
          accept=".json" @change="onDatasetChange">
          <el-icon :size="40"><UploadFilled /></el-icon>
          <div>上传知识图谱数据集 JSON</div>
          <template #tip><div class="el-upload__tip">支持JSON格式文件</div></template>
        </el-upload>
      </template>
    </div>

    <div v-if="importResult" class="import-result">
      <el-alert :title="importResult" type="success" :closable="false" show-icon />
    </div>

    <template #footer>
      <el-button @click="showImport = false">取消</el-button>
      <el-button type="primary" :loading="importLoading" @click="doImport">
        {{ importLoading ? '导入中...' : '开始导入' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { Search, Refresh, Loading, Close, Upload, UploadFilled } from '@element-plus/icons-vue'
import { Graph } from '@antv/g6'
import request from '@/api/request'

// ---- 响应式状态 ----
const loading = ref(true)
const searchText = ref('')
const filterCategory = ref('')
const selectedNode = ref(null)
const graphRef = ref(null)

const allNodes = ref([])
const allEdges = ref([])
const categories = ref([])

let graph = null

// ---- 分类配色 ----
const categoryColors = {
  '数学基础': '#667eea',
  '概念':     '#22c55e',
  '算法':     '#f56c6c',
  '应用':     '#3b82f6',
  '工具':     '#e6a23c',
}
const fallbackColors = ['#667eea', '#764ba2', '#3b82f6', '#22c55e', '#e6a23c', '#f56c6c', '#06b6d4', '#ec4899', '#8b5cf6', '#f97316']

function colorForCategory(cat) {
  if (categoryColors[cat]) return categoryColors[cat]
  const catIndex = categories.value.indexOf(cat)
  return catIndex >= 0 ? fallbackColors[catIndex % fallbackColors.length] : fallbackColors[0]
}

// ---- 边样式 ----
const EDGE_STYLES = {
  'REQUIRES':   { stroke: '#e6a23c', lineWidth: 1.8, endArrow: true },
  'CONTAINS':   { stroke: '#667eea', lineWidth: 1.2, endArrow: false },
  'RELATED_TO': { stroke: '#b0b0b0', lineWidth: 0.8, endArrow: false, lineDash: [4, 4] },
}

function edgeStyle(relation) {
  return EDGE_STYLES[relation] ?? EDGE_STYLES['RELATED_TO']
}

/**
 * BFS 沿 REQUIRES 边向上游追溯，返回 {nodeId → depth} Map
 */
function bfsUpstream(startId, maxDepth = 5) {
  const visited = new Map()  // nodeId → depth
  const queue = [[startId, 0]]
  visited.set(startId, 0)

  while (queue.length > 0) {
    const [current, depth] = queue.shift()
    if (depth >= maxDepth) continue

    // 找到所有指向 current 的 REQUIRES 边
    const parents = allEdges.value
      .filter(e => e.target === current && e.relation === 'REQUIRES')
      .map(e => e.source)

    for (const p of parents) {
      if (!visited.has(p)) {
        visited.set(p, depth + 1)
        queue.push([p, depth + 1])
      }
    }
  }
  return visited
}

/**
 * 将 BFS 结果 {nodeId → depth} 转为嵌套树，用于侧边栏渲染
 * 返回起始节点的直接前置子树（按深度分组）
 */
function buildUpstreamTree(upstreamMap, startId) {
  if (!upstreamMap || upstreamMap.size === 0) return []

  function childrenOf(parentId) {
    return allEdges.value
      .filter(e => e.relation === 'REQUIRES' && e.target === parentId && upstreamMap.has(e.source))
      .map(e => ({
        id: e.source,
        name: allNodes.value.find(n => n.id === e.source)?.name ?? e.source,
        children: childrenOf(e.source),
      }))
  }

  return childrenOf(startId)
}

// ---- 数据转换 ----
function buildGraphData(nodes, edges) {
  return {
    nodes: nodes.map(n => ({
      id: n.id,
      data: {
        name: n.name ?? n.id,
        category: n.category ?? '概念',
        difficulty: n.difficulty ?? 1,
      },
      style: {
        fill: colorForCategory(n.category),
        size: 16 + (n.difficulty ?? 1) * 5,
      },
    })),
    edges: edges.map(e => {
      const rel = e.relation ?? 'RELATED_TO'
      const style = edgeStyle(rel)
      return {
        id: `${e.source}-${e.target}-${rel}`,
        source: e.source,
        target: e.target,
        data: { relation: rel },
        style,
      }
    }),
  }
}

// ---- 图初始化 ----
function initGraph(nodes, edges) {
  if (graph) { graph.destroy(); graph = null }

  const data = buildGraphData(nodes, edges)

  graph = new Graph({
    container: graphRef.value,
    autoFit: 'view',
    animation: true,
    layout: {
      type: 'dagre',
      rankdir: 'TB',
      nodesep: 40,
      ranksep: 80,
    },
    node: {
      type: 'circle',
      state: {
        dimmed:    { opacity: 0.12 },
        active:    { opacity: 0.9 },
        highlight: { opacity: 1, lineWidth: 3, shadowBlur: 16 },
      },
      style: {
        fill: d => d.style?.fill ?? '#667eea',
        size: d => d.style?.size ?? 20,
        stroke: d => d.style?.fill ?? '#667eea',
        lineWidth: 1,
        opacity: 0.9,
        shadowColor: d => d.style?.fill ?? '#667eea',
        shadowBlur: 6,
      },
    },
    edge: {
      type: 'cubic-vertical',
      state: {
        dimmed:    { opacity: 0.06 },
        active:    { opacity: 0.5 },
        highlight: { opacity: 0.9, lineWidth: 2.5 },
      },
      style: {
        stroke: d => d.style?.stroke ?? '#b0b0b0',
        lineWidth: d => d.style?.lineWidth ?? 0.8,
        opacity: 0.5,
        endArrow: d => d.style?.endArrow ?? false,
        lineDash: d => d.style?.lineDash ?? undefined,
      },
    },
    data,
    behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
  })

  // ---- Node click handler — 追溯前置链路 ----
  graph.on('node:click', (evt) => {
    const nodeId = evt.target?.id
    if (!nodeId) return

    // 追溯前置链路
    const upstream = bfsUpstream(nodeId, 5)
    const upstreamIds = new Set(upstream.keys())

    // 高亮前置路径节点
    const allNodeData = graph.getNodeData()
    allNodeData.forEach(n => {
      graph.setElementState(n.id, upstreamIds.has(n.id) ? 'highlight' : 'dimmed')
    })

    // 高亮属于前置路径的 REQUIRES 边
    const allEdgeData = graph.getEdgeData()
    allEdgeData.forEach(e => {
      const isPathEdge = upstreamIds.has(e.source) && upstreamIds.has(e.target)
        && e.data?.relation === 'REQUIRES'
      graph.setElementState(e.id, isPathEdge ? 'highlight' : 'dimmed')
    })

    // 打开侧边栏
    const nodeData = allNodes.value.find(n => n.id === nodeId)
    if (nodeData) {
      const prereqs = allEdges.value
        .filter(e => e.target === nodeId && e.relation === 'REQUIRES')
        .map(e => e.source)
      const succs = allEdges.value
        .filter(e => e.source === nodeId && e.relation === 'REQUIRES')
        .map(e => e.target)
      selectedNode.value = {
        ...nodeData,
        prerequisites: prereqs,
        successors: succs,
        upstreamCount: upstream.size - 1,  // exclude self
        upstreamTree: buildUpstreamTree(upstream, nodeId),
      }
    }
  })

  // ---- Canvas click handler — dismiss sidebar ----
  graph.on('canvas:click', () => { selectedNode.value = null })

  // ---- Canvas double-click — 复位 ----
  graph.on('canvas:dblclick', () => {
    const allNodeData = graph.getNodeData()
    allNodeData.forEach(n => graph.setElementState(n.id, 'active'))
    const allEdgeData = graph.getEdgeData()
    allEdgeData.forEach(e => graph.setElementState(e.id, 'active'))
    selectedNode.value = null
    graph.fitView({ animation: { duration: 500, easing: 'easeCubic' } })
  })

  // ---- Hover 交互 ----
  graph.on('node:pointerenter', (evt) => {
    const nodeId = evt.target?.id
    if (!nodeId) return

    // 获取直接邻居
    const neighbors = graph.getNeighborNodesData(nodeId)
    const highlightIds = new Set([nodeId, ...neighbors.map(n => n.id)])

    // 淡出非高亮节点和所有边
    const allNodeData = graph.getNodeData()
    allNodeData.forEach(n => {
      graph.setElementState(n.id, highlightIds.has(n.id) ? 'active' : 'dimmed')
    })

    const allEdgeData = graph.getEdgeData()
    allEdgeData.forEach(e => {
      graph.setElementState(e.id, 'dimmed')
    })
  })

  graph.on('node:pointerleave', () => {
    // 恢复所有元素
    const allNodeData = graph.getNodeData()
    allNodeData.forEach(n => {
      graph.setElementState(n.id, 'active')
    })
    const allEdgeData = graph.getEdgeData()
    allEdgeData.forEach(e => {
      graph.setElementState(e.id, 'active')
    })
  })

  graph.render()
}

function onSearch(query) {
  if (!graph) return
  const q = (query ?? '').toLowerCase().trim()

  if (!q) {
    // 空搜索 → 恢复全部
    const allNodeData = graph.getNodeData()
    allNodeData.forEach(n => graph.setElementState(n.id, 'active'))
    const allEdgeData = graph.getEdgeData()
    allEdgeData.forEach(e => graph.setElementState(e.id, 'active'))
    graph.fitView({ animation: { duration: 500 } })
    return
  }

  const matched = allNodes.value.filter(n =>
    (n.name ?? '').toLowerCase().includes(q) || (n.id ?? '').toLowerCase().includes(q)
  )
  const matchedIds = new Set(matched.map(n => n.id))

  if (matchedIds.size === 0) return

  // 高亮匹配节点，淡出其余
  const allNodeData = graph.getNodeData()
  allNodeData.forEach(n => {
    graph.setElementState(n.id, matchedIds.has(n.id) ? 'highlight' : 'dimmed')
  })
  const allEdgeData = graph.getEdgeData()
  allEdgeData.forEach(e => {
    graph.setElementState(e.id, 'dimmed')
  })

  // 飞入第一个匹配节点
  graph.focusItem(matched[0].id, { animation: { duration: 600, easing: 'easeCubic' } })
}

function onFilter(cat) {
  if (!graph) return

  if (!cat) {
    // 显示全部 → 重新渲染完整数据
    initGraph(allNodes.value, allEdges.value)
    return
  }

  const filteredNodes = allNodes.value.filter(n => n.category === cat)
  const filteredIds = new Set(filteredNodes.map(n => n.id))
  const filteredEdges = allEdges.value.filter(e =>
    filteredIds.has(e.source) && filteredIds.has(e.target))

  initGraph(filteredNodes, filteredEdges)
}

// 递归树节点组件（用于侧边栏前置知识链）
const TreeNode = {
  name: 'TreeNode',
  props: { node: Object },
  template: `
    <li>
      <span class="tree-node-name">{{ node.name }}</span>
      <ul v-if="node.children && node.children.length">
        <TreeNode v-for="child in node.children" :key="child.id" :node="child" />
      </ul>
    </li>
  `
}

async function loadGraph() {
  loading.value = true
  try {
    const res = await request.get('/knowledge-graph/overview')
    const data = res.data?.data
    if (data) {
      allNodes.value = data.nodes || []
      allEdges.value = data.edges || []

      const catSet = new Set(allNodes.value.map(n => n.category))
      categories.value = [...catSet].sort()

      await nextTick()  // wait for v-else-if to mount graphRef
      initGraph(allNodes.value, allEdges.value)
    }
  } catch (e) {
    console.error('KnowledgeGraph: load failed:', e)
  } finally {
    loading.value = false
  }
}

// ---- import ----
const showImport = ref(false)
const importMode = ref('text')
const importText = ref('')
const importFile = ref(null)
const datasetFile = ref(null)
const importLoading = ref(false)
const importResult = ref('')

function onFileChange(file) { importFile.value = file.raw }
function onDatasetChange(file) { datasetFile.value = file.raw }

async function doImport() {
  importLoading.value = true
  importResult.value = ''
  try {
    const formData = new FormData()
    formData.append('mode', importMode.value)
    if (importMode.value === 'text') {
      formData.append('text', importText.value)
    } else if (importMode.value === 'file') {
      if (!importFile.value) { importResult.value = '请选择文件'; return }
      formData.append('file', importFile.value)
    } else {
      if (!datasetFile.value) { importResult.value = '请选择数据集文件'; return }
      formData.append('file', datasetFile.value)
    }
    const res = await request.post('/knowledge-graph/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const data = res.data?.data
    if (data?.success) {
      const conceptsAdded = data.conceptsAdded || data.knowledgePoints || 0
      const edgesAdded = data.edgesAdded || data.relationships || 0
      importResult.value = `导入成功！新增 ${conceptsAdded} 个知识点，${edgesAdded} 条关系`
      importText.value = ''
      importFile.value = null
      datasetFile.value = null
      setTimeout(() => loadGraph(), 1000) // refresh graph after import
    } else {
      importResult.value = '导入失败: ' + (data?.message || res.data?.message || '未知错误')
    }
  } catch (e) {
    importResult.value = '导入失败: ' + (e.response?.data?.message || e.message)
  } finally {
    importLoading.value = false
  }
}

function onResize() {
  if (!graphRef.value || !graph) return
  const { clientWidth, clientHeight } = graphRef.value
  if (clientWidth > 0 && clientHeight > 0) {
    graph.setSize(clientWidth, clientHeight)
  }
}

onMounted(() => {
  loadGraph()
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  graph?.destroy()
  graph = null
})
</script>

<style scoped>
.kg-container {
  height: calc(100vh - 48px);
  position: relative;
  overflow: hidden;
  background: #f5f7fb;
}

.kg-toolbar {
  position: absolute;
  top: 12px; left: 24px; right: 24px;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: rgba(255,255,255,0.92);
  backdrop-filter: blur(8px);
  border-radius: 12px;
  border: 1px solid rgba(0,0,0,0.06);
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
}
.kg-toolbar h2 { margin: 0; font-size: 16px; color: #1a1a2e; }
.kg-toolbar-right { display: flex; align-items: center; gap: 8px; }
.kg-stats { font-size: 12px; color: #909399; white-space: nowrap; }

.kg-chart {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  overflow: hidden;
}

.kg-loading, .kg-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #909399;
}

.kg-g6-container {
  width: 100%;
  height: 100%;
  min-height: 600px;
}

.kg-sidebar {
  position: absolute;
  top: 0; right: 0; bottom: 0;
  width: 300px;
  background: rgba(255,255,255,0.95);
  backdrop-filter: blur(8px);
  box-shadow: -2px 0 16px rgba(0,0,0,0.1);
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

.import-modes { text-align: center; }
.import-result { margin-top: 16px; }

.upstream-tree {
  list-style: none;
  padding-left: 0;
  margin: 8px 0 0;
}
.upstream-tree ul {
  padding-left: 16px;
  border-left: 1px solid #e0e0e0;
  margin-left: 4px;
}
.tree-node-name {
  display: inline-block;
  padding: 2px 8px;
  margin: 2px 0;
  font-size: 12px;
  color: #e6a23c;
  background: rgba(230, 162, 60, 0.08);
  border-radius: 4px;
  cursor: pointer;
}
.tree-node-name:hover {
  background: rgba(230, 162, 60, 0.18);
}
</style>
