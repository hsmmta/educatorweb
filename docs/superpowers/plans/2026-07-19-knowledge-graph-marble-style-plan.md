# 知识图谱 Marble 风格改版 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 KnowledgeGraph.vue 从 ECharts 力导向图替换为基于 @antv/g6 v5 + dagre 的分层 DAG 可视化，实现 Marble 风格的交互体验。

**Architecture:** 纯前端改造，后端零改动。G6 Graph 实例挂载到 DOM ref，dagre 做分层布局（y 轴对应难度层级），自定义节点样式 + 交互行为（hover 高亮 / click 追溯前置链路 / 双击复位 / 搜索聚焦），侧边栏增强为树状前置链展示。

**Tech Stack:** Vue 3 + @antv/g6 v5 + dagre (built-in)

**Environment:** Node v22.17.0, npm 11.7.0, frontend dir: `frontend/`

---

### Task 1: 安装 G6 依赖

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 1: 安装 @antv/g6**

```bash
cd frontend && npm install @antv/g6@^5.0.0
```

- [ ] **Step 2: 验证安装**

```bash
node -e "const g6 = require('@antv/g6'); console.log('G6 version:', Object.keys(g6).slice(0, 5))"
```
Expected: 打印 `G6 version: [...]`，无报错。

- [ ] **Step 3: Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "deps: add @antv/g6 v5 for knowledge graph visualization"
```

---

### Task 2: G6 基础脚手架 — 替换 ECharts 力导向图为 dagre 分层图

**Files:**
- Modify: `frontend/src/views/KnowledgeGraph.vue`

把 ECharts 替换为 G6 基本实例：circle 节点 + dagre 布局 + 数据加载。

- [ ] **Step 1: 重写 `<script setup>` 核心逻辑**

替换 ECharts 导入和实例化为 G6：

```vue
<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Search, Refresh, Loading, Close, Upload, UploadFilled } from '@element-plus/icons-vue'
import { Graph, register, ExtensionCategory } from '@antv/g6'
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
  return categoryColors[cat] ?? fallbackColors[0]
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
        size: 16 + (n.difficulty ?? 1) * 5,  // 21–41px 直径
      },
    })),
    edges: edges.map(e => ({
      source: e.source,
      target: e.target,
      data: { relation: e.relation ?? 'RELATED_TO' },
    })),
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
      style: {
        stroke: '#b0b0b0',
        lineWidth: 1,
        opacity: 0.4,
      },
    },
    data,
    behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
  })

  graph.render()
}
```

- [ ] **Step 2: 重写 `<template>` 图区域 — 用 div 替代 vue-echarts**

```html
<template>
  <div class="kg-container">
    <!-- Toolbar (保留) -->
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

    <!-- Graph (替换 ECharts) -->
    <div class="kg-chart">
      <div v-if="loading" class="kg-loading">
        <el-icon class="is-loading" :size="36"><Loading /></el-icon>
        <p>加载知识图谱...</p>
      </div>
      <div v-else ref="graphRef" class="kg-g6-container"></div>
    </div>

    <!-- Node detail sidebar (保留) -->
    <transition name="slide">
      <div v-if="selectedNode" class="kg-sidebar">
        <!-- ... 侧边栏内容保留，待 Task 8 增强 ... -->
      </div>
    </transition>
  </div>

  <!-- Import dialog (保留不变) -->
  <!-- ... -->
</template>
```

- [ ] **Step 3: 更新样式 — 新增 `.kg-g6-container`**

在 `<style scoped>` 末尾新增：

```css
.kg-g6-container {
  width: 100%;
  height: 100%;
  min-height: 600px;
}
```

并移除不再需要的 ECharts 相关样式（如有）。

- [ ] **Step 4: 更新 `loadGraph` 函数**

```typescript
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

      initGraph(allNodes.value, allEdges.value)
    }
  } catch (e) {
    console.error('KnowledgeGraph: load failed:', e)
  } finally {
    loading.value = false
  }
}
```

- [ ] **Step 5: 更新 `onUnmounted`**

```typescript
onUnmounted(() => {
  graph?.destroy()
  graph = null
})
```

- [ ] **Step 6: 验证基本渲染**

```bash
cd frontend && npx vite build --mode development 2>&1 | tail -5
```
Expected: Build 成功，无 G6 相关 import 错误。

- [ ] **Step 7: Commit**

```bash
git add frontend/src/views/KnowledgeGraph.vue
git commit -m "feat: replace ECharts with G6 dagre layout in KnowledgeGraph"
```

---

### Task 3: 连线分类型样式

**Files:**
- Modify: `frontend/src/views/KnowledgeGraph.vue`

根据 relation 类型设置不同颜色的边，REQUIRES 带箭头。

- [ ] **Step 1: 在 `buildGraphData` 中为每条边添加样式**

```typescript
function buildGraphData(nodes, edges) {
  return {
    nodes: nodes.map(n => ({ /* ... 同 Task 2 ... */ })),
    edges: edges.map(e => {
      const rel = e.relation ?? 'RELATED_TO'
      return {
        source: e.source,
        target: e.target,
        data: { relation: rel },
        style: edgeStyle(rel),
      }
    }),
  }
}

const EDGE_STYLES = {
  'REQUIRES':   { stroke: '#e6a23c', lineWidth: 1.8, endArrow: true },
  'CONTAINS':   { stroke: '#667eea', lineWidth: 1.2, endArrow: false },
  'RELATED_TO': { stroke: '#b0b0b0', lineWidth: 0.8, endArrow: false, lineDash: [4, 4] },
}

function edgeStyle(relation) {
  return EDGE_STYLES[relation] ?? EDGE_STYLES['RELATED_TO']
}
```

- [ ] **Step 2: 更新 G6 `edge` 配置，使用数据中的 style**

```typescript
edge: {
  type: 'cubic-vertical',
  style: {
    stroke: d => d.style?.stroke ?? '#b0b0b0',
    lineWidth: d => d.style?.lineWidth ?? 0.8,
    opacity: 0.5,
    endArrow: d => d.style?.endArrow ?? false,
    lineDash: d => d.style?.lineDash ?? undefined,
  },
},
```

- [ ] **Step 3: 验证** — `npm run dev`，打开知识图谱页面，确认不同类型连线颜色不同，REQUIRES 有箭头。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/KnowledgeGraph.vue
git commit -m "feat: color-code edges by relation type in knowledge graph"
```

---

### Task 4: Hover 交互 — 高亮邻域

**Files:**
- Modify: `frontend/src/views/KnowledgeGraph.vue`

悬停节点时高亮该节点及其直接邻居，其余节点/边淡出。

- [ ] **Step 1: 在 `initGraph` 末尾绑定 hover 事件**

```typescript
function initGraph(nodes, edges) {
  // ... 图初始化代码 ...

  graph.render()

  // ---- Hover 交互 ----
  graph.on('node:pointerenter', (evt) => {
    const nodeId = evt.target.id
    // 获取直接邻居
    const neighbors = graph.getNeighborNodesData(nodeId)
    const highlightIds = new Set([nodeId, ...neighbors.map(n => n.id)])

    // 淡出非高亮节点
    graph.getNodeData().forEach(n => {
      if (!highlightIds.has(n.id)) {
        graph.setElementState({ [n.id]: 'dimmed' })
      }
    })
    // 淡出所有边（后续可优化为只保留邻边）
    graph.getEdgeData().forEach(e => {
      graph.setElementState({ [e.id]: 'dimmed' })
    })
  })

  graph.on('node:pointerleave', () => {
    // 恢复所有元素
    graph.getNodeData().forEach(n => {
      graph.setElementState({ [n.id]: 'active' })
    })
    graph.getEdgeData().forEach(e => {
      graph.setElementState({ [e.id]: 'active' })
    })
  })
}
```

- [ ] **Step 2: 在 `Graph` 配置中添加状态样式**

```typescript
graph = new Graph({
  // ... 其他配置 ...
  node: {
    type: 'circle',
    state: {
      dimmed: { opacity: 0.15 },
      active: { opacity: 0.9 },
    },
    // ... style ...
  },
  edge: {
    type: 'cubic-vertical',
    state: {
      dimmed: { opacity: 0.08 },
      active: { opacity: 0.5 },
    },
    // ... style ...
  },
})
```

- [ ] **Step 3: 验证** — 鼠标悬停节点，观察邻域高亮、其余淡出效果。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/KnowledgeGraph.vue
git commit -m "feat: hover highlight neighbors in knowledge graph"
```

---

### Task 5: Click 交互 — 追溯前置链路

**Files:**
- Modify: `frontend/src/views/KnowledgeGraph.vue`

点击节点时，BFS 沿 REQUIRES 向上游递归追溯所有前置节点，高亮整条路径。

- [ ] **Step 1: 实现 `bfsUpstream` 函数**

```typescript
/**
 * BFS 沿 relation 类型边向上游追溯，返回 {nodeId → depth} Map
 * @param {string} startId - 起始节点 ID
 * @param {string} relation - 边类型（通常为 'REQUIRES'）
 * @param {number} maxDepth - 最大追溯深度
 */
function bfsUpstream(startId, relation, maxDepth = 5) {
  const visited = new Map()  // nodeId → depth
  const queue = [[startId, 0]]
  visited.set(startId, 0)

  while (queue.length > 0) {
    const [current, depth] = queue.shift()
    if (depth >= maxDepth) continue

    // 找到所有指向 current 的 relation 边
    const parents = allEdges.value
      .filter(e => e.target === current && e.relation === relation)
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
```

- [ ] **Step 2: 绑定 `node:click` 事件**

```typescript
graph.on('node:click', (evt) => {
  const nodeId = evt.target.id

  // 追溯前置链路
  const upstream = bfsUpstream(nodeId, 'REQUIRES', 5)
  const upstreamIds = new Set(upstream.keys())

  // 高亮前置路径节点
  graph.getNodeData().forEach(n => {
    graph.setElementState({ [n.id]: upstreamIds.has(n.id) ? 'highlight' : 'dimmed' })
  })

  // 高亮属于前置路径的边
  graph.getEdgeData().forEach(e => {
    const isPathEdge = upstreamIds.has(e.source) && upstreamIds.has(e.target)
        && allEdges.value.some(ae => ae.source === e.source && ae.target === e.target && ae.relation === 'REQUIRES')
    graph.setElementState({ [e.id]: isPathEdge ? 'highlight' : 'dimmed' })
  })

  // 打开侧边栏（复用现有逻辑，增强内容见 Task 8）
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
      upstreamTree: buildUpstreamTree(upstream, nodeId),  // 见 Task 8
    }
  }
})
```

- [ ] **Step 3: 添加 `highlight` 状态样式**

```typescript
node: {
  state: {
    dimmed:    { opacity: 0.12 },
    active:    { opacity: 0.9 },
    highlight: { opacity: 1, lineWidth: 3, shadowBlur: 16, shadowColor: d => d.style?.fill ?? '#667eea' },
  },
},
edge: {
  state: {
    dimmed:    { opacity: 0.06 },
    active:    { opacity: 0.4 },
    highlight: { opacity: 0.9, lineWidth: 2.5 },
  },
},
```

- [ ] **Step 4: 绑定 `canvas:dblclick` 复位**

```typescript
graph.on('canvas:dblclick', () => {
  // 恢复全局状态
  graph.getNodeData().forEach(n => graph.setElementState({ [n.id]: 'active' }))
  graph.getEdgeData().forEach(e => graph.setElementState({ [e.id]: 'active' }))
  selectedNode.value = null
  graph.fitView({ animation: { duration: 500, easing: 'easeCubic' } })
})
```

- [ ] **Step 5: 验证** — 点击一个节点，确认其所有 REQUIRES 上游节点高亮，其余淡出；双击空白复位。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/KnowledgeGraph.vue
git commit -m "feat: click-to-trace prerequisite chain in knowledge graph"
```

---

### Task 6: 搜索聚焦 + 分类筛选

**Files:**
- Modify: `frontend/src/views/KnowledgeGraph.vue`

- [ ] **Step 1: 实现 `onSearch` — 匹配节点高亮 + 飞入聚焦**

```typescript
function onSearch(query) {
  if (!graph) return
  const q = (query ?? '').toLowerCase().trim()

  if (!q) {
    // 空搜索 → 恢复全部
    graph.getNodeData().forEach(n => graph.setElementState({ [n.id]: 'active' }))
    graph.getEdgeData().forEach(e => graph.setElementState({ [e.id]: 'active' }))
    graph.fitView({ animation: { duration: 500 } })
    return
  }

  const matched = allNodes.value.filter(n =>
    (n.name ?? '').toLowerCase().includes(q) || (n.id ?? '').toLowerCase().includes(q)
  )
  const matchedIds = new Set(matched.map(n => n.id))

  // 高亮匹配节点
  graph.getNodeData().forEach(n => {
    graph.setElementState({ [n.id]: matchedIds.has(n.id) ? 'highlight' : 'dimmed' })
  })
  graph.getEdgeData().forEach(e => {
    graph.setElementState({ [e.id]: 'dimmed' })
  })

  // 飞入第一个匹配节点
  if (matched.length > 0) {
    graph.focusItem(matched[0].id, { animation: { duration: 600, easing: 'easeCubic' } })
  }
}
```

- [ ] **Step 2: 实现 `onFilter` — 分类筛选**

```typescript
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
```

- [ ] **Step 3: 验证** — 搜索框输入关键词 → 匹配节点高亮 + 飞入动画；分类下拉切换 → 图重新渲染为筛选子集。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/KnowledgeGraph.vue
git commit -m "feat: search focus and category filter for knowledge graph"
```

---

### Task 7: 侧边栏增强 — 前置知识链树状展示

**Files:**
- Modify: `frontend/src/views/KnowledgeGraph.vue`

- [ ] **Step 1: 实现 `buildUpstreamTree` — 将 bfsUpstream 结果转为树状结构**

```typescript
/**
 * 将 BFS 结果 {nodeId → depth} 转为嵌套树，用于侧边栏渲染
 * 返回根节点（即起始节点自身）的直接前置子树
 */
function buildUpstreamTree(upstreamMap, startId) {
  if (!upstreamMap || upstreamMap.size === 0) return []

  // 按 depth 分组
  const byDepth = new Map()
  for (const [id, depth] of upstreamMap) {
    if (depth === 0) continue  // 跳过自身
    const list = byDepth.get(depth) ?? []
    list.push(id)
    byDepth.set(depth, list)
  }

  // 将节点按边连接到父节点
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
```

- [ ] **Step 2: 更新侧边栏模板 — 前置知识链用递归组件渲染**

在 `<script setup>` 中注册递归树组件：

```typescript
// 递归树节点组件
const TreeNode = {
  name: 'TreeNode',
  props: { node: Object },
  template: `
    <li>
      <span class="tree-node-name">{{ node.name }}</span>
      <ul v-if="node.children?.length">
        <TreeNode v-for="child in node.children" :key="child.id" :node="child" />
      </ul>
    </li>
  `
}
```

侧边栏 HTML 新增：

```html
<div class="sidebar-field" v-if="selectedNode.upstreamTree?.length">
  <span class="field-label">🔗 前置知识链（共 {{ upstreamCount }} 个）</span>
  <ul class="upstream-tree">
    <TreeNode v-for="node in selectedNode.upstreamTree" :key="node.id" :node="node" />
  </ul>
</div>
```

- [ ] **Step 3: 添加 `upstreamCount` 计算逻辑**

在 `node:click` 处理中计算：

```typescript
const upstreamCount = upstream.size - 1  // 减掉自身
// 存入 selectedNode:
selectedNode.value = {
  ...nodeData,
  prerequisites: prereqs,
  successors: succs,
  upstreamTree: buildUpstreamTree(upstream, nodeId),
  upstreamCount,
}
```

- [ ] **Step 4: 添加树样式**

```css
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
```

- [ ] **Step 5: 验证** — 点击一个有前置依赖的节点，侧边栏展示树状前置链；点击树中节点名可以跳转到该节点。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/KnowledgeGraph.vue
git commit -m "feat: tree-view prerequisite chain in knowledge graph sidebar"
```

---

### Task 8: 收尾 — 清理 ECharts 残留 & 导入功能适配

**Files:**
- Modify: `frontend/src/views/KnowledgeGraph.vue`

- [ ] **Step 1: 清理未使用的 ECharts 导入和代码**

移除 `<script setup>` 中的：
```typescript
// 删除以下行：
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([CanvasRenderer, GraphChart, TooltipComponent, LegendComponent])
```

移除 `<template>` 中的 `<v-chart>` 标签（已替换为 `<div ref="graphRef">`）。

- [ ] **Step 2: 适配导入后刷新**

`doImport` 函数保持现有逻辑不变，成功后的 `setTimeout(() => loadGraph(), 1000)` 会自动触发 G6 重渲染。

- [ ] **Step 3: 处理窗口 resize**

```typescript
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
```

- [ ] **Step 4: Build 验证**

```bash
cd frontend && npm run build 2>&1
```
Expected: `✓ built in ...` 无错误，无 ECharts graph chart 相关警告。

- [ ] **Step 5: 功能回归验收清单**

- [ ] 页面加载 → dagre 分层图正常渲染
- [ ] Hover 节点 → 邻域高亮，其余淡出
- [ ] 点击节点 → 前置链路高亮 + 侧边栏展示树状前置链
- [ ] 双击空白 → 复位
- [ ] 搜索 → 匹配节点高亮 + 飞入
- [ ] 分类筛选 → 图重新渲染
- [ ] 导入 → 刷新后图更新

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/KnowledgeGraph.vue
git commit -m "chore: remove ECharts graph residuals, add resize handler"
```

---

### 文件变更总览

| 文件 | 变更类型 | 任务 |
|------|---------|------|
| `frontend/package.json` | 修改 | Task 1 — 新增 `@antv/g6` |
| `frontend/src/views/KnowledgeGraph.vue` | 重写 | Task 2-8 — ECharts → G6 + 交互 + 侧边栏 |
| 其他文件 | 不动 | — |

### 验证方法

1. **构建验证**: `cd frontend && npm run build` — 确认无错误
2. **功能验证**: `npm run dev` → 浏览器打开 `/knowledge-graph` → 走一遍验收清单
3. **性能验证**: 加载 800 节点图，拖拽/缩放/交互应保持流畅（G6 WebGL 渲染器）
4. **兼容验证**: 确认 Profile.vue 等使用 ECharts 的页面不受影响（`echarts` 依赖保留）
