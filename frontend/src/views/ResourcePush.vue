<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <h1>🗺️ 资源推送</h1>
        <p>基于学习画像，动态规划个性化学习路径，精准推送多类型资源</p>
      </div>
      <div class="header-right">
        <el-input
          v-model="targetTopic"
          placeholder="目标知识点，如：支持向量机"
          size="default"
          style="width: 240px"
          clearable
          @keyup.enter="loadData"
        />
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="loadData">
          生成路径
        </el-button>
      </div>
    </div>

    <div v-if="loading" class="loading-area">
      <el-skeleton :rows="3" animated />
    </div>

    <div v-else class="two-col">
      <!-- 学习路径 -->
      <section class="section">
        <h3>📐 个性化学习路径</h3>
        <div v-if="learningPath.length === 0" class="empty-card">
          <el-empty description="输入目标知识点，生成个性化学习路径" :image-size="60">
            <span class="empty-hint">例如：机器学习、支持向量机、深度学习</span>
          </el-empty>
        </div>
        <div v-else class="path-card">
          <div class="path-summary">
            <span>共 <strong>{{ pathMeta.totalNodes }}</strong> 个节点</span>
            <span>已完成 <strong>{{ pathMeta.completedNodes }}</strong> 个</span>
            <span>预计 <strong>{{ pathMeta.estimatedDays }}</strong> 天</span>
          </div>
          <el-timeline>
            <el-timeline-item
              v-for="(node, i) in learningPath"
              :key="i"
              :timestamp="'第' + (i + 1) + '步 · ' + (node.estimatedDuration || '2-3天')"
              :color="statusColor(node.status)"
              :hollow="node.status === 'PENDING'"
            >
              <div class="path-node">
                <strong>{{ node.knowledgePointName }}</strong>
                <span class="path-desc">{{ node.description }}</span>
                <div class="path-meta">
                  <el-tag size="small" type="info">难度 {{ '⭐'.repeat(node.difficulty || 1) }}</el-tag>
                  <el-tag v-if="node.category" size="small">{{ node.category }}</el-tag>
                </div>
                <div class="path-resources">
                  <el-tag
                    v-for="r in (node.recommendedResources || [])"
                    :key="r.resourceType"
                    size="small"
                    :type="tagType(r.resourceType)"
                  >
                    {{ r.icon }} {{ r.resourceTypeLabel || r.resourceType }}
                  </el-tag>
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </section>

      <!-- 资源推荐 -->
      <section class="section">
        <h3>🎯 今日推荐资源</h3>
        <div v-if="recommendations.length === 0" class="empty-card">
          <el-empty description="生成学习路径后，系统将自动推荐资源" :image-size="60" />
        </div>
        <div v-else class="recommend-list">
          <div v-for="item in recommendations" :key="item.title + item.resourceType" class="recommend-card" @click="goToLearning(item)">
            <span class="rec-icon">{{ item.icon || iconForType(item.resourceType) }}</span>
            <div class="rec-info">
              <strong>{{ item.title }}</strong>
              <span class="rec-meta">{{ item.resourceTypeLabel || item.resourceType }} · {{ item.reason }}</span>
            </div>
            <el-button size="small" type="primary" plain @click.stop="goToLearning(item)">学习</el-button>
          </div>
        </div>

        <h3 style="margin-top: 28px">📊 推送策略</h3>
        <div class="strategy-card">
          <div v-for="s in strategies" :key="s.title" class="strategy-item">
            <span class="st-icon">{{ s.icon }}</span>
            <div>
              <strong>{{ s.title }}</strong>
              <p>{{ s.description }}</p>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getRecommendationsApi } from '../api/index.js'

const targetTopic = ref('机器学习')
const loading = ref(false)
const learningPath = ref([])
const pathMeta = ref({ totalNodes: 0, completedNodes: 0, estimatedDays: 0 })
const recommendations = ref([])
const strategies = ref([
  { icon: '🧠', title: '基于画像', description: '根据你的6维学习画像，匹配最适合的学习内容和难度' },
  { icon: '📈', title: '基于进度', description: '实时追踪学习进度，动态调整推送节奏和内容顺序' },
  { icon: '🔄', title: '基于反馈', description: '根据练习测试和资源使用反馈，持续优化推送策略' }
])

const iconForType = (t) => {
  const map = { DOC: '📄', PPT: '📊', QUIZ: '📝', CODE: '💻', VIDEO: '🎬', MINDMAP: '🧩', HTML: '🌐' }
  return map[t] || '📄'
}

const tagType = (type) => {
  const map = { DOC: '', PPT: 'warning', QUIZ: 'danger', CODE: 'success', VIDEO: 'info', MINDMAP: '', HTML: '' }
  return map[type] || ''
}

const statusColor = (status) => {
  if (status === 'COMPLETED') return '#67c23a'
  if (status === 'CURRENT') return '#667eea'
  return '#dcdfe6'
}

function getStudentId() {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.id || info.phone || 'anonymous'
  } catch { return 'anonymous' }
}

async function loadData() {
  const topic = targetTopic.value.trim()
  if (!topic) return

  loading.value = true
  try {
    const res = await getRecommendationsApi(getStudentId(), topic)
    const data = res.data?.data
    if (data) {
      // 学习路径
      if (data.learningPath) {
        const path = data.learningPath
        learningPath.value = path.nodes || []
        pathMeta.value = {
          totalNodes: path.totalNodes || learningPath.value.length,
          completedNodes: path.completedNodes || 0,
          estimatedDays: path.estimatedTotalDays || 0
        }
      }

      // 推荐资源
      if (data.allRecommendations) {
        recommendations.value = data.allRecommendations
      }

      // 推送策略（从后端获取）
      if (data.pushStrategies && data.pushStrategies.length > 0) {
        strategies.value = data.pushStrategies.map(s => ({
          icon: s.icon || '📊',
          title: s.title || '',
          description: s.description || ''
        }))
      }

      ElMessage.success(`已为「${topic}」生成学习路径和推荐`)
    }
  } catch (e) {
    ElMessage.error('加载失败: ' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}

function goToLearning(item) {
  // 跳转到学习页面并预填主题
  const topic = item.title?.split(' ')[0] || ''
  if (topic) {
    // 使用 query 参数传递
    import('../router/index.js').then(mod => {
      const router = mod.default
      router.push({ path: '/learning', query: { topic } })
    })
  } else {
    window.location.href = '/learning'
  }
}

onMounted(() => {
  // 页面加载时尝试使用默认主题获取数据
  loadData()
})
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
.header-right { display: flex; gap: 10px; align-items: center; flex-shrink: 0; }

.loading-area { margin-top: 24px; padding: 24px; background: #fff; border-radius: 16px; }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 24px; }

.section h3 { font-size: 18px; font-weight: 600; color: #1a1a2e; margin: 0 0 14px; }

.empty-card {
  background: #fff; padding: 32px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
}
.empty-hint { font-size: 12px; color: #c0c4cc; }

/* 学习路径 */
.path-card {
  background: #fff; padding: 24px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
}
.path-summary {
  display: flex; gap: 16px;
  padding: 12px 16px; margin-bottom: 16px;
  background: #f8f7ff; border-radius: 10px;
  font-size: 13px; color: #667eea;
}
.path-summary strong { font-weight: 700; }
.path-node strong { display: block; font-size: 15px; color: #1a1a2e; margin-bottom: 2px; }
.path-desc { font-size: 13px; color: #8890a0; display: block; margin: 4px 0; }
.path-meta { display: flex; gap: 6px; margin: 8px 0; }
.path-resources { margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap; }

/* 推荐 */
.recommend-list { display: flex; flex-direction: column; gap: 10px; }
.recommend-card {
  display: flex; align-items: center; gap: 14px;
  padding: 14px 18px; background: #fff; border-radius: 14px;
  border: 1px solid #eef0f4; cursor: pointer; transition: all 0.2s;
}
.recommend-card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.04); border-color: #d0d5dd; }
.rec-icon { font-size: 28px; }
.rec-info { flex: 1; }
.rec-info strong { display: block; font-size: 14px; color: #1a1a2e; margin-bottom: 2px; }
.rec-meta { font-size: 12px; color: #909399; }

/* 策略 */
.strategy-card {
  background: #fff; padding: 20px 24px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
  display: flex; flex-direction: column; gap: 16px;
}
.strategy-item { display: flex; gap: 14px; align-items: flex-start; }
.st-icon { font-size: 24px; flex-shrink: 0; }
.strategy-item strong { font-size: 14px; color: #1a1a2e; }
.strategy-item p { font-size: 12px; color: #8890a0; margin: 2px 0 0; }

@media (max-width: 800px) {
  .two-col { grid-template-columns: 1fr; }
  .page-header { flex-direction: column; gap: 12px; }
  .header-right { width: 100%; }
  .header-right .el-input { flex: 1; }
}
</style>