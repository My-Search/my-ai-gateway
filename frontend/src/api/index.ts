import axios from 'axios'
import type { AxiosInstance } from 'axios'

const http: AxiosInstance = axios.create({
  baseURL: '/admin/api',
  timeout: 30000,
  withCredentials: true, // 确保 session cookie 总是被携带（向后兼容）
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器：自动携带 JWT Token
http.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('admin_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 标记是否正在处理 401 重定向，防止循环
let isRedirectingToLogin = false

// 响应拦截器：统一处理错误格式
http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      // 401 未登录 → 客户端导航到登录页，避免 full page reload 导致死循环
      if (error.response.status === 401 && !isRedirectingToLogin) {
        isRedirectingToLogin = true
        // 清除无效 token
        localStorage.removeItem('admin_token')
        // 动态 import router 避免循环依赖
        import('@/router').then(({ navigateToLogin }) => {
          navigateToLogin()
        })
        // 10秒后重置标志
        setTimeout(() => { isRedirectingToLogin = false }, 10000)
        return Promise.reject(new Error('未登录，请先登录'))
      }
      const msg = error.response.data?.error || error.response.data?.message || `请求失败 (${error.response.status})`
      return Promise.reject(new Error(msg))
    }
    if (error.request) {
      return Promise.reject(new Error('网络错误，请检查服务器是否正常运行'))
    }
    return Promise.reject(error)
  }
)

export default http
