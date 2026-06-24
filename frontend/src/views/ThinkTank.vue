<template>
  <div class="page-container">
    <!-- 复用导航栏布局：页面标题区 -->
    <div class="page-header">
      <div class="header-left">
        <h1>📚 私人智库</h1>
        <p>上传你的课程资料、笔记、教材，构建专属私有知识库</p>
      </div>
      <el-button type="primary" size="large" :icon="Upload" @click="showUpload = true">
        上传资料
      </el-button>
    </div>

    <!-- 知识源优先级说明 -->
    <div class="priority-bar">
      <div class="priority-step active">
        <span class="step-num">1</span>
        <span class="step-label">私有智库</span>
      </div>
      <div class="priority-arrow">→</div>
      <div class="priority-step">
        <span class="step-num">2</span>
        <span class="step-label">知识图谱</span>
      </div>
      <div class="priority-arrow">→</div>
      <div class="priority-step">
        <span class="step-num">3</span>
        <span class="step-label">互联网</span>
      </div>
      <span class="priority-tip">智能辅导将按此优先级依次检索</span>
    </div>

    <!-- 资料列表（空态/有数据） -->
    <div class="content-area">
      <el-empty v-if="materials.length === 0" description="还没有上传任何资料">
        <el-button type="primary" @click="showUpload = true">立即上传</el-button>
      </el-empty>

      <div v-else class="material-grid">
        <div v-for="item in materials" :key="item.id" class="material-card">
          <div class="material-icon">{{ fileIcon(item.type) }}</div>
          <div class="material-info">
            <h4>{{ item.name }}</h4>
            <span class="material-meta">{{ item.type.toUpperCase() }} · {{ item.size }}</span>
          </div>
          <el-dropdown trigger="click">
            <el-button circle :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item>预览</el-dropdown-item>
                <el-dropdown-item>重新上传</el-dropdown-item>
                <el-dropdown-item divided class="danger">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </div>

    <!-- 上传对话框 -->
    <el-dialog v-model="showUpload" title="上传资料到私人智库" width="520px" center>
      <el-upload
        drag
        multiple
        :auto-upload="false"
        :on-change="handleFileChange"
        :limit="10"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-text">
          <p>将文件拖到此处，或<em>点击上传</em></p>
          <span class="upload-hint">支持 PDF、Word、PPT、Markdown、TXT、图片，单文件不超过 50MB</span>
        </div>
      </el-upload>
      <template #footer>
        <el-button @click="showUpload = false">取消</el-button>
        <el-button type="primary" @click="confirmUpload" :loading="uploading">
          确认上传
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload, UploadFilled, MoreFilled } from '@element-plus/icons-vue'
import request from '@/api/request'

const showUpload = ref(false)
const uploading = ref(false)
const pendingFiles = ref([])
const materials = ref([])

const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.studentId || 'anonymous'
  } catch { return 'anonymous' }
}

const fileIcon = (type) => {
  const map = { pdf: '📕', doc: '📘', docx: '📘', ppt: '📊', pptx: '📊', md: '📝', txt: '📄', png: '🖼️', jpg: '🖼️' }
  return map[type] || '📎'
}

const handleFileChange = (file) => {
  pendingFiles.value.push(file)
}

const confirmUpload = async () => {
  if (pendingFiles.value.length === 0) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  let successCount = 0
  try {
    for (const f of pendingFiles.value) {
      const formData = new FormData()
      formData.append('file', f.raw)
      const res = await request.post('/rag/documents', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        params: { studentId: getStudentId() },
        timeout: 60000
      })
      const data = res.data
      materials.value.push({
        id: Date.now() + Math.random(),
        name: data.filename || f.name,
        type: f.name.split('.').pop().toLowerCase(),
        size: `${data.chunks || 0} 个文本块`
      })
      successCount++
    }
    ElMessage.success(`成功上传 ${successCount} 个文件`)
    showUpload.value = false
    pendingFiles.value = []
  } catch (e) {
    const msg = e.response?.data?.error || '上传失败，请稍后重试'
    ElMessage.error(msg)
  } finally {
    uploading.value = false
  }
}

const formatSize = (bytes) => {
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + 'KB'
  return (bytes / 1048576).toFixed(1) + 'MB'
}
</script>

<style scoped>
.page-container { max-width: 1100px; margin: 0 auto; padding: 32px 24px 60px; }

.page-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  background: #fff; padding: 28px 32px; border-radius: 20px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.header-left h1 { font-size: 26px; font-weight: 700; color: #1a1a2e; margin: 0 0 6px; }
.header-left p { font-size: 14px; color: #8890a0; margin: 0; }

/* 优先级条 */
.priority-bar {
  display: flex; align-items: center; gap: 12px;
  margin-top: 20px; padding: 16px 24px;
  background: #fff; border-radius: 14px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
}
.priority-step {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 16px; border-radius: 10px; background: #f2f3f7;
}
.priority-step.active { background: #eef0ff; }
.priority-step.active .step-num { background: #667eea; color: #fff; }
.step-num {
  width: 24px; height: 24px; border-radius: 50%;
  background: #dcdfe6; color: #fff;
  font-size: 12px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.step-label { font-size: 13px; font-weight: 500; color: #4a4f5e; }
.priority-arrow { color: #c0c4cc; font-size: 18px; }
.priority-tip { margin-left: auto; font-size: 12px; color: #909399; }

/* 内容区 */
.content-area { margin-top: 24px; }
.material-grid { display: flex; flex-direction: column; gap: 10px; }
.material-card {
  display: flex; align-items: center; gap: 16px;
  padding: 16px 20px; background: #fff; border-radius: 14px;
  border: 1px solid #eef0f4; transition: all 0.2s;
}
.material-card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.04); border-color: #d0d5dd; }
.material-icon { font-size: 32px; }
.material-info { flex: 1; }
.material-info h4 { font-size: 15px; font-weight: 600; color: #1a1a2e; margin: 0 0 4px; }
.material-meta { font-size: 12px; color: #909399; }

/* 上传弹窗 */
.upload-icon { font-size: 48px; color: #667eea; }
.upload-text p { font-size: 15px; color: #4a4f5e; margin: 12px 0 6px; }
.upload-text p em { color: #667eea; font-style: normal; }
.upload-hint { font-size: 12px; color: #c0c4cc; }

.danger { color: #f56c6c !important; }
</style>
