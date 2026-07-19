# Docker 一键部署设计文档

## 目标

为比赛提交提供一条命令即可完整运行的多智能体学习系统部署方案。

## 架构

6 个 Docker 容器通过 `docker-compose.yml` 编排，所有服务在隔离网络中通信。

```
                 ┌─────────────┐
          ┌──────│  Nginx:80   │────── 前端静态文件
          │      └─────────────┘
          │
┌─────────┴──────────────────────────────────────┐
│               Docker Network                   │
│                                                │
│  ┌──────────┐ ┌────────┐ ┌────────┐           │
│  │ Backend  │ │ MySQL  │ │ Neo4j  │           │
│  │ :8080    │ │ :3306  │ │ :7687  │           │
│  └──────────┘ └────────┘ └────────┘           │
│  ┌──────────┐ ┌────────┐                      │
│  │ Qdrant   │ │ Chroma │                      │
│  │ :6333    │ │ :8000  │                      │
│  └──────────┘ └────────┘                      │
└────────────────────────────────────────────────┘
```

## 组件清单

### 1. MySQL 8
- 镜像: `mysql:8.0`
- 端口: 3306
- 数据: `mysql-init.sql` 在容器首次启动时自动执行建表
- 持久化: Docker volume `mysql_data`

### 2. Neo4j 5
- 镜像: `neo4j:5-community`
- 端口: 7474 (HTTP), 7687 (Bolt)
- 数据: 预置知识图谱 dump 文件（10126 节点 + REQUIRES/RELATED_TO/CONTAINS 关系）
- 恢复方式: 构建时 `neo4j-admin database load` 或容器内恢复脚本
- 持久化: Docker volume `neo4j_data`

### 3. Qdrant
- 镜像: `qdrant/qdrant:latest`
- 端口: 6333 (HTTP), 6334 (gRPC)
- 数据: 启动后通过 Python 脚本导入 `qdrant_kg_references.json` 和 `qdrant_ml_documents.json`
- 持久化: Docker volume `qdrant_data`

### 4. Chroma
- 镜像: `chromadb/chroma:latest`
- 端口: 8000
- 数据: 空启动，对话记忆自动填充
- 持久化: Docker volume `chroma_data`

### 5. Spring Boot 后端
- 镜像: 自建 (`Dockerfile.backend`)，基于 `eclipse-temurin:25-jre`
- 端口: 8080
- 配置: 通过环境变量注入所有数据库连接信息
- JVM 参数: `-Xms256m -Xmx1g`

### 6. Nginx 前端
- 镜像: 自建 (`Dockerfile.frontend`)，基于 `nginx:alpine`
- 端口: 80
- 内容: Vue 编译后的静态文件
- 反向代理: `/api/*` → `backend:8080`

## 文件结构

```
educatorweb/
├── docker-compose.yml            # 一键启动编排
├── Dockerfile.backend            # 后端镜像构建
├── Dockerfile.frontend           # 前端镜像构建
├── nginx.conf                    # Nginx 反向代理配置
├── data/
│   ├── mysql-init.sql            # MySQL 建表 + 系统预置数据
│   ├── neo4j-dump/               # Neo4j 知识图谱 dump 文件
│   ├── qdrant_kg_references.json # Qdrant 知识图谱引用向量
│   ├── qdrant_ml_documents.json  # Qdrant ML 文档向量
│   └── import_qdrant.py          # Qdrant 数据导入脚本
├── db/
│   └── example_user.sql          # 示例用户数据（后续补充）
├── README.md                     # 部署使用说明
└── docs/
    └── 系统设计说明书.md
```

## 环境变量

评委需自行配置的变量（写在 `.env` 或 docker-compose 环境变量中）：

| 变量 | 说明 | 必需 |
|------|------|------|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 | 是 |
| `DEEPSEEK_BASE_URL` | DeepSeek API 地址 | 否 |
| `DOUBAO_API_KEY` | 豆包视频生成密钥 | 否（视频生成可选） |

其他数据库地址、端口、用户名密码均为容器内部网络，已预设，评委无需关心。

## 启动流程

```bash
# 1. 克隆项目 & 进入目录
cd educatorweb

# 2. 配置 API Key（可选，用 AI 功能才需要）
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY

# 3. 一键启动
docker compose up -d

# 4. 导入 Qdrant 向量数据（首次）
docker compose exec backend python /app/data/import_qdrant.py

# 5. 打开浏览器
# http://localhost
```

## 数据初始化细节

### MySQL 初始化
`mysql-init.sql` 在容器启动时自动执行，包含：
- 创建所有必需表（student_profile, wrong_answer_book, learning_resource, pre_generated_resource 等）
- 不包含示例用户数据（后续通过 SQL 导入）

### Neo4j 初始化
从当前运行的 Neo4j Desktop 导出 dump 文件，Docker 构建时预置：
```bash
neo4j-admin database dump edcatorweb --to-path=data/neo4j-dump/
```
容器启动脚本自动：
```bash
neo4j-admin database load edcatorweb --from-path=/data/neo4j-dump/ --overwrite-destination=true
```

### Qdrant 初始化
`import_qdrant.py` 读取 JSON 文件，通过 Qdrant HTTP API 创建 collection 并导入向量数据。

### Chroma 初始化
无需初始化，空启动即可。对话记忆由系统自动写入。

## 示例用户导入

后续补充示例用户时，将 SQL 文件放入 `db/example_user.sql`：
```bash
docker compose exec -T mysql mysql -uroot -proot educatorweb < db/example_user.sql
```

## 非功能需求

- **体积**: 所有镜像总计约 3-4 GB（主要是 Neo4j 和 JDK）
- **内存**: 最低 4 GB（建议 8 GB）
- **启动时间**: 首次约 2-3 分钟（数据库初始化），后续约 30 秒
- **兼容性**: Windows (Docker Desktop) / Linux / macOS
