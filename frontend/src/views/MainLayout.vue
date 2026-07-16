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
        <el-badge :value="pushNotificationCount" :hidden="pushNotificationCount === 0" class="push-bell">
          <el-icon :size="20" style="cursor: pointer" @click="goToPush">
            <Bell />
          </el-icon>
        </el-badge>
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
    <main class="layout-main" :class="{ 'is-chat': $route.path === '/chat' }">
      <router-view />
    </main>

    <!-- AI 语音助手 -->
    <VoiceAssistant />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElNotification } from 'element-plus'
import { ArrowDown, User, UserFilled, SwitchButton, ChatDotRound, FolderOpened, Position, Bell } from '@element-plus/icons-vue'
import VoiceAssistant from '@/components/VoiceAssistant.vue'
import { subscribePushApi } from '@/api/index.js'

const router = useRouter()
const userInfo = ref({})
const pushNotificationCount = ref(0)

let sseSource = null

function connectSSE(studentId) {
  // Prevent duplicate connections
  if (sseSource) return

  try {
    const es = subscribePushApi(studentId)
    sseSource = es

    es.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'REPORT_UPDATED') {
          window.dispatchEvent(new CustomEvent('report-updated'))
          return
        }
        if (data.type === 'PROFICIENCY_MILESTONE') {
          ElNotification({
            title: '🎉 掌握度达标',
            message: '「' + data.concept + '」掌握度已达 ' + data.proficiency + '%' + (data.nextNode ? '，建议继续学习：' + data.nextNode : ''),
            type: 'success',
            duration: 8000,
            onClick() {
              if (data.nextNode) {
                window.location.href = '/chat?topic=' + encodeURIComponent(data.nextNode) + '&mode=quiz'
              }
            }
          })
          return
        }
        if (data.triggerType === 'PATH_UPDATED') {
          ElNotification({
            title: '学习路径已更新',
            message: `你的画像发生了变化，学习路径已自动调整（剩余 ${data.resourceCount} 个节点）`,
            type: 'success',
            duration: 5000,
            onClick: goToPush
          })
        } else if (data.triggerType) {
          pushNotificationCount.value++
          ElNotification({
            title: '资源推送',
            message: `已为你推送 ${data.resourceCount} 个学习资源（${data.triggerType === 'COUNT' ? '话题触发' : '定时推送'}）`,
            type: 'info',
            duration: 5000,
            onClick: goToPush
          })
          window.dispatchEvent(new CustomEvent('push-refresh'))
        }
      } catch { /* ignore parse errors */ }
    }

    es.onerror = () => {
      // EventSource has built-in reconnection with backoff.
      // We only need to close on fatal errors (e.g., 401, 404).
      if (es.readyState === EventSource.CLOSED) {
        sseSource = null
      }
      // Otherwise, let the browser's built-in reconnect handle it.
      // The built-in delay starts at ~3s and increases.
    }
  } catch { /* SSE not supported */ }
}

function disconnectSSE() {
  if (sseSource) {
    sseSource.close()
    sseSource = null
  }
}

onMounted(() => {
  const info = localStorage.getItem('userInfo')
  if (info) {
    try { userInfo.value = JSON.parse(info) } catch (e) { userInfo.value = {} }
  }
  const studentId = getStudentId()
  if (studentId) {
    connectSSE(studentId)
  }
})

onUnmounted(() => {
  disconnectSSE()
})

function getStudentId() {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.id || ''
  } catch { return '' }
}

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

function goToPush() {
  pushNotificationCount.value = 0
  router.push('/push')
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
.push-bell { margin-right: 12px; }
.user-avatar {
  display: flex; align-items: center; gap: 8px; cursor: pointer;
  padding: 4px 12px 4px 4px; border-radius: 20px; transition: background 0.2s;
}
.user-avatar:hover { background: #f2f3f7; }
.user-name { font-size: 14px; font-weight: 500; color: #1a1a2e; }
.arrow { color: #909399; font-size: 12px; }

.layout-main { padding-bottom: 60px; }
.layout-main.is-chat { padding-bottom: 0; }

@media (max-width: 600px) {
  .navbar { padding: 0 16px; }
}
</style>
