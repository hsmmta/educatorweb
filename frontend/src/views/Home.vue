<template>
  <div class="home">
    <!-- Ambient background orbs -->
    <div class="bg-orbs">
      <div class="orb orb-1"></div>
      <div class="orb orb-2"></div>
      <div class="orb orb-3"></div>
    </div>

    <div class="hero-center">
      <!-- Logo mark -->
      <div class="logo-mark">
        <span class="logo-glyph">✦</span>
      </div>

      <!-- Site title -->
      <h1 class="site-name">智学派</h1>

      <!-- Tagline -->
      <p class="tagline">你的专属 AI 学习助手 — 私人智库 · 智能辅导 · 资源生成</p>

      <!-- Action buttons -->
      <div class="hero-actions">
        <button class="btn-primary" @click="$router.push('/chat')">
          <span class="btn-icon">💬</span>
          智能辅导
        </button>
        <button class="btn-secondary" @click="$router.push('/profile')">
          <span class="btn-icon">👤</span>
          个人中心
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const userInfo = ref({})

onMounted(() => {
  const info = localStorage.getItem('userInfo')
  if (info) {
    try { userInfo.value = JSON.parse(info) } catch (e) { userInfo.value = {} }
  }
})
</script>

<style scoped>
.home {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: calc(100vh - 48px);
  overflow: hidden;
  background: linear-gradient(180deg, #f8f9fe 0%, #f0f2fb 50%, #eef0fa 100%);
}

/* Ambient floating orbs */
.bg-orbs {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.35;
  animation: float 12s ease-in-out infinite;
}

.orb-1 {
  width: 480px;
  height: 480px;
  background: radial-gradient(circle, rgba(102, 126, 234, 0.5), transparent);
  top: -15%;
  left: -10%;
  animation-delay: 0s;
}

.orb-2 {
  width: 380px;
  height: 380px;
  background: radial-gradient(circle, rgba(118, 75, 162, 0.4), transparent);
  bottom: -10%;
  right: -8%;
  animation-delay: -4s;
  animation-duration: 15s;
}

.orb-3 {
  width: 300px;
  height: 300px;
  background: radial-gradient(circle, rgba(168, 130, 230, 0.35), transparent);
  top: 40%;
  left: 55%;
  animation-delay: -8s;
  animation-duration: 18s;
}

@keyframes float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33%  { transform: translate(30px, -40px) scale(1.08); }
  66%  { transform: translate(-20px, 20px) scale(0.95); }
}

/* Centered content */
.hero-center {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 0 24px;
  animation: fadeUp 0.8s ease-out;
}

@keyframes fadeUp {
  from { opacity: 0; transform: translateY(24px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* Logo mark */
.logo-mark {
  margin-bottom: 20px;
}

.logo-glyph {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border-radius: 22px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  font-size: 36px;
  box-shadow: 0 12px 40px rgba(102, 126, 234, 0.35);
  animation: pulse-glow 3s ease-in-out infinite;
}

@keyframes pulse-glow {
  0%, 100% { box-shadow: 0 12px 40px rgba(102, 126, 234, 0.35); }
  50%      { box-shadow: 0 16px 56px rgba(102, 126, 234, 0.5); }
}

/* Site name */
.site-name {
  font-size: 42px;
  font-weight: 800;
  color: #1a1a2e;
  letter-spacing: 3px;
  margin: 0 0 12px;
}

/* Tagline */
.tagline {
  font-size: 15px;
  color: #7a7f90;
  margin: 0 0 40px;
  max-width: 480px;
  line-height: 1.6;
}

/* Buttons */
.hero-actions {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  justify-content: center;
}

.btn-primary {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 36px;
  border: none;
  border-radius: 14px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.25s;
  box-shadow: 0 4px 20px rgba(102, 126, 234, 0.3);
}

.btn-primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 30px rgba(102, 126, 234, 0.45);
}

.btn-secondary {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 36px;
  border: 1.5px solid #d4d8e6;
  border-radius: 14px;
  background: #fff;
  color: #4a4f5e;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.25s;
}

.btn-secondary:hover {
  border-color: #667eea;
  color: #667eea;
  background: #f8f6ff;
  transform: translateY(-2px);
}

.btn-icon {
  font-size: 20px;
}

@media (max-width: 500px) {
  .site-name { font-size: 32px; }
  .tagline { font-size: 14px; }
  .hero-actions { flex-direction: column; align-items: center; }
  .btn-primary, .btn-secondary { width: 240px; justify-content: center; }
}
</style>
