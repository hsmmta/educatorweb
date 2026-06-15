<template>
  <div class="auth-container">
    <div class="auth-bg">
      <div class="bg-shape shape-1"></div>
      <div class="bg-shape shape-2"></div>
      <div class="bg-shape shape-3"></div>
    </div>
    <el-card class="auth-card">
      <div class="auth-header">
        <div class="logo-icon">✦</div>
        <h2>智学派</h2>
        <p>登录你的账号，继续学习之旅</p>
      </div>

      <!-- 登录方式切换 -->
      <div class="login-tabs">
        <button
          :class="['tab-btn', { active: loginMode === 'password' }]"
          @click="switchMode('password')"
        >
          密码登录
        </button>
        <button
          :class="['tab-btn', { active: loginMode === 'code' }]"
          @click="switchMode('code')"
        >
          验证码登录
        </button>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" label-position="top" class="auth-form">
        <el-form-item prop="phone">
          <el-input v-model="form.phone" placeholder="手机号" size="large">
            <template #prefix>
              <el-icon><Iphone /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <!-- 密码登录模式 -->
        <template v-if="loginMode === 'password'">
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              size="large"
              show-password
              @keyup.enter="handleLogin"
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>
        </template>

        <!-- 验证码登录模式 -->
        <template v-else>
          <el-form-item prop="code">
            <div class="code-row">
              <el-input v-model="form.code" placeholder="验证码" size="large" class="code-input">
                <template #prefix>
                  <el-icon><Key /></el-icon>
                </template>
              </el-input>
              <el-button
                :type="codeCountdown > 0 ? 'info' : 'primary'"
                :disabled="codeCountdown > 0 || !form.phone"
                @click="sendCode"
                size="large"
                class="code-btn"
              >
                {{ codeCountdown > 0 ? `${codeCountdown}s 后重发` : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>
        </template>

        <el-form-item>
          <el-button
            type="primary"
            @click="handleLogin"
            :loading="loading"
            size="large"
            class="submit-btn"
          >
            {{ loginMode === 'password' ? '登录' : '验证码登录' }}
          </el-button>
          <el-button @click="$router.push('/register')" size="large" class="link-btn">
            没有账号？去注册
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Iphone, Lock, Key } from '@element-plus/icons-vue'
import { loginApi } from '@/api/auth'

const router = useRouter()
const formRef = ref()
const loading = ref(false)
const loginMode = ref('password')
const codeCountdown = ref(0)
const sentCode = ref('')

const form = reactive({
  phone: '',
  password: '',
  code: ''
})

const generateCode = () => {
  const len = Math.floor(Math.random() * 3) + 4
  let code = ''
  for (let i = 0; i < len; i++) {
    code += Math.floor(Math.random() * 10)
  }
  return code
}

const sendCode = () => {
  if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    ElMessage.warning('请先输入正确的手机号')
    return
  }
  const code = generateCode()
  sentCode.value = code
  console.log(`📱 [模拟验证码] 手机号: ${form.phone}  验证码: ${code}`)
  ElMessage.success('验证码已发送（查看控制台）')

  codeCountdown.value = 60
  const timer = setInterval(() => {
    codeCountdown.value--
    if (codeCountdown.value <= 0) {
      clearInterval(timer)
    }
  }, 1000)
}

const validateCode = (_rule, value, callback) => {
  if (loginMode.value !== 'code') return callback()
  if (!value) {
    callback(new Error('请输入验证码'))
  } else if (!sentCode.value) {
    callback(new Error('请先获取验证码'))
  } else if (value !== sentCode.value) {
    callback(new Error('验证码错误'))
  } else {
    callback()
  }
}

const rules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号', trigger: 'blur' }
  ],
  password: [
    {
      validator: (_rule, value, callback) => {
        if (loginMode.value === 'code') return callback()
        if (!value) callback(new Error('请输入密码'))
        else callback()
      },
      trigger: 'blur'
    }
  ],
  code: [{ validator: validateCode, trigger: 'blur' }]
}

const switchMode = (mode) => {
  loginMode.value = mode
  form.password = ''
  form.code = ''
  sentCode.value = ''
  formRef.value?.clearValidate()
}

const handleLogin = async () => {
  if (!formRef.value) return
  await formRef.value.validate()
  loading.value = true
  try {
    const payload = {
      phone: form.phone,
      loginMode: loginMode.value
    }
    if (loginMode.value === 'password') {
      payload.password = form.password
    } else {
      payload.code = form.code
    }
    const res = await loginApi(payload)
    if (res.data.code === 200) {
      const user = res.data.data
      localStorage.setItem('token', user.token || 'dummy-token')
      localStorage.setItem('userInfo', JSON.stringify(user))
      ElMessage.success('登录成功')
      router.push('/home')
    } else {
      ElMessage.error(res.data.message || '登录失败')
    }
  } catch (err) {
    const msg = err?.response?.data?.message || '网络错误，请稍后重试'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-container {
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
  overflow: hidden;
  background: #f0f4ff;
}

.auth-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.bg-shape {
  position: absolute;
  border-radius: 50%;
  opacity: 0.12;
}

.shape-1 {
  width: 600px;
  height: 600px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  top: -200px;
  right: -150px;
  animation: float 8s ease-in-out infinite;
}

.shape-2 {
  width: 400px;
  height: 400px;
  background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);
  bottom: -100px;
  left: -100px;
  animation: float 10s ease-in-out infinite reverse;
}

.shape-3 {
  width: 300px;
  height: 300px;
  background: linear-gradient(135deg, #fa709a 0%, #fee140 100%);
  top: 50%;
  left: 60%;
  animation: float 7s ease-in-out infinite 2s;
}

@keyframes float {
  0%, 100% { transform: translateY(0) scale(1); }
  50% { transform: translateY(-30px) scale(1.05); }
}

.auth-card {
  width: 440px;
  border-radius: 20px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.08), 0 8px 20px rgba(0, 0, 0, 0.04);
  z-index: 1;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.6);
}

.auth-header {
  text-align: center;
  padding: 8px 0 16px;
}

.logo-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 12px;
  border-radius: 16px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  font-size: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 15px rgba(102, 126, 234, 0.35);
}

.auth-header h2 {
  font-size: 22px;
  font-weight: 700;
  color: #1a1a2e;
  margin: 0 0 6px;
}

.auth-header p {
  color: #8890a0;
  font-size: 14px;
  margin: 0;
}

/* 登录方式切换 */
.login-tabs {
  display: flex;
  background: #f2f3f7;
  border-radius: 12px;
  padding: 4px;
  margin-bottom: 20px;
}

.tab-btn {
  flex: 1;
  padding: 10px 0;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: #8890a0;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s;
}

.tab-btn.active {
  background: #fff;
  color: #667eea;
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.auth-form {
  margin-top: 4px;
}

:deep(.el-form-item__label) {
  display: none;
}

:deep(.el-input__wrapper) {
  border-radius: 12px;
  box-shadow: 0 0 0 1px #e4e7ed inset;
  transition: all 0.2s;
}

:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #c0c4cc inset;
}

:deep(.el-input.is-focus .el-input__wrapper) {
  box-shadow: 0 0 0 1px #667eea inset;
}

.code-row {
  display: flex;
  gap: 12px;
}

.code-input {
  flex: 1;
}

.code-btn {
  min-width: 130px;
  border-radius: 12px;
  font-weight: 500;
}

.submit-btn {
  width: 100%;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
  height: 46px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  box-shadow: 0 4px 15px rgba(102, 126, 234, 0.35);
  transition: all 0.3s;
}

.submit-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px rgba(102, 126, 234, 0.45);
}

.link-btn {
  width: 100%;
  border-radius: 12px;
  border: none;
  color: #667eea;
  font-weight: 500;
  margin-left: 0 !important;
  margin-top: 8px;
}
</style>
