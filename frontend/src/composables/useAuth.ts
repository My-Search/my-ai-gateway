import { authApi } from '@/api/auth'
import type { AuthCheckResponse, AuthLoginResponse } from '@/types'

/**
 * 检查后端 session 是否有效
 */
export async function checkAuth(): Promise<AuthCheckResponse> {
  try {
    // Token 会由 http 拦截器自动从 localStorage 携带
    const res = await authApi.check()
    return res.data
  } catch {
    return { authenticated: false, hasAdminAccount: true }
  }
}

/**
 * 尝试登录
 */
export async function loginUser(username: string, password: string): Promise<AuthLoginResponse> {
  try {
    const res = await authApi.login(username, password)
    const data = res.data
    if (data.success && data.token) {
      // 保存 JWT Token 到 localStorage
      localStorage.setItem('admin_token', data.token)
    }
    return data
  } catch (e) {
    return { success: false, error: (e instanceof Error ? e.message : '网络错误') }
  }
}

/**
 * 尝试设置管理员账号
 */
export async function setupAdmin(username: string, password: string, confirmPassword: string): Promise<AuthLoginResponse> {
  try {
    const res = await authApi.setup(username, password, confirmPassword)
    const data = res.data
    if (data.success && data.token) {
      // 保存 JWT Token 到 localStorage
      localStorage.setItem('admin_token', data.token)
    }
    return data
  } catch (e) {
    return { success: false, error: (e instanceof Error ? e.message : '网络错误') }
  }
}
