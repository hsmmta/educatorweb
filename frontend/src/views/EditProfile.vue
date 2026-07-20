<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <el-button text :icon="ArrowLeft" @click="$router.push('/profile')">返回</el-button>
        <h1>编辑资料</h1>
      </div>
    </div>

    <div class="content-area">
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
          <el-button type="primary" :loading="savingProfile" @click="saveProfile" size="large" class="submit-btn">保存修改</el-button>
        </el-form>
      </section>

      <section class="section">
        <h3>修改密码</h3>
        <el-alert title="修改密码需要手机验证码，验证码将输出到浏览器控制台" type="info" :closable="false" show-icon style="margin-bottom: 20px"/>
        <el-form :model="pwdForm" :rules="pwdRules" ref="pwdFormRef" label-position="top">
          <el-form-item label="手机验证码" prop="code">
            <div class="code-row">
              <el-input v-model="pwdForm.code" placeholder="输入验证码" size="large" class="code-input">
                <template #prefix><el-icon><Key /></el-icon></template>
              </el-input>
              <el-button :type="pwdCountdown > 0 ? 'info' : 'primary'" :disabled="pwdCountdown > 0" @click="sendPwdCode" size="large" class="code-btn">{{ pwdCountdown > 0 ? `${pwdCountdown}s 后重发` : '获取验证码' }}</el-button>
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
          <el-button type="warning" :loading="changingPwd" @click="changePwd" size="large" class="submit-btn">修改密码</el-button>
        </el-form>
      </section>

      <section class="section">
        <h3>AI 模型配置</h3>
        <el-form label-position="top">
          <el-form-item label="当前模型">
            <el-tag :type="aiCurrent === 'deepseek' ? '' : 'warning'" size="large">{{ aiCurrent === 'deepseek' ? 'DeepSeek' : '讯飞星火' }}</el-tag>
            <el-button size="small" type="primary" :loading="aiLoading" @click="switchAI" style="margin-left:12px">切换至{{ aiCurrent === 'deepseek' ? '讯飞星火' : 'DeepSeek' }}</el-button>
          </el-form-item>
        </el-form>
        <el-divider />
        <el-form label-position="top">
          <el-form-item label="DeepSeek API Key">
            <el-input v-model="deepseekKeyInput" :placeholder="deepseekMasked ? '配置: ' + deepseekMasked : '输入 DeepSeek API Key'" size="large" show-password>
              <template #prefix><el-icon><Key /></el-icon></template>
            </el-input>
            <span class="form-hint">
              <span v-if="deepseekMasked" style="color:#67c23a">配置: {{ deepseekMasked }}</span>
              <span v-else>获取: <a href="https://platform.deepseek.com/api_keys" target="_blank">platform.deepseek.com</a></span>
            </span>
          </el-form-item>
          <el-button size="small" type="primary" :loading="savingDeepseek" @click="saveKey('deepseek')">{{ deepseekMasked ? '更新' : '保存' }}</el-button>

          <el-divider />

          <el-form-item label="讯飞星火 API Key">
            <el-input v-model="xunfeiKeyInput" :placeholder="xunfeiMasked ? '配置: ' + xunfeiMasked : '输入讯飞 API Key'" size="large" show-password>
              <template #prefix><el-icon><Key /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="讯飞 API Secret（选填）">
            <el-input v-model="xunfeiSecretInput" placeholder="输入讯飞 API Secret" size="large" show-password>
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <span class="form-hint">
            <span v-if="xunfeiMasked" style="color:#67c23a">配置: {{ xunfeiMasked }}</span>
            <span v-else>获取: <a href="https://console.xfyun.cn/" target="_blank">console.xfyun.cn</a></span>
          </span>
          <el-button size="small" type="primary" :loading="savingXunfei" @click="saveKey('xunfei')" style="margin-top:8px">{{ xunfeiMasked ? '更新' : '保存' }}</el-button>
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
import request from '@/api/request'

const router = useRouter()
const userInfo = ref({})
const savingProfile = ref(false)
const changingPwd = ref(false)
const pwdCountdown = ref(0)
const sentPwdCode = ref('')
const profileFormRef = ref()
const pwdFormRef = ref()

const profileForm = reactive({ nickname: '', email: '' })
const pwdForm = reactive({ code: '', newPassword: '', confirmPassword: '' })

const profileRules = {
  nickname: [{ required: true, message: '昵称不能为空', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

const validateConfirmPwd = (_rule, value, callback) => {
  callback(value !== pwdForm.newPassword ? new Error('两次输入密码不一致') : undefined)
}

const pwdRules = {
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { min: 6, message: '密码至少6位', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }, { validator: validateConfirmPwd, trigger: 'blur' }]
}

const sendPwdCode = () => {
  sentPwdCode.value = String(Math.floor(1000 + Math.random() * 9000))
  console.log('验证码: ' + sentPwdCode.value)
  ElMessage.success('验证码已发送（查看控制台）')
  pwdCountdown.value = 60
  const t = setInterval(() => { if (--pwdCountdown.value <= 0) clearInterval(t) }, 1000)
}

const saveProfile = async () => {
  if (!profileFormRef.value) return
  try { await profileFormRef.value.validate() } catch { return }
  savingProfile.value = true
  try {
    const res = await updateProfileApi({ phone: userInfo.value.phone, nickname: profileForm.nickname, email: profileForm.email || undefined })
    if (res.data?.code === 200) {
      ElMessage.success('修改成功')
      const s = JSON.parse(localStorage.getItem('userInfo') || '{}'); s.nickname = profileForm.nickname; s.email = profileForm.email
      localStorage.setItem('userInfo', JSON.stringify(s))
    } else ElMessage.error(res.data?.message || '修改失败')
  } catch (e) { ElMessage.error(e.response?.data?.message || '修改失败') }
  finally { savingProfile.value = false }
}

const changePwd = async () => {
  if (!pwdFormRef.value) return
  try { await pwdFormRef.value.validate() } catch { return }
  if (pwdForm.code !== sentPwdCode.value) { ElMessage.error('验证码错误'); return }
  changingPwd.value = true
  try {
    const res = await changePasswordApi({ phone: userInfo.value.phone, code: pwdForm.code, newPassword: pwdForm.newPassword })
    if (res.data?.code === 200) { ElMessage.success('密码修改成功，请重新登录'); localStorage.clear(); setTimeout(() => router.push('/login'), 1000) }
    else ElMessage.error(res.data?.message || '修改失败')
  } catch (e) { ElMessage.error(e.response?.data?.message || '修改失败') }
  finally { changingPwd.value = false }
}

// ======== AI Provider ========
const aiCurrent = ref('deepseek')
const aiLoading = ref(false)
const deepseekKeyInput = ref('')
const xunfeiKeyInput = ref('')
const xunfeiSecretInput = ref('')
const deepseekMasked = ref('')
const xunfeiMasked = ref('')
const savingDeepseek = ref(false)
const savingXunfei = ref(false)

const loadAIStatus = async () => {
  try {
    const [sr, kr] = await Promise.all([
      request.get('/provider/status'),
      request.get('/provider/keys')
    ])
    aiCurrent.value = sr.data?.current || 'deepseek'
    const keys = kr.data || {}
    deepseekMasked.value = keys.deepseek?.masked || ''
    xunfeiMasked.value = keys.xunfei?.masked || ''
  } catch {}
}

const switchAI = async () => {
  const target = aiCurrent.value === 'deepseek' ? 'xunfei' : 'deepseek'
  aiLoading.value = true
  try {
    const res = await request.post('/provider/switch', { provider: target })
    if (res.data?.success) {
      aiCurrent.value = target
      ElMessage.success('当前模型: ' + (target === 'deepseek' ? 'DeepSeek' : '讯飞星火'))
    } else ElMessage.error('切换失败: ' + (res.data?.error || '未知错误'))
  } catch (e) { ElMessage.error('切换失败') }
  finally { aiLoading.value = false }
}

const saveKey = async (provider) => {
  const key = provider === 'deepseek' ? deepseekKeyInput.value : (xunfeiKeyInput.value + (xunfeiSecretInput.value ? ':' + xunfeiSecretInput.value : ''))
  if (!key.trim()) { ElMessage.warning('请先输入 API Key'); return }
  const saving = provider === 'deepseek' ? savingDeepseek : savingXunfei
  saving.value = true
  try {
    await request.post('/provider/keys', { provider, key })
    ElMessage.success((provider === 'deepseek' ? 'DeepSeek' : '讯飞') + ' Key 已保存，重启后端后生效')

    // Update masked display
    const kr = await request.get('/provider/keys')
    const keys = kr.data || {}
    if (provider === 'deepseek') deepseekMasked.value = keys.deepseek?.masked || ''
    else xunfeiMasked.value = keys.xunfei?.masked || ''

    // Clear inputs
    if (provider === 'deepseek') deepseekKeyInput.value = ''
    else { xunfeiKeyInput.value = ''; xunfeiSecretInput.value = '' }
  } catch { ElMessage.error('保存失败') }
  finally { saving.value = false }
}

onMounted(() => {
  const info = localStorage.getItem('userInfo')
  if (info) {
    try {
      userInfo.value = JSON.parse(info)
      profileForm.nickname = userInfo.value.nickname || ''
      profileForm.email = userInfo.value.email || ''
    } catch { userInfo.value = {} }
  }
  loadAIStatus()
})
</script>

<style scoped>
.page-container { max-width: 700px; margin: 0 auto; padding: 32px 24px 60px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
.header-left { display: flex; align-items: center; gap: 16px; }
.header-left h1 { font-size: 20px; font-weight: 700; }
.content-area { display: flex; flex-direction: column; gap: 24px; }
.section { background: #fff; border-radius: 14px; padding: 24px; box-shadow: 0 1px 4px rgba(0,0,0,0.04); }
.section h3 { font-size: 16px; margin: 0 0 16px; color: #1a1a2e; }
.code-row { display: flex; gap: 12px; align-items: flex-start; }
.code-input { flex: 1; }
.code-btn { flex-shrink: 0; min-width: 130px; }
.form-hint { font-size: 12px; color: #909399; margin-top: 4px; display: block; }
.form-hint a { color: #667eea; }
.submit-btn { width: 100%; margin-top: 8px; }
</style>
