<template>
  <div class="home">
    <div class="content-wrapper">
      <!-- 欢迎横幅 -->
      <section class="hero-banner">
        <div class="hero-text">
          <h1>你好，{{ userInfo?.nickname || '同学' }} 👋</h1>
          <p>你的专属 AI 学习助手已就绪。上传资料构建私人智库，智能辅导优先基于你的知识库作答。</p>
          <div class="hero-actions">
            <el-button type="primary" size="large" round @click="$router.push('/tutoring')">
              <el-icon><ChatDotRound /></el-icon>开始智能辅导
            </el-button>
            <el-button size="large" round class="btn-outline" @click="$router.push('/thinktank')">
              <el-icon><Upload /></el-icon>上传学习资料
            </el-button>
          </div>
        </div>
        <div class="hero-stats">
          <div class="stat-item">
            <span class="stat-num">0</span>
            <span class="stat-label">私有资料</span>
          </div>
          <div class="stat-item">
            <span class="stat-num">7</span>
            <span class="stat-label">资源类型</span>
          </div>
          <div class="stat-item">
            <span class="stat-num">24h</span>
            <span class="stat-label">智能在线</span>
          </div>
        </div>
      </section>

      <!-- 核心功能 -->
      <section class="feature-section">
        <h2 class="section-title">核心功能</h2>
        <p class="section-desc">私人智库 → 智能辅导 → 资源生成 → 精准推送，形成完整学习闭环</p>
        <div class="feature-grid">
          <div class="feature-card card-purple" @click="$router.push('/thinktank')">
            <div class="card-shine"></div>
            <div class="card-icon-wrap"><span class="card-icon">📚</span></div>
            <div class="card-body">
              <h3>私人智库</h3>
              <p>上传个人课程资料、笔记、教材，构建专属知识库。智能辅导优先从这里检索，你的资料你做主。</p>
            </div>
            <div class="card-footer">
              <span class="card-tag">资料上传</span>
              <span class="card-tag">私有知识库</span>
              <span class="card-tag">第一优先级</span>
            </div>
          </div>

          <div class="feature-card card-orange" @click="$router.push('/tutoring')">
            <div class="card-shine"></div>
            <div class="card-icon-wrap"><span class="card-icon">🎓</span></div>
            <div class="card-body">
              <h3>智能辅导</h3>
              <p>遇到问题随时提问。检索链路：私有智库 → 本地知识图谱 → 互联网，确保答案准确可靠。</p>
            </div>
            <div class="card-footer">
              <span class="card-tag">三级检索</span>
              <span class="card-tag">即时答疑</span>
              <span class="card-tag">多模态解答</span>
            </div>
          </div>

          <div class="feature-card card-blue" @click="$router.push('/learning')">
            <div class="card-shine"></div>
            <div class="card-icon-wrap"><span class="card-icon">🤖</span></div>
            <div class="card-body">
              <h3>沉浸学习</h3>
              <p>多智能体协同生成个性化学习资源——文档、PPT、题库、导图、视频、代码、课件共 7 种类型。</p>
            </div>
            <div class="card-footer">
              <span class="card-tag">多智能体</span>
              <span class="card-tag">7种资源</span>
              <span class="card-tag">多模态生成</span>
            </div>
          </div>

          <div class="feature-card card-green" @click="$router.push('/push')">
            <div class="card-shine"></div>
            <div class="card-icon-wrap"><span class="card-icon">🗺️</span></div>
            <div class="card-body">
              <h3>资源推送</h3>
              <p>基于学习画像动态规划个性化学习路径，按进度精准推送文档、视频、题库等多类型资源。</p>
            </div>
            <div class="card-footer">
              <span class="card-tag">路径规划</span>
              <span class="card-tag">精准推送</span>
              <span class="card-tag">动态调整</span>
            </div>
          </div>

          <div class="feature-card card-pink" @click="$router.push('/profile')">
            <div class="card-shine"></div>
            <div class="card-icon-wrap"><span class="card-icon">📊</span></div>
            <div class="card-body">
              <h3>个人中心</h3>
              <p>6维学习画像可视化、学习效果评估报告、历史记录回顾。基于评估结果持续优化学习策略。</p>
            </div>
            <div class="card-footer">
              <span class="card-tag">6维画像</span>
              <span class="card-tag">效果评估</span>
              <span class="card-tag">策略优化</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 快速生成 -->
      <section class="quick-section">
        <h2 class="section-title">快速生成资源</h2>
        <p class="section-desc">选择类型，AI 智能体即刻为你生成</p>
        <div class="resource-grid">
          <div v-for="res in resourceTypes" :key="res.type" class="resource-card" @click="$router.push('/learning')">
            <span class="res-icon">{{ res.icon }}</span>
            <span class="res-name">{{ res.label }}</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ChatDotRound, Upload } from '@element-plus/icons-vue'

const userInfo = ref({})

onMounted(() => {
  const info = localStorage.getItem('userInfo')
  if (info) {
    try { userInfo.value = JSON.parse(info) } catch (e) { userInfo.value = {} }
  }
})

const resourceTypes = [
  { type: 'doc', icon: '📄', label: '课程文档' },
  { type: 'ppt', icon: '📊', label: '教学PPT' },
  { type: 'quiz', icon: '📝', label: '练习题库' },
  { type: 'mindmap', icon: '🧩', label: '思维导图' },
  { type: 'video', icon: '🎬', label: '教学视频' },
  { type: 'code', icon: '💻', label: '代码案例' },
  { type: 'html', icon: '🌐', label: '交互课件' }
]
</script>

<style scoped>
.home { padding: 32px 32px 60px; }
.content-wrapper { max-width: 1200px; margin: 0 auto; }

.hero-banner {
  display: flex; justify-content: space-between; align-items: center;
  padding: 36px 40px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 24px; color: #fff; position: relative; overflow: hidden;
}
.hero-banner::before {
  content: ''; position: absolute; width: 300px; height: 300px;
  border-radius: 50%; background: rgba(255,255,255,0.06); top: -80px; right: -40px;
}
.hero-banner::after {
  content: ''; position: absolute; width: 200px; height: 200px;
  border-radius: 50%; background: rgba(255,255,255,0.04); bottom: -60px; right: 180px;
}
.hero-text { position: relative; z-index: 1; flex: 1; }
.hero-text h1 { font-size: 28px; font-weight: 700; margin: 0 0 10px; }
.hero-text p { font-size: 15px; opacity: 0.85; line-height: 1.6; margin: 0 0 20px; max-width: 520px; }
.hero-actions { display: flex; gap: 12px; }
.hero-actions .el-button--primary { background: #fff; color: #667eea; border: none; font-weight: 600; }
.hero-actions .el-button--primary:hover { background: #f0eeff; color: #5a6fd6; }
.btn-outline { background: transparent !important; color: #fff !important; border: 1.5px solid rgba(255,255,255,0.5) !important; }
.btn-outline:hover { background: rgba(255,255,255,0.12) !important; border-color: #fff !important; }

.hero-stats { display: flex; gap: 32px; position: relative; z-index: 1; }
.stat-item {
  display: flex; flex-direction: column; align-items: center;
  padding: 16px 24px; background: rgba(255,255,255,0.15);
  border-radius: 16px; backdrop-filter: blur(10px); min-width: 80px;
}
.stat-num { font-size: 28px; font-weight: 800; }
.stat-label { font-size: 13px; opacity: 0.8; margin-top: 4px; }

.section-title { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0 0 6px; }
.section-desc { font-size: 14px; color: #8890a0; margin: 0 0 24px; }

.feature-section { margin-top: 40px; }
.feature-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 20px; }

.feature-card {
  position: relative; padding: 28px; border-radius: 20px;
  background: #fff; border: 1px solid #eef0f4;
  cursor: pointer; transition: all 0.3s; overflow: hidden;
}
.feature-card:hover { transform: translateY(-4px); box-shadow: 0 16px 40px rgba(0,0,0,0.08); border-color: transparent; }

.card-shine { position: absolute; top: 0; left: 0; right: 0; height: 3px; opacity: 0; transition: opacity 0.3s; }
.feature-card:hover .card-shine { opacity: 1; }
.card-purple .card-shine { background: linear-gradient(90deg, #a855f7, #6366f1); }
.card-blue   .card-shine { background: linear-gradient(90deg, #3b82f6, #06b6d4); }
.card-green  .card-shine { background: linear-gradient(90deg, #22c55e, #14b8a6); }
.card-orange .card-shine { background: linear-gradient(90deg, #f97316, #eab308); }
.card-pink   .card-shine { background: linear-gradient(90deg, #ec4899, #f43f5e); }

.card-icon-wrap {
  width: 52px; height: 52px; border-radius: 14px;
  display: flex; align-items: center; justify-content: center; margin-bottom: 16px;
}
.card-purple .card-icon-wrap { background: #f3f0ff; }
.card-blue   .card-icon-wrap { background: #eff6ff; }
.card-green  .card-icon-wrap { background: #ecfdf5; }
.card-orange .card-icon-wrap { background: #fff7ed; }
.card-pink   .card-icon-wrap { background: #fdf2f8; }
.card-icon { font-size: 26px; }

.card-body h3 { font-size: 17px; font-weight: 600; color: #1a1a2e; margin: 0 0 8px; }
.card-body p { font-size: 13px; color: #6b7280; line-height: 1.65; margin: 0; }

.card-footer { display: flex; gap: 8px; margin-top: 16px; flex-wrap: wrap; }
.card-tag { font-size: 11px; padding: 3px 10px; border-radius: 20px; font-weight: 500; }
.card-purple .card-tag { background: #f3f0ff; color: #7c3aed; }
.card-blue   .card-tag { background: #eff6ff; color: #2563eb; }
.card-green  .card-tag { background: #ecfdf5; color: #059669; }
.card-orange .card-tag { background: #fff7ed; color: #ea580c; }
.card-pink   .card-tag { background: #fdf2f8; color: #db2777; }

.quick-section { margin-top: 48px; }
.resource-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 16px; }
.resource-card {
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  padding: 24px 12px; background: #fff; border-radius: 16px;
  border: 1px solid #eef0f4; cursor: pointer; transition: all 0.25s;
}
.resource-card:hover { transform: translateY(-3px); box-shadow: 0 8px 25px rgba(0,0,0,0.06); border-color: #667eea; }
.res-icon { font-size: 32px; }
.res-name { font-size: 13px; font-weight: 500; color: #4a4f5e; }

@media (max-width: 900px) {
  .hero-banner { flex-direction: column; text-align: center; gap: 24px; }
  .hero-text p { max-width: 100%; }
  .hero-actions { justify-content: center; }
  .feature-grid { grid-template-columns: 1fr; }
  .resource-grid { grid-template-columns: repeat(4, 1fr); }
}
@media (max-width: 600px) {
  .home { padding: 20px 16px 40px; }
  .resource-grid { grid-template-columns: repeat(2, 1fr); }
  .hero-stats { gap: 12px; }
  .stat-item { padding: 12px 16px; min-width: 60px; }
  .stat-num { font-size: 22px; }
}
</style>
