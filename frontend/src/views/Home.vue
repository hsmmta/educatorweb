<template>
  <div class="home">
    <el-container>
      <el-header>
        <div class="header-left">
          <h2>📚 个性化智能学习系统</h2>
        </div>
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              {{ userInfo?.nickname || userInfo?.username || '用户' }}
              <el-icon><arrow-down /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人画像</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-card class="feature-card">
              <template #header>
                <div class="card-header">
                  <span>🎯 动态学生画像</span>
                </div>
              </template>
              <p>通过对话自动构建6维画像：知识基础、认知风格、易错点偏好、学习目标、兴趣方向、学习进度。</p>
              <el-button type="primary" plain>开始构建</el-button>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="feature-card">
              <template #header>
                <div class="card-header">
                  <span>🤖 多智能体资源生成</span>
                </div>
              </template>
              <p>支持生成文档、思维导图、题目、拓展阅读、视频/动画、代码案例等5+种资源。</p>
              <el-button type="primary" plain>生成资源</el-button>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="feature-card">
              <template #header>
                <div class="card-header">
                  <span>🗺️ 个性化学习路径</span>
                </div>
              </template>
              <p>根据画像动态规划学习顺序，智能推送文档、视频、题库等。</p>
              <el-button type="primary" plain>查看路径</el-button>
            </el-card>
          </el-col>
        </el-row>
        <el-card class="welcome-card" style="margin-top: 20px">
          <h3>欢迎回来，{{ userInfo?.nickname || '同学' }}！</h3>
          <p>今天想学习什么？我可以帮你生成专属学习资料，规划最优学习路线。</p>
          <el-input
            v-model="chatInput"
            placeholder="例如：我是计算机专业大二学生，想深入学习人工智能，目前对Python有基础..."
            type="textarea"
            :rows="3"
          />
          <el-button type="success" style="margin-top: 12px" @click="sendMessage">发送</el-button>
        </el-card>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'

const router = useRouter()
const userInfo = ref({})
const chatInput = ref('')

onMounted(() => {
  const info = localStorage.getItem('userInfo')
  if (info) {
    userInfo.value = JSON.parse(info)
  }
})

const handleCommand = (cmd) => {
  if (cmd === 'logout') {
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    ElMessage.success('已退出登录')
    router.push('/login')
  } else if (cmd === 'profile') {
    ElMessage.info('画像功能开发中，敬请期待')
  }
}

const sendMessage = () => {
  if (!chatInput.value.trim()) {
    ElMessage.warning('请输入内容')
    return
  }
  ElMessage.success('即将开启智能画像构建')
}
</script>

<style scoped>
.home {
  height: 100vh;
}
.el-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background-color: #ffffff;
  border-bottom: 1px solid #eaeef2;
  padding: 0 24px;
}
.header-right .user-info {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
}
.feature-card {
  height: 200px;
  transition: transform 0.2s;
}
.feature-card:hover {
  transform: translateY(-4px);
}
.welcome-card {
  background: #f5f7fa;
}
</style>