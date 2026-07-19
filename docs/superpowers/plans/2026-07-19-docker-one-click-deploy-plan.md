# Docker 一键部署实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 6 容器 Docker Compose 编排，`docker compose up -d` 一键启动完整系统

**Architecture:** Nginx(前端) → Spring Boot(后端) → MySQL + Neo4j + Qdrant + Chroma，内部 Docker 网络通信

**Tech Stack:** Docker Compose, eclipse-temurin:25-jre, nginx:alpine, mysql:8.0, neo4j:5-community, qdrant/qdrant, chromadb/chroma

---

## 文件总览

| 文件 | 作用 |
|------|------|
| `docker-compose.yml` | 6 容器编排，网络，卷，健康检查 |
| `Dockerfile.backend` | 后端镜像：Maven 构建 → JRE 运行 |
| `Dockerfile.frontend` | 前端镜像：Vite 构建 → Nginx 静态文件 |
| `nginx.conf` | Nginx 配置：静态文件 + `/api` 反向代理 |
| `.env.example` | 环境变量模板（API Key 等） |
| `data/mysql-init.sql` | MySQL 初始化：建库 + 建表 |
| `data/neo4j-data/` | Neo4j 数据库目录（从本地复制） |
| `data/import_qdrant.py` | Qdrant 数据导入脚本 |
| `README.md` | 部署说明文档 |

---

### Task 1: 创建 Docker Compose 编排文件

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: 创建 docker-compose.yml**

```yaml
version: "3.8"

services:
  mysql:
    image: mysql:8.0
    container_name: edu-mysql
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root}
      MYSQL_DATABASE: educatorweb
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./data/mysql-init.sql:/docker-entrypoint-initdb.d/01-init.sql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 5s
      timeout: 5s
      retries: 10
    networks:
      - edu-net

  neo4j:
    image: neo4j:5-community
    container_name: edu-neo4j
    environment:
      NEO4J_AUTH: neo4j/${NEO4J_PASSWORD:-password}
      NEO4J_PLUGINS: "[]"
    ports:
      - "7474:7474"
      - "7687:7687"
    volumes:
      - neo4j_data:/data
      - ./data/neo4j-data:/backup-data
    healthcheck:
      test: ["CMD", "cypher-shell", "-u", "neo4j", "-p", "${NEO4J_PASSWORD:-password}", "RETURN 1"]
      interval: 10s
      timeout: 10s
      retries: 10
    entrypoint: |
      /bin/bash -c '
        if [ ! -f /data/.initialized ]; then
          cp -r /backup-data/* /data/databases/ 2>/dev/null || true
          touch /data/.initialized
        fi
        /startup/docker-entrypoint.sh neo4j
      '
    networks:
      - edu-net

  qdrant:
    image: qdrant/qdrant:latest
    container_name: edu-qdrant
    ports:
      - "6333:6333"
      - "6334:6334"
    volumes:
      - qdrant_data:/qdrant/storage
    networks:
      - edu-net

  chroma:
    image: chromadb/chroma:latest
    container_name: edu-chroma
    ports:
      - "8000:8000"
    volumes:
      - chroma_data:/chroma/chroma
    environment:
      IS_PERSISTENT: "TRUE"
      ANONYMIZED_TELEMETRY: "FALSE"
    networks:
      - edu-net

  backend:
    build:
      context: .
      dockerfile: Dockerfile.backend
    container_name: edu-backend
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/educatorweb?createDatabaseIfNotExist=true
      MYSQL_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root}
      NEO4J_URI: bolt://neo4j:7687
      NEO4J_USERNAME: neo4j
      NEO4J_PASSWORD: ${NEO4J_PASSWORD:-password}
      QDRANT_HOST: qdrant
      CHROMA_URL: http://chroma:8000
      DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY:-}
      DEEPSEEK_BASE_URL: ${DEEPSEEK_BASE_URL:-https://api.deepseek.com}
      SEEDANCE_API_KEY: ${SEEDANCE_API_KEY:-}
    depends_on:
      mysql:
        condition: service_healthy
      neo4j:
        condition: service_healthy
      qdrant:
        condition: service_started
      chroma:
        condition: service_started
    networks:
      - edu-net

  frontend:
    build:
      context: ./frontend
      dockerfile: ../Dockerfile.frontend
    container_name: edu-frontend
    ports:
      - "80:80"
    depends_on:
      - backend
    networks:
      - edu-net

volumes:
  mysql_data:
  neo4j_data:
  qdrant_data:
  chroma_data:

networks:
  edu-net:
    driver: bridge
```

- [ ] **Step 2: 验证 YAML 语法**

Run: `docker compose config`
Expected: 无错误输出完整配置

---

### Task 2: 后端 Dockerfile

**Files:**
- Create: `Dockerfile.backend`

- [ ] **Step 1: 创建 Dockerfile.backend（多阶段构建）**

```dockerfile
# Stage 1: Build the Spring Boot jar
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN apt-get update && apt-get install -y dos2unix && dos2unix mvnw && chmod +x mvnw
RUN ./mvnw package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
COPY data/import_qdrant.py /app/data/import_qdrant.py
COPY data/qdrant_kg_references.json /app/data/qdrant_kg_references.json
COPY data/qdrant_ml_documents.json /app/data/qdrant_ml_documents.json
COPY data/neo4j-data /app/data/neo4j-data
EXPOSE 8080
ENTRYPOINT ["java", "-Xms256m", "-Xmx1g", "-jar", "app.jar"]
```

Run: `docker build -f Dockerfile.backend -t edu-backend .`
Expected: 构建成功

---

### Task 3: 前端 Dockerfile + Nginx 配置

**Files:**
- Create: `Dockerfile.frontend`
- Create: `nginx.conf`

- [ ] **Step 1: 创建 Dockerfile.frontend**

```dockerfile
# Stage 1: Build Vue frontend
FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: Serve with Nginx
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
```

- [ ] **Step 2: 创建 nginx.conf**

```nginx
server {
    listen 80;
    server_name localhost;

    root /usr/share/nginx/html;
    index index.html;

    # Vue SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }

    # API proxy to backend
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }

    # SSE for streaming responses
    location /api/chat/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
    }
}
```

- [ ] **Step 3: 验证 Nginx 配置**

Run: `docker build -f Dockerfile.frontend -t edu-frontend ./frontend`
Expected: 构建成功

---

### Task 4: MySQL 初始化脚本

**Files:**
- Create: `data/mysql-init.sql`

- [ ] **Step 1: 创建 data/mysql-init.sql**

应用使用 `spring.jpa.hibernate.ddl-auto: update` 自动建表，但 MySQL 8 默认认证插件需要调整：

```sql
-- 确保数据库存在
CREATE DATABASE IF NOT EXISTS educatorweb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 修改 root 用户认证（MySQL 8 默认使用 caching_sha2_password，某些 JDBC 驱动需要 mysql_native_password）
ALTER USER 'root'@'%' IDENTIFIED WITH mysql_native_password BY 'root';
FLUSH PRIVILEGES;
```

Run: `docker compose up -d mysql && docker compose logs mysql`
Expected: MySQL 启动，init.sql 执行成功

---

### Task 5: 复制 Neo4j 数据

**Files:**
- Copy: `dev-docs/neo4j-backup/neo4j-data-20260719-180207/` → `data/neo4j-data/`

- [ ] **Step 1: 复制 Neo4j 数据库目录**

Run:
```bash
mkdir -p data/neo4j-data/databases
cp -r dev-docs/neo4j-backup/neo4j-data-20260719-180207/* data/neo4j-data/
```

Expected: `data/neo4j-data/` 包含 `block.*.db` 等 Neo4j 数据文件

---

### Task 6: Qdrant 导入脚本

**Files:**
- Create: `data/import_qdrant.py`

- [ ] **Step 1: 创建 data/import_qdrant.py**

```python
"""Import Qdrant vector data from JSON exports into local Qdrant instance."""
import json, os
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct

QDRANT_HOST = os.environ.get("QDRANT_HOST", "localhost")
DATA_DIR = os.path.join(os.path.dirname(__file__))

client = QdrantClient(host=QDRANT_HOST, port=6333)

files = {
    "qdrant_kg_references.json": "kg_references",
    "qdrant_ml_documents.json": "ml_documents",
}

for filename, coll_name in files.items():
    path = os.path.join(DATA_DIR, filename)
    if not os.path.exists(path):
        print(f"SKIP: {filename} not found")
        continue

    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    points_data = data.get("points", [])
    if not points_data:
        print(f"SKIP: {filename} has no points")
        continue

    # Determine vector size from first point
    first_vector = points_data[0].get("vector", [])
    vec_size = len(first_vector) if isinstance(first_vector, list) else 768

    # Recreate collection
    try:
        client.delete_collection(coll_name)
    except Exception:
        pass

    client.create_collection(
        collection_name=coll_name,
        vectors_config=VectorParams(size=vec_size, distance=Distance.COSINE),
    )

    # Batch upsert
    batch_size = 100
    for i in range(0, len(points_data), batch_size):
        batch = points_data[i : i + batch_size]
        points = []
        for p in batch:
            pid = p.get("id")
            vector = p.get("vector")
            payload = p.get("payload", {})
            if pid is None or vector is None:
                continue
            points.append(PointStruct(
                id=pid if isinstance(pid, (int, str)) else str(pid),
                vector=vector,
                payload=payload,
            ))
        if points:
            client.upsert(collection_name=coll_name, points=points)

    print(f"DONE: {coll_name} — {len(points_data)} vectors imported")
```

- [ ] **Step 2: 测试 Qdrant 导入**

Run:
```bash
pip install qdrant-client
python data/import_qdrant.py
```

Expected: 两个 collection 导入成功，无报错

---

### Task 7: 环境变量模板

**Files:**
- Create: `.env.example`

- [ ] **Step 1: 创建 .env.example**

```bash
# ======== 必需配置 ========
# DeepSeek API Key（AI 对话/内容生成）
DEEPSEEK_API_KEY=sk-your-key-here

# ======== 可选配置 ========
MYSQL_ROOT_PASSWORD=root
NEO4J_PASSWORD=password
DEEPSEEK_BASE_URL=https://api.deepseek.com
SEEDANCE_API_KEY=
```

---

### Task 8: README 部署文档

**Files:**
- Create: `README.md`

- [ ] **Step 1: 创建 README.md**

```markdown
# 智学派 (EducatorWeb)

个性化多智能体学习系统 — 知识图谱驱动的 AI 教育平台

## 一键部署

### 环境要求
- Docker Desktop 4.x+
- 内存 ≥ 8 GB

### 启动

```bash
# 1. 克隆项目
git clone <repo-url>
cd educatorweb

# 2. 配置 API Key
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY

# 3. 启动所有服务
docker compose up -d

# 4. 导入 Qdrant 向量数据（首次）
docker compose exec backend python /app/data/import_qdrant.py

# 5. 打开浏览器 http://localhost
```

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| 前端 | 80 | 用户界面 |
| 后端 API | 8080 | Spring Boot |
| MySQL | 3306 | 用户数据 |
| Neo4j | 7474 | 知识图谱浏览器 |
| Qdrant | 6333 | RAG 向量库 |
| Chroma | 8000 | 对话记忆 |

### 导入示例用户（可选）

```bash
docker compose exec -T mysql mysql -uroot -proot educatorweb < db/example_user.sql
```

### 停止

```bash
docker compose down
```

### 重置数据

```bash
docker compose down -v   # 删除所有数据卷
docker compose up -d      # 重新启动
```
```

---

### Task 9: 集成测试

- [ ] **Step 1: 完整启动测试**

Run:
```bash
docker compose down -v
docker compose up -d
```

等待所有容器健康检查通过（约 2-3 分钟）。

- [ ] **Step 2: 验证各项服务**

```bash
# 前端
curl -s -o /dev/null -w "%{http_code}" http://localhost/
# Expected: 200

# 后端 API
curl -s http://localhost/api/kg/status
# Expected: {"knowledgePointCount":10126,...}

# MySQL
docker compose exec mysql mysql -uroot -proot -e "SHOW TABLES" educatorweb
# Expected: 有表列表

# Neo4j
curl -s http://localhost:7474
# Expected: Neo4j 浏览器页面

# Qdrant
curl -s http://localhost:6333/collections
# Expected: {"collections":[...]}

# Chroma
curl -s http://localhost:8000/api/v1/heartbeat
# Expected: {"nanosecond heartbeat":...}
```

- [ ] **Step 3: 导入 Qdrant 并验证**

```bash
docker compose exec backend python /app/data/import_qdrant.py
curl -s http://localhost:6333/collections
# Expected: 包含 kg_references 和 ml_documents
```

- [ ] **Step 4: 提交**

```bash
git add docker-compose.yml Dockerfile.backend Dockerfile.frontend nginx.conf \
        data/ .env.example README.md
git commit -m "feat: add Docker Compose one-click deployment"
```
