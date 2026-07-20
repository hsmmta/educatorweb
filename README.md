# 智学派 (EducatorWeb)

个性化多智能体学习系统 — 知识图谱驱动的 AI 教育平台

## 一键部署

### 环境要求
- Docker Desktop 4.x+
- 内存 ≥ 8 GB

### 快速启动

```bash
# 1. 进入项目目录
cd educatorweb

# 2. 配置 API Key
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY

# 3. 启动所有服务（首次启动约 2-3 分钟）
docker compose up -d

# 4. 导入 Qdrant 向量数据（仅首次需要）
docker compose exec backend python /app/data/import_qdrant.py

# 5. 打开浏览器
# http://localhost
```

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| 前端 | 80 | 用户界面 |
| 后端 API | 8080 | Spring Boot |
| MySQL | 3306 | 用户数据 |
| Neo4j Browser | 7474 | 知识图谱浏览器 |
| Neo4j Bolt | 7687 | 知识图谱连接 |
| Qdrant | 6333 | RAG 向量库 |
| Chroma | 8000 | 对话记忆 |

### 功能模块

- **AI 对话辅导**：基于 DeepSeek 的多模态对话，支持文档/PPT/题库/代码/课件/视频生成
- **知识图谱**：10126 个 ML 知识节点，力导向图可视化，前置关系追溯
- **资源推送**：六维画像驱动的个性化学习资源推荐
- **学习路径**：知识图谱驱动的智能学习路径规划
- **错题集**：自动收集错题，支持重做练习
- **Live2D 角色**：交互式 AI 助手形象

### 导入示例用户（可选）

```bash
docker compose exec -T mysql mysql -uroot -proot educatorweb < db/example_user.sql
```

### 常用命令

```bash
# 查看日志
docker compose logs -f backend

# 停止服务
docker compose down

# 停止并清除所有数据（重新开始）
docker compose down -v

# 重启单个服务
docker compose restart backend
```
