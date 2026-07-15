<template>
  <div class="profile-build-page">
    <!-- 页面头部 -->
    <div class="page-hero">
      <div class="hero-content">
        <h1 class="hero-title">
          <span class="hero-icon">🧠</span>
          构建学习画像
        </h1>
        <p class="hero-desc">
          填写以下信息，AI 将自动分析你的学习特征，生成专属的六维学习画像
        </p>
      </div>
      <n-button
        secondary round size="large"
        @click="$router.push('/profile')"
      >
        <template #icon><ArrowBack /></template>
        返回画像
      </n-button>
    </div>

    <!-- 表单主体 -->
    <n-space vertical :size="20">
      <!-- 知识水平 -->
      <n-card title="知识储备" :bordered="false" class="section-card">
        <template #header-extra>
          <n-tag type="info" size="small" round>可选</n-tag>
        </template>
        <div class="card-grid">
          <div class="card-grid-item">
            <label class="field-label">📖 学得较好的知识点</label>
            <n-input
              v-model:value="form.strengths"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 6 }"
              placeholder="例如：Python 基础、NumPy、Pandas 数据处理、线性回归..."
            />
          </div>
          <div class="card-grid-item">
            <label class="field-label">⚠️ 相对薄弱的知识点</label>
            <n-input
              v-model:value="form.weaknesses"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 6 }"
              placeholder="例如：SVM 推导、深度学习调参、梯度下降数学原理..."
            />
          </div>
        </div>
      </n-card>

      <!-- 资料偏好 -->
      <n-card title="学习偏好" :bordered="false" class="section-card">
        <label class="field-label">🎯 偏好的学习资料类型（可多选）</label>
        <n-checkbox-group v-model:value="form.preferredResourceTypes">
          <n-space :size="12" :wrap="true">
            <n-checkbox
              v-for="opt in resourceTypeOptions"
              :key="opt.value"
              :value="opt.value"
              :label="opt.value"
            >
              <span class="check-label">{{ opt.icon }} {{ opt.label }}</span>
            </n-checkbox>
          </n-space>
        </n-checkbox-group>
      </n-card>

      <!-- 风格 + 节奏 -->
      <n-card title="学习特征" :bordered="false" class="section-card">
        <div class="card-grid">
          <div class="card-grid-item">
            <label class="field-label">🧩 学习风格</label>
            <n-radio-group v-model:value="form.learningStyle" name="style">
              <n-space vertical :size="8">
                <n-radio
                  v-for="opt in learningStyleOptions"
                  :key="opt.value"
                  :value="opt.value"
                >
                  <span class="radio-main">{{ opt.label }}</span>
                  <span class="radio-sub">{{ opt.desc }}</span>
                </n-radio>
              </n-space>
            </n-radio-group>
          </div>
          <div class="card-grid-item">
            <label class="field-label">🏃 学习节奏</label>
            <n-radio-group v-model:value="form.learningPace" name="pace">
              <n-space vertical :size="8">
                <n-radio
                  v-for="opt in learningPaceOptions"
                  :key="opt.value"
                  :value="opt.value"
                >
                  <span class="radio-main">{{ opt.label }}</span>
                  <span class="radio-sub">{{ opt.desc }}</span>
                </n-radio>
              </n-space>
            </n-radio-group>
          </div>
        </div>
      </n-card>

      <!-- 目标 + 专业 -->
      <n-card title="目标与背景" :bordered="false" class="section-card">
        <div class="card-grid">
          <div class="card-grid-item">
            <label class="field-label">🏆 学习目标</label>
            <n-radio-group v-model:value="form.learningGoal" name="goal">
              <n-space vertical :size="8">
                <n-radio
                  v-for="opt in goalOptions"
                  :key="opt.value"
                  :value="opt.value"
                >
                  <span class="goal-opt">{{ opt.icon }} {{ opt.label }}</span>
                </n-radio>
              </n-space>
            </n-radio-group>
          </div>
          <div class="card-grid-item">
            <label class="field-label">
              专业/年级
              <n-tag type="default" size="tiny" round style="margin-left:6px">可选</n-tag>
            </label>
            <n-input
              v-model:value="form.majorAndGrade"
              placeholder="例如：计算机科学 大三"
              size="large"
              clearable
            />
          </div>
        </div>
      </n-card>

      <!-- 提交区域 -->
      <n-card :bordered="false" class="section-card submit-card">
        <n-space vertical :size="16" align="center">
          <n-button
            type="primary"
            size="large"
            :loading="submitting"
            :disabled="submitting"
            @click="submitForm"
            style="min-width: 240px; height: 48px; font-size: 16px"
          >
            <template #icon><Rocket /></template>
            提交并生成画像
          </n-button>
          <n-text depth="3">
            「不确定」的选项将保持默认值，后续学习中可以随时更新
          </n-text>
        </n-space>
      </n-card>
    </n-space>

    <!-- 结果区域 -->
    <div v-if="result" class="result-section">
      <n-card :bordered="false" class="section-card result-card">
        <div class="result-body" v-html="renderedResult"></div>
      </n-card>
      <n-space justify="center" :size="16" style="margin-top: 20px">
        <n-button type="primary" size="large" @click="$router.push('/profile')">
          <template #icon><PersonOutline /></template>
          查看我的画像
        </n-button>
        <n-button secondary size="large" @click="$router.push('/learning')">
          <template #icon><SchoolOutline /></template>
          开始学习
        </n-button>
      </n-space>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowBack, Rocket, PersonOutline, SchoolOutline
} from '@vicons/ionicons5'
import {
  NCard, NInput, NCheckboxGroup, NCheckbox,
  NRadioGroup, NRadio, NButton, NSpace, NTag, NText
} from 'naive-ui'
import { buildProfileFromFormApi } from '@/api'

const submitting = ref(false)
const result = ref('')
const renderedResult = ref('')

const form = reactive({
  strengths: '',
  weaknesses: '',
  preferredResourceTypes: [],
  learningStyle: '不确定',
  learningPace: '不确定',
  learningGoal: '不确定',
  majorAndGrade: ''
})

const resourceTypeOptions = [
  { value: '课程文档', icon: '📄', label: '课程文档' },
  { value: 'PPT课件', icon: '📊', label: 'PPT课件' },
  { value: '教学视频', icon: '🎬', label: '教学视频' },
  { value: '代码案例', icon: '💻', label: '代码案例' },
  { value: '练习题库', icon: '📝', label: '练习题库' }
]

const learningStyleOptions = [
  { value: '视觉型', label: '视觉型', desc: '喜欢图表、视频等可视化内容' },
  { value: '言语型', label: '言语型', desc: '喜欢文字阅读和讲解' },
  { value: '直觉型', label: '直觉型', desc: '喜欢先了解整体框架再看细节' },
  { value: '分析型', label: '分析型', desc: '喜欢逐步推导，扎实每一步' },
  { value: '不确定', label: '不确定', desc: '暂时不清楚自己的风格' }
]

const learningPaceOptions = [
  { value: '稳扎稳打型', label: '稳扎稳打型', desc: '反复练习巩固，吃透再继续' },
  { value: '快速推进型', label: '快速推进型', desc: '快速浏览后在实践中补缺' },
  { value: '跳跃型', label: '跳跃型', desc: '按兴趣跳转，不受固定顺序约束' },
  { value: '不确定', label: '不确定', desc: '暂时不清楚' }
]

const goalOptions = [
  { value: '求职准备', icon: '💼', label: '求职准备' },
  { value: '学术深造', icon: '🎓', label: '学术深造/考研' },
  { value: '兴趣探索', icon: '🔍', label: '兴趣探索' },
  { value: '考证通关', icon: '📜', label: '考证通关' },
  { value: '课程考试', icon: '📝', label: '课程考试' },
  { value: '不确定', icon: '❓', label: '不确定' }
]

function getStudentId() {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.id || 'anonymous'
  } catch { return 'anonymous' }
}

async function submitForm() {
  submitting.value = true
  result.value = ''
  renderedResult.value = ''
  try {
    const res = await buildProfileFromFormApi({
      studentId: getStudentId(),
      strengths: form.strengths.trim() || '不确定',
      weaknesses: form.weaknesses.trim() || '不确定',
      preferredResourceTypes:
        form.preferredResourceTypes.length > 0
          ? form.preferredResourceTypes
          : ['课程文档'],
      learningStyle: form.learningStyle || '不确定',
      learningPace: form.learningPace || '不确定',
      learningGoal: form.learningGoal || '不确定',
      majorAndGrade: form.majorAndGrade.trim() || ''
    })
    const data = res.data?.data
    if (data) {
      result.value = data.message
      renderedResult.value = renderMarkdown(data.message)
      if (data.isComplete) {
        ElMessage.success('🎉 画像构建完成！')
      } else {
        ElMessage.info('📝 画像已更新，部分维度待后续完善')
      }
      setTimeout(() => {
        document.querySelector('.result-section')?.scrollIntoView({
          behavior: 'smooth', block: 'start'
        })
      }, 200)
    }
  } catch (e) {
    ElMessage.error('提交失败: ' + (e.response?.data?.message || e.message))
  } finally {
    submitting.value = false
  }
}

function renderMarkdown(content) {
  if (!content) return ''
  return content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/### (.+)/g, '<h3>$1</h3>')
    .replace(/\n/g, '<br>')
}
</script>

<style scoped>
.profile-build-page {
  max-width: 900px;
  margin: 0 auto;
  padding: 32px 24px 80px;
}

/* Hero */
.page-hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 28px;
}
.hero-title {
  font-size: 28px;
  font-weight: 800;
  color: #1a1a2e;
  margin: 0 0 8px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.hero-icon { font-size: 32px; }
.hero-desc {
  font-size: 15px;
  color: #6b7280;
  margin: 0;
  line-height: 1.6;
}

/* Cards */
.section-card {
  border-radius: 16px;
  transition: box-shadow 0.25s;
}
.section-card:hover {
  box-shadow: 0 4px 20px rgba(0,0,0,0.06);
}
.section-card :deep(.n-card-header) {
  padding: 20px 24px 12px;
  font-size: 17px;
  font-weight: 700;
}
.section-card :deep(.n-card__content) {
  padding: 8px 24px 20px;
}

/* Field labels */
.field-label {
  display: flex;
  align-items: center;
  font-size: 14px;
  font-weight: 600;
  color: #1a1a2e;
  margin-bottom: 10px;
}

/* Grid */
.card-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 28px;
}
.card-grid-item {
  min-width: 0;
}

/* Checkbox / Radio */
.check-label { font-size: 14px; }
.radio-main {
  font-weight: 600;
  font-size: 14px;
  color: #1a1a2e;
}
.radio-sub {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.goal-opt { font-size: 14px; }

/* Submit card */
.submit-card {
  text-align: center;
  background: linear-gradient(135deg, #faf9ff 0%, #f3f0ff 100%);
}
.submit-card :deep(.n-card__content) {
  padding: 28px 24px;
}

/* Result */
.result-section {
  margin-top: 28px;
  animation: fadeInUp 0.4s ease-out;
}
@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(24px); }
  to   { opacity: 1; transform: translateY(0); }
}
.result-body {
  font-size: 15px;
  line-height: 1.9;
  color: #1a1a2e;
}
.result-body :deep(strong) {
  font-weight: 700;
  color: #7c3aed;
}
.result-body :deep(h3) {
  font-size: 17px;
  font-weight: 700;
  margin: 16px 0 8px;
  color: #1a1a2e;
}

/* Responsive */
@media (max-width: 700px) {
  .profile-build-page {
    padding: 20px 12px 60px;
  }
  .page-hero {
    flex-direction: column;
    gap: 16px;
  }
  .hero-title { font-size: 24px; }
  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
