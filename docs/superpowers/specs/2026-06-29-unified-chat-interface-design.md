# 统一聊天界面 (Unified Chat Interface) 设计文档

> **目标**: 将「智能辅导」「沉浸学习」融合为豆包式统一聊天界面，简化导航，精简交互。

**核心参考**: 豆包网页版 — 左侧会话列表 + 右侧聊天区 + 底部模式切换

---

## 1. 整体布局

```
┌─────────────────────────────────────────────────────────┐
│  [✦ 智学派]                              [头像 ▼]      │  ← 精简顶栏 48px
├────────────┬────────────────────────────────────────────┤
│ [+ 新对话] │                                            │
│            │  💬 聊天消息区                              │
│ 会话列表    │  - AI 回答 (含检索来源标签)                 │
│ 240px      │  - 用户消息                                │
│ 可折叠     │  - 生成资源 (文档/PPT/导图等)               │
│            │  - Agent 进度气泡                           │
│            ├────────────────────────────────────────────┤
│            │  [💬问答][📄文档][📊PPT][📝题库]           │
│            │  [🧩导图][💻代码][🌐课件]                   │  ← 7种模式
│            ├────────────────────────────────────────────┤
│            │  ┌──────────────────────────────┐ [▶发送]  │
│            │  │ 输入内容...                    │          │
│            │  └──────────────────────────────┘          │
└────────────┴────────────────────────────────────────────┘
```

---

## 2. 精简顶栏 + 头像下拉

### 顶栏
- 去掉原有 5 项导航栏
- 仅保留左侧 Logo「✦ 智学派」+ 右侧用户头像
- 高度从 64px 缩小到 48px
- 背景保持现有毛玻璃样式

### 头像下拉菜单
```
┌──────────────────────┐
│ 👤 个人中心           │  → /profile
│ 📚 私人智库           │  → /thinktank
│ 📊 资源推送           │  → /push
│ ─────────             │
│ 🚪 退出登录           │
└──────────────────────┘
```

导航栏原有的「私人智库」「资源推送」「个人中心」统一迁入头像下拉。

---

## 3. 左侧会话列表 (240px, 可折叠)

### 结构
```
┌──────────────────────┐
│ [+ 新对话]            │
│                       │
│ 🔍 搜索                │
│                       │
│ 今天                   │
│ · SVM原理总结          │
│ · 决策树代码实现        │
│                       │
│ 昨天                   │
│ · 线性回归推导          │
│                       │
│ 更早                   │
│ · Python入门           │
└──────────────────────┘
```

### 行为
- 每条会话标题：取该会话第一条用户消息的前 30 字
- 按天分组（今天/昨天/本周/更早）
- 当前会话高亮
- 悬停显示删除按钮
- 点击「+ 新对话」清空聊天区
- 搜索栏：本地过滤会话标题
- 数据来源：后端 Chroma 现有 `retrieveHistory()` 已存储所有 Q&A

### 后端改动
- 新增 API：`GET /api/tutor/conversations?studentId=xxx` 返回会话列表
- 新增 API：`GET /api/tutor/conversations/{id}/messages` 返回某会话消息
- Chroma metadata 已有 `conversationId` 字段，按此分组即可

---

## 4. 右侧聊天区 — 核心交互

### 4.1 模式切换栏

输入框上方一行圆角标签：

```
💬问答  📄文档  📊PPT  📝题库  🧩导图  💻代码  🌐课件
```

- 默认选中「问答」
- 选中态：紫色高亮 (`#667eea`)
- 切换时输入框 placeholder 自动变化
- 单选模式（一次只能选一种）

### 4.2 问答模式 (💬)

- 用户输入问题，调用现有 `POST /api/tutor/chat` API
- AI 回答以聊天气泡展示
- 气泡包含：内容 + 检索来源标签（①②③ 三个层级）+ 复制/重新生成按钮
- 完全继承现有 Tutoring 的后端逻辑（RAG → KG → Web 三级检索）

### 4.3 资源生成模式 (📄📊📝🧩💻🌐)

- 用户输入知识点/主题，调用现有资源生成 SSE API
- 发送后立即显示一个「Agent 进度卡片」气泡：
  ```
  ⚙️ 需求分析 → 🎨 内容设计 → ✍️ 生成中...
  ```
- 进度通过现有 SSE (`Flux<ServerSentEvent<ProgressEvent>>`) 实时推送
- 生成完成后，结果以「资源卡片」气泡展示：
  ```
  🤖 AI助教 · 📄 课程文档
  已为你生成「SVM支持向量机」课程文档
  [预览] [下载]
  ┌─ 文档内容渲染... ─┐
  └──────────────────┘
  ```
- 各资源类型的渲染保持不变：
  - DOC：KaTeX markdown 渲染
  - PPT：下载按钮
  - QUIZ：交互式答题卡片
  - MINDMAP：Mermaid SVG 渲染
  - CODE：Jupyter 式可编辑 + 运行 + 输出
  - HTML：内联预览 + 下载

### 4.4 消息气泡通用结构

**用户消息**: 右对齐，紫色背景气泡

**AI 消息**:
- 左对齐，白色背景气泡
- 问答模式显示来源标签
- 资源模式显示资源类型标签
- 底部工具栏：复制 / 重新生成 / 下载(资源)

---

## 5. 文件改动范围

### 新建
| 文件 | 职责 |
|------|------|
| `frontend/src/views/Chat.vue` | **统一聊天界面**（合并 Tutoring + Learning） |
| `frontend/src/components/ChatSidebar.vue` | 左侧会话列表组件 |
| `backend: ConversationController.java` | 会话列表 / 消息历史 API |

### 修改
| 文件 | 改动 |
|------|------|
| `frontend/src/views/MainLayout.vue` | 去除导航栏，顶栏精简，头像下拉增加菜单项 |
| `frontend/src/router/index.js` | 新增 `/chat` 路由；可保留 `/tutoring` `/learning` 重定向到 `/chat` |

### 保留不变
| 文件 | 说明 |
|------|------|
| `frontend/src/views/Home.vue` | 首页保持，作为 `/home` 默认页 |
| `frontend/src/views/ThinkTank.vue` | 私人智库，独立页面 |
| `frontend/src/views/ResourcePush.vue` | 资源推送，独立页面 |
| `frontend/src/views/Profile.vue` | 个人中心，独立页面 |
| `frontend/src/views/Login.vue` / `Register.vue` | 不变 |
| 全部后端 Service/Controller | 不变（Chat.vue 复用现有 API） |

### 可删除/废弃
| 文件 | 说明 |
|------|------|
| `frontend/src/views/Tutoring.vue` | 功能融入 Chat.vue |
| `frontend/src/views/Learning.vue` | 功能融入 Chat.vue |

---

## 6. 路由设计

```js
// 新路由
{ path: '/chat', component: Chat, meta: { requiresAuth: true } }

// MainLayout children 调整
children: [
  { path: 'home',      component: Home },       // 首页保留
  { path: 'chat',      component: Chat },        // 新增：统一聊天
  { path: 'thinktank', component: ThinkTank },    // 保留
  { path: 'push',      component: ResourcePush }, // 保留
  { path: 'profile',   component: Profile },      // 保留
  { path: 'profile/edit', component: EditProfile },
]
```

首页「开始智能辅导」按钮改为跳转 `/chat`。原 `/tutoring` 和 `/learning` 路径可做重定向或直接移除。

---

## 7. 颜色与风格

- 保持现有渐变主题色 `#667eea → #764ba2`
- 登录页完全不变
- 背景色 `#f5f7fb`
- 聊天气泡：用户紫色 / AI 白色
- 模式切换选中态：`#667eea` 紫色

---

## 8. 技术要点

1. **Chat.vue 需要管理两种调用路径**：
   - 问答模式 → `POST /api/tutor/chat`
   - 资源模式 → SSE `GET /api/resource/generate?topic=&type=...`（现有）
   通过 `activeMode` 变量切换

2. **会话列表 API**：
   - 现有 `ChromaClient` 存储每个 Q&A 带 `conversationId` metadata
   - 需新增后端接口：按 `conversationId` 分组聚合
   - 前端按 `conversationId` 分组消息流

3. **消息历史加载**：
   - 点击左侧历史会话 → `GET /api/tutor/conversations/{id}/messages` 拉取消息
   - 消息以时间序排列在聊天区
