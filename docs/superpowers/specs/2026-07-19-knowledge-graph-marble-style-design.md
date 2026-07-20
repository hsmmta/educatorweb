# 知识图谱 Marble 风格改版 — 设计文档

> **日期**: 2026-07-19
> **范围**: 纯前端改造，后端零改动
> **目标**: 将知识图谱页面从 ECharts 力导向图升级为 Marble 风格的分层 DAG 可视化

---

## 1. 目标

将 `KnowledgeGraph.vue` 从 ECharts 力导向图替换为基于 **antv/g6 v5 + dagre** 的分层有向无环图（DAG），实现与 [withmarble.com/curriculum](https://withmarble.com/curriculum/) 同等级别的视觉效果与交互体验。

### 核心变化

| 维度 | 当前（ECharts） | 目标（G6 + dagre） |
|------|----------------|-------------------|
| 布局 | 力导向（杂乱无结构） | dagre 分层 DAG（y 轴 = 难度层级） |
| 性能 | force 布局 800 节点吃力 | WebGL 渲染，1600+ 节点流畅 |
| 连线 | 统一灰色曲线 | 分关系类型着色 + 方向箭头 |
| 交互 | 点击看侧边栏 | 点击追溯完整前置链路 + 高亮路径 |
| 视觉 | 基础圆点 + 纯色 | 径向渐变节点 + 光环 + Marble 风格 |

---

## 2. 架构

### 2.1 改动范围

```
纯前端，后端零改动。

frontend/
├── package.json                     ← 新增 @antv/g6, @antv/dagre-layout 依赖
├── src/views/KnowledgeGraph.vue     ← 完全重写（ECharts → G6）
└── (其他文件不动)
```

不变的部分：
- 后端 `KnowledgeGraphController` API 完全不动
- 路由 `/knowledge-graph` 不动
- 工具栏（搜索、分类筛选、统计、导入）保留并复用
- 侧边栏保留并增强

### 2.2 组件树

```
KnowledgeGraph.vue
├── 工具栏 (保留) — 搜索框 · 分类筛选 · 节点/边统计 · 导入按钮 · 刷新
├── 图区域 — G6 Graph 实例挂载到 div ref
│   ├── 自定义节点: marble-node (Canvas 2D 径向渐变)
│   ├── 连线: cubic-vertical 贝塞尔曲线
│   └── 交互行为: hover 高亮 · click 追溯 · 双击复位
├── 侧边栏 (保留并增强)
│   ├── 节点详情（名称 · 分类 · 难度 · 简介）
│   └── 完整前置链路（递归 REQUIRES 上游，树状列表）
└── 导入对话框 (保留)
```

---

## 3. 新增依赖

```json
{
  "@antv/g6": "^5.0.0",
  "@antv/dagre-layout": "^1.0.0"
}
```

- `@antv/g6` — 图可视化引擎，内置 WebGL 渲染器、交互系统
- `@antv/dagre-layout` — 分层 DAG 布局算法（TB 方向，从上到下）

---

## 4. 核心实现

### 4.1 G6 实例化与生命周期

```typescript
import { Graph } from '@antv/g6'
import { DagreLayout } from '@antv/dagre-layout'

const graphRef = ref<HTMLDivElement>()
let graph: Graph | null = null

onMounted(async () => {
  await loadGraphData()  // GET /api/knowledge-graph/overview
  graph = new Graph({
    container: graphRef.value!,
    width: containerWidth,
    height: containerHeight,
    layout: {
      type: 'dagre',
      rankdir: 'TB',       // Top → Bottom
      nodesep: 40,         // 水平间距
      ranksep: 80,         // 垂直间距（层级间距）
    },
    node: {
      type: 'marble-node', // 自定义节点类型
      style: { size: 20 },
    },
    edge: {
      type: 'cubic-vertical',
      style: { stroke: '#ccc', lineWidth: 1, opacity: 0.4 },
    },
    data: transformData(nodes, edges),
    behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
    autoFit: 'view',
    animation: true,
  })
  graph.render()
  bindEvents()  // hover/click/dblclick 事件
})

onUnmounted(() => graph?.destroy())
```

### 4.2 数据转换

API 返回格式不变，仅做字段映射：

```typescript
function transformData(rawNodes, rawEdges) {
  return {
    nodes: rawNodes.map(n => ({
      id: n.id,
      data: {
        name: n.name,
        category: n.category,
        difficulty: n.difficulty ?? 1,
      },
      style: {
        fill: categoryColors[n.category],
        size: 12 + (n.difficulty ?? 1) * 4,
      },
    })),
    edges: rawEdges.map(e => ({
      source: e.source,
      target: e.target,
      data: { relation: e.relation },
      style: edgeStyle(e.relation),
    })),
  }
}

function edgeStyle(relation: string) {
  switch (relation) {
    case 'REQUIRES':    return { stroke: '#e6a23c', lineWidth: 1.5, endArrow: true }
    case 'CONTAINS':    return { stroke: '#667eea', lineWidth: 1, endArrow: false }
    case 'RELATED_TO':  return { stroke: '#909399', lineWidth: 0.8, endArrow: false, lineDash: [4, 4] }
    default:            return { stroke: '#909399', lineWidth: 0.8 }
  }
}
```

分类配色（与现有保持兼容）：

```typescript
const categoryColors: Record<string, string> = {
  '数学基础': '#667eea',
  '概念':     '#22c55e',
  '算法':     '#f56c6c',
  '应用':     '#3b82f6',
  '工具':     '#e6a23c',
}
```

### 4.3 自定义节点 `marble-node`

使用 G6 自定义节点 API，Canvas 2D 绘制：

- **主体**：径向渐变圆（中心亮 → 边缘暗），颜色按分类
- **外圈**：半透明圆环，`strokeWidth: 2`，`opacity: 0.3`，hover 时 `opacity: 0.8`
- **标签**：节点右侧 8px 处，`fontSize: 11`，深灰色 `#333`

```typescript
G6.register('node', 'marble-node', {
  draw: (cfg, group) => {
    const { size, fill } = cfg.style
    const r = size / 2

    // 径向渐变主体
    const gradient = new LinearGradient(...)
    const keyShape = group.addShape('circle', {
      attrs: {
        r,
        fill: gradient,
        shadowColor: fill,
        shadowBlur: 8,
        cursor: 'pointer',
      },
    })

    // 外圈光环
    group.addShape('circle', {
      attrs: {
        r: r + 3,
        stroke: fill,
        lineWidth: 2,
        opacity: 0.25,
      },
    })

    // 标签
    group.addShape('text', {
      attrs: {
        x: r + 8,
        y: 0,
        text: cfg.data.name,
        fontSize: 11,
        fill: '#333',
        textBaseline: 'middle',
      },
    })

    return keyShape
  },
})
```

### 4.4 交互行为

#### Hover 节点

```
graph.on('node:pointerenter', (e) => {
  const nodeId = e.target.id
  // 高亮当前节点 + 所有直接邻居（1 跳）
  const neighbors = graph.getNeighborNodesData(nodeId)
  highlightSet(nodeId, neighbors.map(n => n.id))
  // 其余节点/边 opacity → 0.15
})

graph.on('node:pointerleave', () => {
  // 恢复全局 opacity → 1
  resetHighlight()
})
```

#### 点击节点 — 追溯前置链路

```typescript
graph.on('node:click', (e) => {
  const nodeId = e.target.id
  // BFS 沿 REQUIRES 边向上游递归，最大深度 5 层
  const upstream = bfsUpstream(nodeId, 'REQUIRES', maxDepth = 5)
  // 高亮整条前置链路
  highlightPath(nodeId, upstream)
  // 打开侧边栏，展示完整前置链（树状列表）
  openSidebar(nodeId, upstream)
})
```

`bfsUpstream` 实现：

```typescript
function bfsUpstream(startId: string, relation: string, maxDepth: number): Map<string, number> {
  const visited = new Map<string, number>()  // nodeId → depth
  const queue: [string, number][] = [[startId, 0]]
  visited.set(startId, 0)

  while (queue.length > 0) {
    const [current, depth] = queue.shift()!
    if (depth >= maxDepth) continue
    const parents = allEdges
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

#### 双击空白 — 复位

```
graph.on('canvas:dblclick', () => {
  resetHighlight()
  closeSidebar()
  graph.fitView({ animation: { duration: 500, easing: 'easeCubic' } })
})
```

#### 搜索聚焦

现有搜索框行为升级：匹配节点高亮 + 飞入动画 + 其余淡出。

```typescript
function onSearch(query: string) {
  const matched = allNodes.filter(n =>
    n.name.toLowerCase().includes(query) || n.id.toLowerCase().includes(query)
  )
  if (matched.length === 0) return

  const ids = new Set(matched.map(n => n.id))
  highlightSet(ids)
  graph.focusItem(matched[0].id, { animation: { duration: 600 } })
}
```

### 4.5 侧边栏增强

现有侧边栏（名称、分类、难度、简介、前置/后继 tags）保留，新增：

```
┌─────────────────────────────┐
│ 支持向量机           ✕      │
│ [算法]  ⭐⭐⭐⭐              │
│                             │
│ 📝 简介                     │
│ 通过寻找最大间隔超平面...    │
│                             │
│ 🔗 前置知识链（共 8 个）     │  ← 新增
│ ├─ 线性代数                  │
│ │  ├─ 矩阵运算               │
│ │  └─ 向量空间               │
│ ├─ 优化理论                  │
│ │  └─ 拉格朗日乘子法          │
│ └─ 统计学习                  │
│    ├─ 经验风险最小化          │
│    └─ VC维理论               │
│                             │
│ → 后继知识                  │  ← 保留
│ [核方法] [SVR] [半监督SVM]   │
└─────────────────────────────┘
```

前置知识链通过 BFS 结果按深度分组渲染为树状结构，每层缩进 16px。

### 4.6 布局与难度映射

dagre 按拓扑分层后，每个节点的 y 坐标主要由其在前置链中的位置决定。额外微调：

```typescript
// dagre 分配层级后，同一层内按 difficulty 微调 y 偏移
// difficulty 高的节点在同层中靠上
const yOffset = (5 - node.difficulty) * 10  // difficulty 5 → y-0, difficulty 1 → y+40
```

这样天然形成"难度越高越靠上"的视觉流。

---

## 5. 工具栏保留

工具栏（搜索、分类筛选、节点/边统计、导入按钮、刷新）全部保留，API 和交互逻辑不变。改动仅为：

- 搜索输入触发 G6 的 `focusItem` + 高亮，而非 ECharts 的 `dispatchAction`
- 分类筛选触发 G6 的数据过滤 + 重新渲染
- 统计数字从 G6 的 `graph.getData()` 取值

---

## 6. 移除的内容

- `vue-echarts` 依赖（如无其他地方使用则移除，保留 echarts 因为 Profile.vue 等其他页面在用）
- ECharts graph chart 相关代码（`GraphChart`、`CanvasRenderer`、`TooltipComponent`、`LegendComponent`）
- `force` 布局相关配置

---

## 7. 文件变更清单

| 文件 | 改动 |
|------|------|
| `frontend/package.json` | 新增 `@antv/g6`、`@antv/dagre-layout` 依赖 |
| `frontend/src/views/KnowledgeGraph.vue` | 完全重写：ECharts → G6 + 自定义节点 + 交互 |

**不改动的文件**: 所有后端文件、路由、MainLayout、其他 Vue 页面。

---

## 8. 兼容性

### 8.1 数据兼容

API 返回格式不变（`{ nodes: [...], edges: [...] }`），仅在前端做 `transformData()` 映射。即使 Neo4j 数据量增长到 5000+ 节点，G6 WebGL 渲染器可保证 60fps。

### 8.2 浏览器兼容

G6 v5 支持 Chrome 90+、Edge 90+、Firefox 90+、Safari 15+。与项目现有的 `"not dead"` browserslist 一致。

### 8.3 现有的 ECharts 依赖

`echarts` 和 `vue-echarts` 保留，因为 `Profile.vue` 等其他页面使用 ECharts 雷达图等图表。仅 `KnowledgeGraph.vue` 不再使用 ECharts。

---

## 9. 限制与风险

| 风险 | 对策 |
|------|------|
| 5000+ 节点 dagre 计算耗时 | 首屏截断 800 节点，搜索 + 分类筛选缩小范围；dagre 对 800 节点布局在 100ms 内完成 |
| G6 v5 API 不稳定（beta 阶段） | 锁定 `^5.0.0` 版本，API 变动时仅需改导入路径 |
| 自定义节点复杂度高 | 先用内置 circle 节点跑通，再逐步迭代到 marble-node |
| 搜索聚焦飞入动画卡顿 | focusItem 动画限制 600ms，低端设备可关闭动画 |
| Vue 响应式与 G6 手动 DOM 冲突 | G6 实例完全通过 ref 管理，不经过 Vue 响应式系统 |
