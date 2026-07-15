<template>
  <div class="va-root">
    <!-- 折叠态：浮动按钮 -->
    <button
      v-if="!expanded"
      class="va-toggle-btn"
      @click="open"
      title="AI 语音助手"
    >
      <span class="va-toggle-icon">🤖</span>
      <span class="va-pulse-ring"></span>
    </button>

    <!-- 展开态：面板 -->
    <div v-show="expanded" class="va-panel">
      <!-- 头部 -->
      <div class="va-header">
        <span class="va-title">AI 助手 · 小智</span>
        <button class="va-close" @click="close">✕</button>
      </div>

      <!-- 数字人形象：Live2D 优先，加载失败则 emoji 兜底 -->
      <div class="va-canvas-wrap">
        <Live2DCharacter v-if="expanded" v-show="l2dReady" ref="l2dRef" class="va-l2d" />
        <div v-show="!l2dReady" class="va-avatar">
          <span class="va-avatar-emoji">{{ avatarEmoji }}</span>
          <span v-if="isSpeaking" class="va-speaking-dot"></span>
        </div>
        <div class="va-expression-label">{{ expressionLabel }}</div>
      </div>

      <!-- 对话区 -->
      <div class="va-convo" ref="convoRef">
        <div
          v-for="(msg, i) in messages"
          :key="i"
          :class="['va-msg', msg.role]"
        >
          <span class="va-msg-avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</span>
          <span class="va-msg-text">{{ msg.text }}</span>
        </div>
      </div>

      <!-- 控制栏 -->
      <div class="va-controls">
        <button
          :class="['va-mic-btn', { listening: isListening }]"
          :disabled="!speechSupported"
          @click="toggleListening"
          :title="speechSupported ? '点击说话' : '浏览器不支持语音识别，请使用文字输入'"
        >
          <span v-if="!speechSupported">🚫</span>
          <span v-else-if="isListening">🔴</span>
          <span v-else>🎤</span>
        </button>
        <input
          v-model="textInput"
          class="va-text-input"
          placeholder="输入文字..."
          @keyup.enter="sendText"
        />
        <button class="va-send-btn" @click="sendText" :disabled="!textInput.trim()">
          ➤
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted, nextTick, computed, defineAsyncComponent } from 'vue'
import { useRouter } from 'vue-router'
// 异步组件：pixi.js / pixi-live2d-display 仅在面板首次打开、组件挂载时才加载，
// 避免重型库在应用启动阶段被求值而拖垮整个布局，失败也被隔离在组件内。
const Live2DCharacter = defineAsyncComponent(() => import('./Live2DCharacter.vue'))

const router = useRouter()

// ==================== UI STATE ====================
const expanded = ref(false)
const isListening = ref(false)
const textInput = ref('')
const messages = reactive([])
const convoRef = ref(null)
const l2dRef = ref(null)
const l2dReady = ref(false)
const expressionLabel = ref('')
const speechSupported = ref(false)

// ==================== AVATAR / EXPRESSION ====================
let isSpeaking = false

const EXPR_LABELS = {
  happy: '😊 开心', surprised: '😮 惊讶', thinking: '🤔 思考中',
  speaking: '🗣️ 说话中', neutral: '',
}

const avatarEmoji = computed(() => {
  if (isSpeaking) return '🗣️'
  if (isListening.value) return '👂'
  switch (expressionLabel.value) {
    case '😊 开心': return '😊'
    case '😮 惊讶': return '😮'
    case '🤔 思考中': return '🤔'
    default: return '🐱'
  }
})

function setExpression(expr) {
  expressionLabel.value = EXPR_LABELS[expr] || ''
  l2dRef.value?.setExpression?.(expr)
}

// ==================== SPEECH ====================

let recognition = null
let synth = window.speechSynthesis
let currentUtterance = null

function initSpeech() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition
  if (SR) {
    speechSupported.value = true
    recognition = new SR()
    recognition.lang = 'zh-CN'
    recognition.interimResults = false
    recognition.continuous = false

    recognition.onresult = (event) => {
      const text = event.results[0][0].transcript.trim()
      if (text) {
        addMessage('user', text)
        handleIntent(text)
      }
    }

    recognition.onerror = (event) => {
      isListening.value = false
      setExpression('neutral')
      // Web Speech API 固有的几类失败，给用户明确反馈（'aborted'=用户主动停止，不提示）
      const tip = {
        'no-speech': '没有听到声音，请靠近麦克风再说一次 🎤',
        'audio-capture': '未检测到麦克风设备，请检查设备连接',
        'not-allowed': '麦克风权限被拒绝，请在浏览器允许麦克风后重试',
        'service-not-allowed': '麦克风权限被拒绝，请在浏览器允许麦克风后重试',
        'network': '语音识别需要联网，当前网络异常，可改用文字输入',
      }[event.error]
      if (tip) addMessage('bot', tip)
    }

    recognition.onend = () => {
      isListening.value = false
      if (!isSpeaking) setExpression('neutral')
    }
  }
}

function toggleListening() {
  if (isListening.value) {
    recognition?.stop()
    isListening.value = false
  } else {
    try {
      recognition?.start()
      isListening.value = true
      setExpression('surprised')
    } catch (e) {
      // already started
    }
  }
}

function speak(text) {
  if (!synth) return

  // Cancel any ongoing speech
  synth.cancel()

  const utterance = new SpeechSynthesisUtterance(text)

  // Try to pick a Chinese voice
  const voices = synth.getVoices()
  const zhVoice = voices.find(v => v.lang.startsWith('zh')) || voices[0]
  if (zhVoice) utterance.voice = zhVoice
  utterance.rate = 1.1
  utterance.pitch = 1.05
  utterance.volume = 1

  utterance.onstart = () => {
    isSpeaking = true
    setExpression('speaking')
    l2dRef.value?.playTalkMotion?.()
    l2dRef.value?.startLipSync?.()
  }

  utterance.onend = () => {
    isSpeaking = false
    setExpression('neutral')
    l2dRef.value?.stopLipSync?.()
  }

  utterance.onerror = () => {
    isSpeaking = false
    setExpression('neutral')
    l2dRef.value?.stopLipSync?.()
  }

  currentUtterance = utterance
  synth.speak(utterance)
}

// ==================== INTENT MATCHING ====================

const ROUTE_MAP = {
  '个人中心': '/profile',
  '个人资料': '/profile',
  '我的资料': '/profile',
  '智能辅导': '/tutoring',
  '辅导': '/tutoring',
  '答疑': '/tutoring',
  '提问': '/tutoring',
  '私人智库': '/thinktank',
  '智库': '/thinktank',
  '知识库': '/thinktank',
  '上传': '/thinktank',
  '沉浸学习': '/learning',
  '学习': '/learning',
  '生成资源': '/learning',
  '资源推送': '/push',
  '推送': '/push',
  '学习路径': '/push',
  '首页': '/home',
  '主页': '/home',
  '主页面': '/home',
}

function handleIntent(text) {
  const t = text.toLowerCase().replace(/[，。！？、]/g, '')

  // Navigation intent
  const navPatterns = ['打开', '去', '前往', '导航到', '帮我打开', '帮我进入', '跳转到', '进入', '回到', '返回']
  let isNav = false
  for (const pat of navPatterns) {
    if (t.includes(pat)) {
      isNav = true
      break
    }
  }

  if (isNav) {
    for (const [keyword, route] of Object.entries(ROUTE_MAP)) {
      if (t.includes(keyword)) {
        const pageName = Object.keys(ROUTE_MAP).find(k => ROUTE_MAP[k] === route) || keyword
        addMessage('bot', `好的，正在为你打开${pageName}页面 ✨`)
        speak(`好的，正在为你打开${pageName}页面`)
        setTimeout(() => router.push(route), 800)
        return
      }
    }
    // Didn't match a specific page
    addMessage('bot', '抱歉，我没有找到对应的页面，你可以试试说"打开个人中心"或"去学习页面"')
    speak('抱歉，我没有找到对应的页面，你可以试试说打开个人中心或去学习页面')
    return
  }

  // Introduction / help intent
  const introPatterns = ['介绍', '功能', '能做什么', '有什么', '说明', '帮助', '你会什么', '怎么用']
  let isIntro = false
  for (const pat of introPatterns) {
    if (t.includes(pat)) {
      isIntro = true
      break
    }
  }

  if (isIntro) {
    const intro = '智学派是一个AI学习平台，包含五大核心功能：私人智库可以上传你的学习资料构建专属知识库；智能辅导提供三级检索的AI答疑；沉浸学习能够生成文档、PPT、题库等七种学习资源；资源推送根据你的学习画像动态推荐内容；个人中心展示你的六维学习画像。你可以对我说"打开个人中心"来导航到各个页面。'
    addMessage('bot', intro)
    speak(intro)
    return
  }

  // Greetings
  if (/你好|嗨|hello|hi|在吗/.test(t)) {
    const resp = '你好呀！我是小智，你的AI学习助手。有什么可以帮你的吗？'
    addMessage('bot', resp)
    setExpression('happy')
    speak(resp)
    setTimeout(() => { if (!isSpeaking) setExpression('neutral') }, 3000)
    return
  }

  // Thanks
  if (/谢谢|感谢|多谢|thanks/.test(t)) {
    const resp = '不客气！有任何需要随时叫我哦 😊'
    addMessage('bot', resp)
    setExpression('happy')
    speak(resp)
    return
  }

  // Fallback
  const fallback = '你可以对我说"介绍一下系统功能"，或者"打开个人中心"来导航到各个页面哦～'
  addMessage('bot', fallback)
  setExpression('thinking')
  speak(fallback)
}

// ==================== HELPERS ====================

function addMessage(role, text) {
  messages.push({ role, text })
  if (messages.length > 20) messages.shift()
  nextTick(() => {
    if (convoRef.value) {
      convoRef.value.scrollTop = convoRef.value.scrollHeight
    }
  })
}

function sendText() {
  const text = textInput.value.trim()
  if (!text) return
  textInput.value = ''
  addMessage('user', text)
  handleIntent(text)
}

function open() {
  expanded.value = true
  // Live2D 组件随面板 v-if 挂载（在可见容器里全新创建 canvas）；轮询其就绪状态
  let tries = 0
  const poll = setInterval(() => {
    tries++
    const rdy = l2dRef.value?.isReady
    const ready = rdy && (typeof rdy === 'object' ? rdy.value : rdy)
    if (ready) { l2dReady.value = true; clearInterval(poll) }
    else if (tries > 40) { clearInterval(poll) }
  }, 200)
  nextTick(() => {
    if (synth) synth.getVoices()
  })
}

function close() {
  expanded.value = false
  isListening.value = false
  if (recognition) {
    try { recognition.stop() } catch (e) { /* ignore */ }
  }
  if (synth) synth.cancel()
  l2dRef.value?.stopLipSync?.()
  l2dReady.value = false  // 关闭即销毁 Live2D（v-if），重开时先显示 emoji 直到新实例就绪
  isSpeaking = false
  setExpression('neutral')
  messages.length = 0
  expressionLabel.value = ''
}

onMounted(() => {
  initSpeech()
})

onUnmounted(() => {
  isListening.value = false
  if (recognition) {
    try { recognition.stop() } catch (e) { /* ignore */ }
  }
  if (synth) synth.cancel()
  isSpeaking = false
})
</script>

<style scoped>
.va-root {
  position: fixed;
  bottom: 28px;
  right: 28px;
  z-index: 9999;
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ========== Toggle Button ========== */
.va-toggle-btn {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, #667eea, #764ba2);
  box-shadow: 0 8px 28px rgba(102, 126, 234, 0.45);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  transition: transform 0.3s, box-shadow 0.3s;
}
.va-toggle-btn:hover {
  transform: scale(1.08);
  box-shadow: 0 12px 36px rgba(102, 126, 234, 0.55);
}
.va-toggle-icon {
  font-size: 30px;
  position: relative;
  z-index: 1;
}
.va-pulse-ring {
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 2px solid rgba(102, 126, 234, 0.35);
  animation: va-pulse 2s ease-out infinite;
}
@keyframes va-pulse {
  0% { transform: scale(1); opacity: 0.7; }
  100% { transform: scale(1.35); opacity: 0; }
}

/* ========== Panel ========== */
.va-panel {
  width: 380px;
  max-height: 640px;
  background: #fff;
  border-radius: 24px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15), 0 4px 16px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: va-slide-up 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes va-slide-up {
  from { opacity: 0; transform: translateY(30px) scale(0.95); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

/* ========== Header ========== */
.va-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f5;
}
.va-title {
  font-size: 16px;
  font-weight: 700;
  color: #1a1a2e;
  background: linear-gradient(135deg, #667eea, #764ba2);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
.va-close {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: none;
  background: #f2f3f7;
  color: #8890a0;
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.va-close:hover { background: #e8e9ef; color: #1a1a2e; }

/* ========== Canvas / Avatar ========== */
.va-canvas-wrap {
  position: relative;
  height: 280px;
  background: linear-gradient(180deg, #f8f6ff 0%, #f0eeff 100%);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.va-l2d {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
.va-avatar {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.va-avatar-emoji {
  font-size: 96px;
  transition: transform 0.3s;
  animation: va-float 3s ease-in-out infinite;
}
@keyframes va-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-12px); }
}
.va-speaking-dot {
  position: absolute;
  bottom: -8px;
  left: 50%;
  transform: translateX(-50%);
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #667eea;
  animation: va-dot-pulse 0.5s ease-in-out infinite;
}
@keyframes va-dot-pulse {
  0%, 100% { transform: translateX(-50%) scale(1); opacity: 1; }
  50% { transform: translateX(-50%) scale(1.8); opacity: 0.4; }
}
.va-expression-label {
  position: absolute;
  bottom: 10px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 13px;
  font-weight: 500;
  color: #667eea;
  background: rgba(255, 255, 255, 0.85);
  padding: 2px 14px;
  border-radius: 20px;
  pointer-events: none;
  transition: all 0.3s;
}

/* ========== Conversation ========== */
.va-convo {
  flex: 1;
  max-height: 180px;
  overflow-y: auto;
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: #fafbfc;
}
.va-msg {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  animation: va-msg-in 0.3s ease-out;
}
@keyframes va-msg-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
.va-msg-avatar { font-size: 16px; flex-shrink: 0; margin-top: 2px; }
.va-msg-text {
  font-size: 13px;
  line-height: 1.55;
  padding: 8px 12px;
  border-radius: 14px;
  max-width: 85%;
}
.va-msg.user .va-msg-text {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
}
.va-msg.bot .va-msg-text {
  background: #fff;
  color: #3a3f50;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

/* ========== Controls ========== */
.va-controls {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #f0f0f5;
  align-items: center;
}
.va-mic-btn {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  border: none;
  font-size: 18px;
  cursor: pointer;
  flex-shrink: 0;
  background: #f2f3f7;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}
.va-mic-btn:hover { background: #e8e9ef; }
.va-mic-btn.listening {
  background: #fee2e2;
  animation: va-mic-pulse 0.8s ease-in-out infinite;
}
@keyframes va-mic-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.4); }
  50% { box-shadow: 0 0 0 12px rgba(239, 68, 68, 0); }
}
.va-mic-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.va-text-input {
  flex: 1;
  border: 1.5px solid #e8e9ef;
  border-radius: 22px;
  padding: 10px 16px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
  min-width: 0;
}
.va-text-input:focus { border-color: #667eea; }
.va-send-btn {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  font-size: 18px;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}
.va-send-btn:hover { transform: scale(1.05); }
.va-send-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* ========== Responsive ========== */
@media (max-width: 480px) {
  .va-root { bottom: 16px; right: 16px; }
  .va-panel { width: calc(100vw - 32px); max-height: 520px; }
  .va-canvas-wrap { height: 220px; }
  .va-convo { max-height: 130px; }
}
</style>
