<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <el-button text :icon="ArrowLeft" @click="$router.push('/profile')">返回</el-button>
        <h1>编辑资料</h1>
      </div>
    </div>

    <div class="content-area">
      <!-- 修改昵称和邮箱 -->
      <section class="section">
        <h3>基本信息</h3>
        <el-form :model="profileForm" :rules="profileRules" ref="profileFormRef" label-position="top">
          <el-form-item label="手机号">
            <el-input :model-value="userInfo.phone" disabled size="large">
              <template #prefix><el-icon><Iphone /></el-icon></template>
            </el-input>
            <span class="form-hint">手机号不可修改</span>
          </el-form-item>

          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="profileForm.nickname" placeholder="输入新昵称" size="large">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-form-item label="邮箱" prop="email">
            <el-input v-model="profileForm.email" placeholder="输入新邮箱" size="large">
              <template #prefix><el-icon><Message /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-button type="primary" :loading="savingProfile" @click="saveProfile" size="large" class="submit-btn">
            保存修改
          </el-button>
        </el-form>
      </section>

      <!-- 修改密码 -->
      <section class="section">
        <h3>修改密码</h3>
        <el-alert
          title="修改密码需要手机验证码，验证码将输出到浏览器控制台"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 20px"
        />

        <el-form :model="pwdForm" :rules="pwdRules" ref="pwdFormRef" label-position="top">
          <el-form-item label="手机验证码" prop="code">
            <div class="code-row">
              <el-input v-model="pwdForm.code" placeholder="输入验证码" size="large" class="code-input">
                <template #prefix><el-icon><Key /></el-icon></template>
              </el-input>
              <el-button
                :type="pwdCountdown > 0 ? 'info' : 'primary'"
                :disabled="pwdCountdown > 0"
                @click="sendPwdCode"
                size="large"
                class="code-btn"
              >
                {{ pwdCountdown > 0 ? `${pwdCountdown}s 后重发` : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>

          <el-form-item label="新密码" prop="newPassword">
            <el-input v-model="pwdForm.newPassword" type="password" placeholder="至少6位" size="large" show-password>
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input v-model="pwdForm.confirmPassword" type="password" placeholder="再次输入新密码" size="large" show-password>
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-button type="warning" :loading="changingPwd" @click="changePwd" size="large" class="submit-btn">
            修改密码
          </el-button>
        </el-form>
      </section>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, User, Message, Lock, Key, Iphone } from '@element-plus/icons-vue'
import { updateProfileApi, changePasswordApi } from '@/api/auth'

const router = useRouter()
const userInfo = ref({})
const savingProfile = ref(false)
const changingPwd = ref(false)
const pwdCountdown = ref(0)
const sentPwdCode = ref('')

const profileFormRef = ref()
const pwdFormRef = ref()

const profileForm = reactive({
  nickname: '',
  email: ''
})

const pwdForm = reactive({
  code: '',
  newPassword: '',
  confirmPassword: ''
})

const profileRules = {
  nickname: [{ required: true, message: '昵称不能为空', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

const validateConfirmPwd = (_rule, value, callback) => {
  if (value !== pwdForm.newPassword) {
    callback(new Error('两次输入密码不一致'))
  } else {
    callback()
  }
}

const pwdRules = {
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码至少6位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    { validator: validateConfirmPwd, trigger: 'blur' }
  ]
}

onMounted(() => {
  const info = localStorage.getItem('userInfo')
  if (info) {
    try {
      userInfo.value = JSON.parse(info)
      profileForm.nickname = userInfo.value.nickname || ''
      profileForm.email = userInfo.value.email || ''
    } catch (e) {
      userInfo.value = {}
    }
  }
})

const generateCode = () => {
  const len = Math.floor(Math.random() * 3) + 4
  let code = ''
  for (let i = 0; i < len; i++) code += Math.floor(Math.random() * 10)
  return code
}

const sendPwdCode = () => {
  const code = generateCode()
  sentPwdCode.value = code
  console.log(`📱 [修改密码验证码] 手机号: ${userInfo.value.phone}  验证码: ${code}`)
  ElMessage.success('验证码已发送（查看控制台）')

  pwdCountdown.value = 60
  const timer = setInterval(() => {
    pwdCountdown.value--
    if (pwdCountdown.value <= 0) clearInterval(timer)
  }, 1000)
}

// 保存基本资料
const saveProfile = async () => {
  if (!profileFormRef.value) return
  await profileFormRef.value.validate()
  savingProfile.value = true
  try {
    const res = await updateProfileApi({
      phone: userInfo.value.phone,
      nickname: profileForm.nickname,
      email: profileForm.email
    })
    if (res.data.code === 200) {
      // 更新本地存储
      const updated = { ...userInfo.value, ...res.data.data, passwordHash: undefined }
      localStorage.setItem('userInfo', JSON.stringify(updated))
      userInfo.value = updated
      ElMessage.success('资料修改成功')
    } else {
      ElMessage.error(res.data.message || '修改失败')
    }
  } catch (err) {
    const msg = err?.response?.data?.message || '保存失败，请稍后重试'
    ElMessage.error(msg)
  } finally {
    savingProfile.value = false
  }
}

// 修改密码
const changePwd = async () => {
  if (!pwdFormRef.value) return
  await pwdFormRef.value.validate()

  // 验证码校验
  if (pwdForm.code !== sentPwdCode.value) {
    ElMessage.error('验证码错误')
    return
  }

  changingPwd.value = true
  try {
    const res = await changePasswordApi({
      phone: userInfo.value.phone,
      code: pwdForm.code,
      newPassword: pwdForm.newPassword
    })
    if (res.data.code === 200) {
      ElMessage.success('密码修改成功，请重新登录')
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      setTimeout(() => router.push('/login'), 800)
    } else {
      ElMessage.error(res.data.message || '修改失败')
    }
  } catch (err) {
    const msg = err?.response?.data?.message || '修改失败，请稍后重试'
    ElMessage.error(msg)
  } finally {
    changingPwd.value = false
  }
}
</script>

<style scoped>
.page-container { max-width: 640px; margin: 0 auto; padding: 32px 24px 60px; }

.page-header {
  background: #fff; padding: 20px 28px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03); margin-bottom: 24px;
}
.header-left { display: flex; align-items: center; gap: 8px; }
.header-left h1 { font-size: 22px; font-weight: 700; color: #1a1a2e; margin: 0; }

.section {
  background: #fff; padding: 28px 32px; border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.03); margin-bottom: 20px;
}
.section h3 { font-size: 18px; font-weight: 600; color: #1a1a2e; margin: 0 0 20px; }

.form-hint { font-size: 12px; color: #909399; margin-top: 4px; display: block; }

.code-row { display: flex; gap: 12px; }
.code-input { flex: 1; }
.code-btn { min-width: 140px; border-radius: 12px; font-weight: 500; }

.submit-btn { width: 100%; border-radius: 12px; height: 44px; font-size: 15px; font-weight: 600; margin-top: 8px; }

:deep(.el-input__wrapper) { border-radius: 12px; }
</style>
