# 知识图谱可视化 — 设计文档

## 1. 目标

在系统中新增知识图谱可视化页面，用户可通过头像下拉菜单进入，直观浏览 Neo4j 中的知识点节点及其依赖关系。

## 2. 后端

### 2.1 新增 Controller

`KnowledgeGraphController.java` — `@RequestMapping("/api/knowledge-graph")`

**`GET /api/knowledge-graph/overview`**

返回所有知识点节点和关系的摘要数据，用于前端力导向图渲染。

```json
{
  "nodes": [
    {"id": "梯度下降", "name": "梯度下降", "category": "优化算法", "difficulty": 3},
    {"id": "损失函数", "name": "损失函数", "category": "基础概念", "difficulty": 2}
  ],
  "edges": [
    {"source": "梯度下降", "target": "损失函数", "relation": "REQUIRES"},
    {"source": "机器学习", "target": "监督学习", "relation": "CONTAINS"}
  ]
}
```

**实现**：
- 注入 `KnowledgePointRepository` 和 `Neo4jKnowledgeGraphService`
- 查询所有 `KnowledgePoint` 节点（限制 500 个防止过大）
- 按 category 分组，返回节点列表
- 查询 `REQUIRES`、`CONTAINS`、`RELATED_TO` 三种关系的边列表
- 关系去重（同一对节点之间的多条边合并为一条）

### 2.2 知识图谱已有数据

Neo4j 中已通过 MOOCCube 导入约 5000+ 知识点，包含 BELONGS_TO、CONTAINS、REQUIRES 等关系。直接复用现有 `KnowledgePointRepository` 查询。

## 3. 前端

### 3.1 新增页面

`frontend/src/views/KnowledgeGraph.vue`

**布局**：
- 全屏 ECharts 力导向图（占满整个页面，减去顶部导航栏 48px）
- 顶部工具栏：搜索框（高亮匹配节点）、分类图例、缩放按钮
- 点击节点弹出侧边栏：显示知识点名称、分类、难度、前置依赖、后继知识点

**ECharts 配置**：
```javascript
series: [{
  type: 'graph',
  layout: 'force',
  data: nodes.map(n => ({ name: n.name, category: n.category, symbolSize: 10 + n.difficulty * 3 })),
  links: edges.map(e => ({ source: e.source, target: e.target })),
  roam: true,           // 支持缩放和拖拽
  draggable: true,
  force: { repulsion: 200, edgeLength: [50, 200] },
  emphasis: { focus: 'adjacency' }  // 悬停高亮相邻节点
}]
```

颜色按 category 区分（分类用不同色调），难度越高节点越大。

### 3.2 路由

`router/index.js` 新增：
```javascript
{ path: 'knowledge-graph', component: () => import('../views/KnowledgeGraph.vue') }
```

### 3.3 导航入口

`MainLayout.vue` 头像下拉菜单 `<el-dropdown-menu>` 中新增一项：

```html
<el-dropdown-item divided command="knowledge-graph">
  <el-icon><Connection /></el-icon>知识图谱
</el-dropdown-item>
```

导入 `Connection` 图标，`handleCommand` 中新增 `'knowledge-graph'` → `router.push('/knowledge-graph')`。

## 4. 影响范围

| 文件 | 改动 |
|------|------|
| **新增** `KnowledgeGraphController.java` | 知识图谱查询 API |
| **新增** `KnowledgeGraph.vue` | 力导向图可视化页面 |
| `router/index.js` | 新增路由 |
| `MainLayout.vue` | 下拉菜单加入口 |

## 5. 风险

| 风险 | 对策 |
|------|------|
| 5000+ 节点力导向图性能差 | 默认只展示前 500 个节点，搜索可筛选；后续可加分页或按分类筛选 |
| 关系边过多导致图表混乱 | 合并同类型边，默认不显示 RELATED_TO，只显示 REQUIRES 和 CONTAINS |
