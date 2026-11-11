import axios from 'axios'
import { ElMessage } from 'element-plus'

export const http = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || '', timeout: 60000 })
http.interceptors.request.use(config => {
  const token = localStorage.getItem('storybook_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
http.interceptors.response.use(r => r.data, error => {
  // Leaving a page or pausing a pending narration intentionally cancels its request.
  if (axios.isCancel(error)) return Promise.reject(error)
  const status = error.response?.status
  if (status === 401 || status === 403) {
    localStorage.removeItem('storybook_token')
    localStorage.removeItem('storybook_role')
    localStorage.removeItem('storybook_nickname')
    localStorage.removeItem('storybook_avatar')
    if (!location.pathname.includes('/login')) location.href = '/login'
    return Promise.reject(error)
  }
  ElMessage.error(error.response?.data?.message || '连接服务失败，请稍后再试')
  return Promise.reject(error)
})
