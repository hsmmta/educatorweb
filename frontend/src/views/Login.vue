<template>
  <div class="login-container">
    <el-card class="login-card">
      <template #header>
        <h2>个性化智能学习系统</h2>
        <p>登录你的账号</p>
      </template>
      <el-form :model="form" :rules="rules" ref="formRef" label-width="80px">
        <el-form-item label="手机" prop="username">
          <el-input v-model="form.username" placeholder="请输入注册手机号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input type="password" v-model="form.password" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleLogin" :loading="loading">登录</el-button>
          <el-button @click="$router.push('/register')">去注册</el-button>
        </el-form-item>
      </el-form>
      <div class="demo-tip">演示账号：demo@example.com / 123456</div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

const router = useRouter()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// 临时模拟登录（不依赖后端）
const handleLogin = async () => {
  if (!formRef.value) return
  await formRef.value.validate()
  loading.value = true
  // 模拟后端验证
  setTimeout(() => {
    if (form.username === 'demo@example.com' && form.password === '123456') {
      localStorage.setItem('token', 'fake-token')
      localStorage.setItem('userInfo', JSON.stringify({ nickname: '测试用户', username: form.username }))
      ElMessage.success('登录成功（模拟）')
      router.push('/home')
    } else {
      ElMessage.error('账号或密码错误，请使用 demo@example.com / 123456')
    }
    loading.value = false
  }, 500)
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 420px;
  border-radius: 12px;
  box-shadow: 0 10px 25px rgba(0,0,0,0.1);
}
h2 {
  text-align: center;
  margin-bottom: 8px;
}
.demo-tip {
  text-align: center;
  font-size: 12px;
  color: #909399;
  margin-top: 16px;
}
</style>