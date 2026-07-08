import axios from 'axios'

export const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)

// ==================== 认证 ====================
export const registerApi = (data) => request.post('/auth/register', data)
export const loginApi = (data) => request.post('/auth/login', data)
export const updateProfileApi = (data) => request.put('/auth/profile', data)
export const changePasswordApi = (data) => request.put('/auth/password', data)

// ==================== 学生画像 ====================
/** 获取6维学习画像 */
export const getStudentProfileApi = (studentId) => request.get(`/profile/${studentId}`)
/** 获取画像简要信息（不存在时不报错） */
export const getProfileSummaryApi = (studentId) => request.get(`/profile/${studentId}/summary`)

// ==================== 对话式画像构建 ====================
/** 开始画像构建对话 */
export const startProfileChatApi = (data) => request.post('/profile/chat/start', data)
/** 发送消息 */
export const sendProfileChatMsgApi = (data) => request.post('/profile/chat/message', data)
/** 获取对话历史 */
export const getChatHistoryApi = (sessionId) => request.get(`/profile/chat/history/${sessionId}`)
/** 获取活跃会话 */
export const getActiveChatSessionApi = (studentId) => request.get(`/profile/chat/active/${studentId}`)
/** 快速更新画像 */
export const quickUpdateProfileApi = (data) => request.post('/profile/chat/quick-update', data)
/** 表单式构建画像（推荐） */
export const buildProfileFromFormApi = (data) => request.post('/profile/build-from-form', data)

// ==================== 学习路径与推荐 ====================
/** 获取学习路径规划 */
export const getLearningPathApi = (studentId, target) =>
  request.get(`/push/path/${studentId}`, { params: { target } })
/** 获取今日推荐 */
export const getRecommendationsApi = (studentId, target) =>
  request.get(`/push/recommend/${studentId}`, { params: { target } })
/** 更新学习进度 */
export const updateProgressApi = (studentId, data) =>
  request.post(`/push/progress/${studentId}`, data)

// ==================== 话题推送 (Topic Push) ====================
/** SSE 订阅推送通知 */
export const subscribePushApi = (studentId) => {
  return new EventSource(`/api/push/subscribe?studentId=${encodeURIComponent(studentId)}`)
}

/** 获取推送历史 */
export const getPushResultsApi = (studentId) =>
  request.get('/push/results', { params: { studentId } })

/** 获取最新推送 */
export const getLatestPushApi = (studentId) =>
  request.get('/push/latest', { params: { studentId } })

/** 获取页面上下文（最近学过 + 薄弱点 + 搜索补全） */
export const getPushContextApi = (studentId, q) =>
  request.get('/push/context', { params: { studentId, q } })

// ==================== 资源生成 (SSE) ====================
/**
 * 通过 SSE 流式生成资源。
 * 返回 EventSource 实例，调用方负责关闭。
 */
export function createGenerateStream(reqBody, callbacks) {
  const token = localStorage.getItem('token')
  const url = `/api/generate`

  // 使用 fetch + ReadableStream 以支持 POST SSE
  const controller = new AbortController()

  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'Authorization': `Bearer ${token}` } : {})
    },
    body: JSON.stringify(reqBody),
    signal: controller.signal
  }).then(async (response) => {
    if (!response.ok) {
      const text = await response.text()
      callbacks.onError?.(new Error(`HTTP ${response.status}: ${text}`))
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      // 按换行分割 SSE 事件
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          try {
            const data = JSON.parse(line.substring(5).trim())
            callbacks.onEvent?.(data)
          } catch (e) {
            // 忽略解析错误
          }
        }
      }
    }

    callbacks.onComplete?.()
  }).catch(err => {
    if (err.name !== 'AbortError') {
      callbacks.onError?.(err)
    }
  })

  return controller
}

export default request