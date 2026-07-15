import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 120000   // 2 min: LLM chat calls can take 15-45s (RAG + KG + embeddings + DeepSeek)
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export default request
