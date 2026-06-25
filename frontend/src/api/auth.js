import request from './request'

// 认证相关
export const registerApi = (data) => request.post('/auth/register', data)
export const loginApi = (data) => request.post('/auth/login', data)

// 用户资料
export const updateProfileApi = (data) => request.put('/auth/profile', data)
export const changePasswordApi = (data) => request.put('/auth/password', data)
