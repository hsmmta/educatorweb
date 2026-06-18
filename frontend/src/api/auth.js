import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 认证相关
export const registerApi = (data) => request.post('/auth/register', data)
export const loginApi = (data) => request.post('/auth/login', data)

// 用户资料
export const updateProfileApi = (data) => request.put('/auth/profile', data)
export const changePasswordApi = (data) => request.put('/auth/password', data)