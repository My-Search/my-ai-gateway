import { authApi } from '@/api/auth'

/**
 * 导航到登录页，使用 window.location.href 以触发全页刷新（清除可能的路由状态）
 */
export function navigateToLogin() {
  window.location.href = '/login'
}

/**
 * 检查后端 session 是否有效
 */
export async function checkAuth(): Promise<{ authenticated: boolean; hasAdminAccount: boolean }> {
  try {
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
    return res.data as any
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
    return res.data as any
  } catch (e: any) {
    return { success: false, error: e.message || '网络错误' }
  }
}
