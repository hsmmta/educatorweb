# 知识图谱导入更新 — 设计文档

## 1. 目标

知识图谱页面支持用户通过三种方式导入新知识点：文本描述、文件上传（PDF/Word/MD）、数据集 JSON。LLM 自动解析并加入 Neo4j 图谱。

## 2. 后端

### 2.1 新增 API

`KnowledgeGraphController` 新增：

**`POST /api/knowledge-graph/import`**（multipart/form-data）

| 参数 | 类型 | 说明 |
|------|------|------|
| `mode` | String | `text` / `file` / `dataset` |
| `text` | String | 知识点描述（mode=text 时） |
| `file` | MultipartFile | 上传文件（mode=file/dataset 时） |

返回：
```json
{
  "success": true,
  "conceptsAdded": 3,
  "edgesAdded": 5,
  "concepts": ["反向传播", "梯度消失", "激活函数"]
}
```

### 2.2 处理流程

**mode=text**：直接调用 `LlmKnowledgeExtractor.extract(text)` → Neo4j

**mode=file**：用 Apache Tika 或 PDFBox 提取文本 → 调用 `LlmKnowledgeExtractor.extract(text)`

**mode=dataset**：保存 JSON 文件到临时目录 → 调用 `KgBuildAgent.incrementalBuild()`

## 3. 前端

### 3.1 KnowledgeGraph.vue 工具栏新增按钮

```html
<el-button size="small" type="primary" :icon="Upload" @click="showImport = true">
  导入
</el-button>
```

### 3.2 导入对话框

`el-dialog` 三步流程：

1. 选择模式（三个 `el-radio` 卡片）
2. 输入内容（文本框 或 文件上传）
3. 导入中 + 结果展示

## 4. 影响范围

| 文件 | 改动 |
|------|------|
| `KnowledgeGraphController.java` | 新增 `POST /import` 端点 |
| `KnowledgeGraph.vue` | 工具栏加按钮 + 导入对话框 |
