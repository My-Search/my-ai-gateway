import { authApi } from '@/api/auth'

/**
 * 检查后端 session 是否有效
 */
export async function checkAuth(): Promise<{ authenticated: boolean; hasAdminAccount: boolean }> {
  try {
    // Token 会由 http 拦截器自动从 localStorage 携带
    const res = await authApi.check()
    return res.data as any
  } catch {
    return { authenticated: false, hasAdminAccount: true }
  }
}

/**
 * 尝试登录
 */
export async function loginUser(username: string, password: string): Promise<{ success: boolean; error?: string }> {
  try {
    const res = await authApi.login(username, password)
    const data = res.data as any
    if (data.success && data.token) {
      // 保存 JWT Token 到 localStorage
      localStorage.setItem('admin_token', data.token)
    }
    return data
  } catch (e: any) {
    return { success: false, error: e.message || '网络错误' }
  }
}

/**
 * 尝试设置管理员账号
 */
export async function setupAdmin(username: string, password: string, confirmPassword: string): Promise<{ success: boolean; error?: string }> {
  try {
    const res = await authApi.setup(username, password, confirmPassword)
    const data = res.data as any
    if (data.success && data.token) {
      // 保存 JWT Token 到 localStorage
      localStorage.setItem('admin_token', data.token)
    }
    return data
  } catch (e: any) {
    return { success: false, error: e.message || '网络错误' }
  }
}
