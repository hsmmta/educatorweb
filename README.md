<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.4.3-brightgreen?logo=springboot" />
  <img src="https://img.shields.io/badge/Vue-3.x-4FC08D?logo=vuedotjs" />
  <img src="https://img.shields.io/badge/Neo4j-5.x-4581C3?logo=neo4j" />
  <img src="https://img.shields.io/badge/Docker-Ready-2496ED?logo=docker" />
  <img src="https://img.shields.io/badge/License-MIT-blue" />
</p>

<h1 align="center">智引未来</h1>
<h3 align="center">基于可信知识图谱与多智能体编排的个性化导学平台</h3>

<p align="center">
  <b>高等教育个性化学习资源体系 &nbsp;·&nbsp; 软件杯赛题作品</b>
</p>

---

## 项目简介

智引未来是一个面向高等教育的个性化多智能体学习系统。以大模型技术为核心引擎，融合知识图谱、向量检索、多模态生成等前沿 AI 技术，构建了"感知→生成→推送→评估→再感知"的闭环学习体系。

系统以学生画像为中心，通过自研的 DAG 编排引擎调度七个专业智能体协同工作，为每位学生提供从知识诊断、资源生成、路径规划到智能辅导的个性化学习服务。

---

## 核心功能

| 功能 | 说明 |
|------|------|
| 📊 **知识图谱构建与检索** | 10,126 个 ML 知识节点，4,600+ 前置依赖关系，力导向图可视化 |
| 🧠 **用户画像构建** | 六维画像（知识基础/认知风格/易错偏好/学习步调/内容偏好/目标导向） |
| 🎬 **多模态资源生成** | 7 种类型（文档/题库/代码/课件/思维导图/PPT/视频），7 Agent 并行编排 |
| 🗺️ **个性化路径规划** | 知识图谱驱动 BFS 拓扑排序，画像感知资源推荐 |
| 📈 **多维评估可视化** | 五维评估雷达图，掌握度追踪，成长趋势分析 |
| 💬 **智能即时辅导** | 三级检索增强（私人智库→知识图谱→联网搜索），流式 SSE 输出 |

---

## 技术架构

```
┌─────────────────────────────────────────────┐
│              前端 Nginx + Vue 3              │
│      Element Plus · ECharts · KaTeX · Live2D │
└─────────────────┬───────────────────────────┘
                  │ /api/*
┌─────────────────▼───────────────────────────┐
│           Spring Boot 3.4 (WebFlux)          │
│   Auth · Profile · Tutor · Push · KG · RAG  │
│         7 Agent DAG Orchestrator             │
└───────┬─────────┬─────────┬────────┬────────┘
        │         │         │        │
   ┌────▼──┐ ┌───▼──┐ ┌───▼───┐ ┌▼──────┐
   │ MySQL │ │Neo4j │ │Qdrant │ │Chroma │
   └───────┘ └──────┘ └───────┘ └───────┘
```

---

## 一键部署

### 环境要求

- Docker Desktop 4.x+
- 内存 ≥ 8 GB

### 快速启动

```bash
# 1. 克隆项目
git clone https://github.com/hsmmta/educatorweb.git
cd educatorweb

# 2. 配置 API Key
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY

# 3. 导入 Docker 镜像（提交版）
docker load -i docker-images/educatorweb-backend.tar
docker load -i docker-images/educatorweb-frontend.tar

# 4. 启动所有服务
docker compose -f docker-compose.export.yml up -d

# 5. 导入 Qdrant 向量数据（首次）
docker compose exec backend python /app/data/import_qdrant.py

# 6. 打开浏览器 http://localhost
```

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| 前端 | `80` | 用户界面 |
| 后端 API | `8080` | Spring Boot REST API |
| MySQL | `3306` | 用户数据、画像、错题 |
| Neo4j Browser | `7474` | 知识图谱浏览器 |
| Neo4j Bolt | `7687` | 知识图谱连接 |
| Qdrant | `6333` | RAG 向量检索 |
| Chroma | `8000` | 对话记忆 |

### 常用命令

```bash
# 查看日志
docker compose logs -f backend

# 停止服务
docker compose down

# 重置所有数据
docker compose down -v && docker compose up -d

# 导入示例用户数据
docker compose exec -T mysql mysql -uroot -proot educatorweb < db/example_user.sql
```

---

## 项目结构

```
educatorweb/
├── frontend/                 # Vue 3 前端
│   ├── src/views/            # 页面组件
│   ├── src/components/       # 通用组件
│   └── src/api/              # API 封装
├── src/                      # Spring Boot 后端
│   └── main/java/.../
│       ├── aitutor/          # AI 辅导模块
│       ├── profile/          # 用户画像模块
│       ├── resourcegen/      # 资源生成模块
│       ├── learningpath/     # 学习路径模块
│       ├── knowledgegraph/   # 知识图谱模块
│       ├── rag/              # RAG 检索模块
│       └── topicpush/        # 资源推送模块
├── data/                     # 预置数据
│   ├── neo4j-data/           # 知识图谱数据库
│   ├── qdrant_*.json         # 向量检索数据
│   └── mysql-init.sql        # MySQL 初始化脚本
├── docker-compose.export.yml # Docker 编排（提交用）
├── docker-images/            # 导出镜像 tar 包
├── .env.example              # 环境变量模板
└── README.md
```

---

## 评测指标

| 指标 | 数值 |
|------|------|
| 知识图谱节点 | 10,126 |
| REQUIRES 前置关系 | ~4,600 |
| 资源生成成功率 | 100% (33/33) |
| KG+RAG 混合检索提升 | +56% vs baseline |
| 智能辅导综合评分 | 4.40 / 5.00 |
| 首 Token 延迟 | 1.2s |

---

## 核心依赖

| 组件 | 用途 |
|------|------|
| Spring Boot 3.4 | 后端框架 |
| Spring AI (OpenAI) | LLM 调用抽象 |
| Spring Data Neo4j | 知识图谱数据访问 |
| Vue 3 + Vite | 前端框架 |
| Element Plus | UI 组件库 |
| ECharts | 数据可视化 |
| MySQL 8.0 | 关系型数据存储 |
| Neo4j 5 | 图数据库 |
| Qdrant | 向量检索库 |
| Chroma | 对话记忆库 |
| DeepSeek / 讯飞星火 | LLM 服务 |

---

## License

MIT License © 2026 智引未来团队
