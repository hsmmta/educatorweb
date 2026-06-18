<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <h1>🗺️ 资源推送</h1>
        <p>基于学习画像，动态规划个性化学习路径，精准推送多类型资源</p>
      </div>
    </div>

    <div class="two-col">
      <!-- 学习路径 -->
      <section class="section">
        <h3>📐 个性化学习路径</h3>
        <div class="path-card">
          <el-timeline>
            <el-timeline-item
              v-for="(node, i) in learningPath"
              :key="i"
              :timestamp="node.time"
              :color="node.status === 'done' ? '#67c23a' : node.status === 'current' ? '#667eea' : '#dcdfe6'"
              :hollow="node.status === 'pending'"
            >
              <div class="path-node">
                <strong>{{ node.title }}</strong>
                <span class="path-desc">{{ node.desc }}</span>
                <div class="path-resources">
                  <el-tag v-for="r in node.resources" :key="r" size="small" :type="tagType(r)">
                    {{ r }}
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
        <div class="recommend-list">
          <div v-for="item in recommendations" :key="item.id" class="recommend-card">
            <span class="rec-icon">{{ item.icon }}</span>
            <div class="rec-info">
              <strong>{{ item.title }}</strong>
              <span class="rec-meta">{{ item.type }} · {{ item.reason }}</span>
            </div>
            <el-button size="small" type="primary" plain>查看</el-button>
          </div>
        </div>

        <h3 style="margin-top: 28px">📊 推送策略</h3>
        <div class="strategy-card">
          <div class="strategy-item">
            <span class="st-icon">🧠</span>
            <div>
              <strong>基于画像</strong>
              <p>根据你的6维学习画像，匹配最适合的学习内容和难度</p>
            </div>
          </div>
          <div class="strategy-item">
            <span class="st-icon">📈</span>
            <div>
              <strong>基于进度</strong>
              <p>实时追踪学习进度，动态调整推送节奏和内容顺序</p>
            </div>
          </div>
          <div class="strategy-item">
            <span class="st-icon">🔄</span>
            <div>
              <strong>基于反馈</strong>
              <p>根据练习测试和资源使用反馈，持续优化推送策略</p>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

// 接口预留
const API_BASE = '/api/push'

const learningPath = ref([
  { title: 'Python 基础语法', desc: '掌握变量、数据类型、控制流', time: '第1周', status: 'done',
    resources: ['📄文档', '📝题库'] },
  { title: 'NumPy 数值计算', desc: '数组操作、矩阵运算、广播机制', time: '第2周', status: 'done',
    resources: ['📄文档', '💻代码'] },
  { title: 'Pandas 数据处理', desc: 'DataFrame、数据清洗、聚合分析', time: '第3周', status: 'current',
    resources: ['📄文档', '📊PPT', '💻代码'] },
  { title: 'Matplotlib 可视化', desc: '折线图、散点图、统计图表', time: '第4周', status: 'pending',
    resources: ['📄文档', '🎬视频'] },
  { title: 'Scikit-learn 入门', desc: '分类、回归、聚类算法实践', time: '第5周', status: 'pending',
    resources: ['📄文档', '📝题库', '💻代码'] }
])

const recommendations = ref([
  { id: 1, icon: '📄', title: 'Pandas 数据清洗完全指南', type: '课程文档', reason: '基于你的学习进度推荐' },
  { id: 2, icon: '📊', title: '数据分析可视化最佳实践', type: '教学PPT', reason: '匹配你的认知风格' },
  { id: 3, icon: '📝', title: 'Python 数据处理 50 题', type: '练习题库', reason: '针对你的知识薄弱点' },
  { id: 4, icon: '💻', title: 'Pandas + Matplotlib 实战案例', type: '代码案例', reason: '最近热门资源' }
])

const tagType = (r) => {
  if (r.includes('文档')) return ''
  if (r.includes('PPT')) return 'warning'
  if (r.includes('题库')) return 'danger'
  if (r.includes('代码')) return 'success'
  if (r.includes('视频')) return 'info'
  return ''
}
</script>

<style scoped>
.page-container { max-width: 1100px; margin: 0 auto; padding: 32px 24px 60px; }

.page-header {
  background: #fff; padding: 28px 32px; border-radius: 20px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.header-left h1 { font-size: 26px; font-weight: 700; color: #1a1a2e; margin: 0 0 6px; }
.header-left p { font-size: 14px; color: #8890a0; margin: 0; }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 24px; }

.section h3 { font-size: 18px; font-weight: 600; color: #1a1a2e; margin: 0 0 14px; }

/* 学习路径 */
.path-card {
  background: #fff; padding: 24px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03);
}
.path-node strong { display: block; font-size: 15px; color: #1a1a2e; margin-bottom: 2px; }
.path-desc { font-size: 13px; color: #8890a0; }
.path-resources { margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap; }

/* 推荐 */
.recommend-list { display: flex; flex-direction: column; gap: 10px; }
.recommend-card {
  display: flex; align-items: center; gap: 14px;
  padding: 14px 18px; background: #fff; border-radius: 14px;
  border: 1px solid #eef0f4; transition: all 0.2s;
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
}
</style>
