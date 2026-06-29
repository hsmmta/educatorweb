<template>
  <div class="layout">
    <!-- 顶部导航栏 -->
    <header class="navbar">
      <div class="nav-left">
        <router-link to="/home" class="logo">
          <span class="logo-icon">✦</span>
          <span class="logo-text">智学派</span>
        </router-link>
      </div>
      <div class="nav-right">
        <el-dropdown @command="handleCommand" trigger="click">
          <div class="user-avatar">
            <el-avatar :size="32" :icon="UserFilled" />
            <span class="user-name">{{ userInfo?.nickname || '同学' }}</span>
            <el-icon class="arrow"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="chat">
                <el-icon><ChatDotRound /></el-icon>AI 对话
              </el-dropdown-item>
              <el-dropdown-item command="thinktank">
                <el-icon><FolderOpened /></el-icon>私人智库
              </el-dropdown-item>
              <el-dropdown-item command="push">
                <el-icon><Position /></el-icon>资源推送
              </el-dropdown-item>
              <el-dropdown-item divided command="profile">
                <el-icon><User /></el-icon>个人中心
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- 子页面内容 -->
    <main class="layout-main">
      <router-view />
    </main>

    <!-- AI 语音助手 -->
    <VoiceAssistant />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, User, UserFilled, SwitchButton, ChatDotRound, FolderOpened, Position } from '@element-plus/icons-vue'
import VoiceAssistant from '@/components/VoiceAssistant.vue'

const router = useRouter()
const userInfo = ref({})

onMounted(() => {
  const info = localStorage.getItem('userInfo')
  if (info) {
    try { userInfo.value = JSON.parse(info) } catch (e) { userInfo.value = {} }
  }
})

const handleCommand = (cmd) => {
  if (cmd === 'logout') {
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    ElMessage.success('已退出登录')
    router.push('/login')
  } else if (cmd === 'chat') {
    router.push('/chat')
  } else if (cmd === 'thinktank') {
    router.push('/thinktank')
  } else if (cmd === 'push') {
    router.push('/push')
  } else if (cmd === 'profile') {
    router.push('/profile')
  }
}
</script>

<style scoped>
.layout { min-height: 100vh; background: #f5f7fb; }

.navbar {
  position: sticky; top: 0; z-index: 100;
  display: flex; justify-content: space-between; align-items: center;
  height: 48px; padding: 0 32px;
  background: rgba(255,255,255,0.85);
  backdrop-filter: blur(20px) saturate(180%);
  border-bottom: 1px solid rgba(0,0,0,0.06);
  box-shadow: 0 1px 8px rgba(0,0,0,0.04);
}

.nav-left { display: flex; align-items: center; gap: 40px; }
.logo { display: flex; align-items: center; gap: 8px; text-decoration: none; }
.logo-icon {
  width: 32px; height: 32px; border-radius: 10px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff; font-size: 18px;
  display: flex; align-items: center; justify-content: center;
}
.logo-text { font-size: 20px; font-weight: 700; color: #1a1a2e; letter-spacing: 1px; }

.nav-right { display: flex; align-items: center; gap: 16px; }
.user-avatar {
  display: flex; align-items: center; gap: 8px; cursor: pointer;
  padding: 4px 12px 4px 4px; border-radius: 20px; transition: background 0.2s;
}
.user-avatar:hover { background: #f2f3f7; }
.user-name { font-size: 14px; font-weight: 500; color: #1a1a2e; }
.arrow { color: #909399; font-size: 12px; }

.layout-main { padding-bottom: 60px; }

@media (max-width: 600px) {
  .navbar { padding: 0 16px; }
}
</style>
