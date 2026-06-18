<template>
  <div class="layout">
    <!-- 顶部导航栏 -->
    <header class="navbar">
      <div class="nav-left">
        <router-link to="/home" class="logo">
          <span class="logo-icon">✦</span>
          <span class="logo-text">智学派</span>
        </router-link>
        <nav class="nav-links">
          <router-link
            v-for="item in navItems"
            :key="item.key"
            :to="item.path"
            :class="['nav-item', { active: $route.path.startsWith(item.path) }]"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </router-link>
        </nav>
      </div>
      <div class="nav-right">
        <el-badge :value="3" :max="99" class="notice-badge">
          <el-button circle :icon="Bell" />
        </el-badge>
        <el-dropdown @command="handleCommand" trigger="click">
          <div class="user-avatar">
            <el-avatar :size="36" :icon="UserFilled" />
            <span class="user-name">{{ userInfo?.nickname || '同学' }}</span>
            <el-icon class="arrow"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Bell, User, UserFilled, SwitchButton } from '@element-plus/icons-vue'

const router = useRouter()
const userInfo = ref({})

onMounted(() => {
  const info = localStorage.getItem('userInfo')
  if (info) {
    try { userInfo.value = JSON.parse(info) } catch (e) { userInfo.value = {} }
  }
})

const navItems = [
  { key: 'thinktank', label: '私人智库', path: '/thinktank', icon: 'FolderOpened' },
  { key: 'tutoring',  label: '智能辅导', path: '/tutoring',  icon: 'Headset' },
  { key: 'learning',  label: '沉浸学习', path: '/learning',  icon: 'Monitor' },
  { key: 'push',      label: '资源推送', path: '/push',      icon: 'Position' },
  { key: 'profile',   label: '个人中心', path: '/profile',   icon: 'User' }
]

const handleCommand = (cmd) => {
  if (cmd === 'logout') {
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    ElMessage.success('已退出登录')
    router.push('/login')
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
  height: 64px; padding: 0 32px;
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

.nav-links { display: flex; gap: 4px; }
.nav-item {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 16px; border-radius: 10px;
  font-size: 14px; font-weight: 500; color: #5a5f72;
  text-decoration: none; transition: all 0.25s;
}
.nav-item:hover { background: #f2f3f7; color: #667eea; }
.nav-item.active, .nav-item.router-link-exact-active,
.nav-item.router-link-active:not(.logo .router-link-active) {
  background: #eef0ff; color: #667eea; font-weight: 600;
}

.nav-right { display: flex; align-items: center; gap: 16px; }
.notice-badge { cursor: pointer; }
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
  .nav-links .nav-item span { display: none; }
}
</style>
