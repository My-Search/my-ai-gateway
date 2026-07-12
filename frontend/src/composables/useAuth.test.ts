import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { AxiosResponse } from 'axios'
import type { AuthCheckResponse, AuthLoginResponse } from '@/types'

// mock authApi 模块，避免真实网络请求
vi.mock('@/api/auth', () => ({
  authApi: {
    check: vi.fn(),
    login: vi.fn(),
    setup: vi.fn(),
    logout: vi.fn(),
  }
}))

import { authApi } from '@/api/auth'
import { checkAuth, loginUser, setupAdmin } from './useAuth'

/** 构造仅含 data 的最小 AxiosResponse mock（测试专用） */
function mockAxiosResponse<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>
}

describe('useAuth', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  // ---------- checkAuth ----------

  describe('checkAuth', () => {
    it('认证成功时返回后端响应', async () => {
      vi.mocked(authApi.check).mockResolvedValue(
        mockAxiosResponse<AuthCheckResponse>({ authenticated: true, hasAdminAccount: true })
      )

      const result = await checkAuth()

      expect(result).toEqual({ authenticated: true, hasAdminAccount: true })
      expect(authApi.check).toHaveBeenCalledOnce()
    })

    it('请求失败时返回默认值（authenticated=false）', async () => {
      vi.mocked(authApi.check).mockRejectedValue(new Error('网络错误'))

      const result = await checkAuth()

      expect(result).toEqual({ authenticated: false, hasAdminAccount: true })
    })
  })

  // ---------- loginUser ----------

  describe('loginUser', () => {
    it('登录成功时保存 token 到 localStorage', async () => {
      vi.mocked(authApi.login).mockResolvedValue(
        mockAxiosResponse<AuthLoginResponse>({ success: true, token: 'test-jwt-token' })
      )

      const result = await loginUser('admin', 'password')

      expect(result).toEqual({ success: true, token: 'test-jwt-token' })
      expect(localStorage.getItem('admin_token')).toBe('test-jwt-token')
    })

    it('登录失败（success=false）时不保存 token', async () => {
      vi.mocked(authApi.login).mockResolvedValue(
        mockAxiosResponse<AuthLoginResponse>({ success: false, error: '用户名或密码错误' })
      )

      const result = await loginUser('admin', 'wrong')

      expect(result).toEqual({ success: false, error: '用户名或密码错误' })
      expect(localStorage.getItem('admin_token')).toBeNull()
    })

    it('网络异常时返回错误信息', async () => {
      vi.mocked(authApi.login).mockRejectedValue(new Error('网络错误'))

      const result = await loginUser('admin', 'password')

      expect(result.success).toBe(false)
      expect(result.error).toBe('网络错误')
      expect(localStorage.getItem('admin_token')).toBeNull()
    })
  })

  // ---------- setupAdmin ----------

  describe('setupAdmin', () => {
    it('设置成功时保存 token 到 localStorage', async () => {
      vi.mocked(authApi.setup).mockResolvedValue(
        mockAxiosResponse<AuthLoginResponse>({ success: true, token: 'setup-jwt-token' })
      )

      const result = await setupAdmin('admin', 'password', 'password')

      expect(result).toEqual({ success: true, token: 'setup-jwt-token' })
      expect(localStorage.getItem('admin_token')).toBe('setup-jwt-token')
    })

    it('设置失败（success=false）时不保存 token', async () => {
      vi.mocked(authApi.setup).mockResolvedValue(
        mockAxiosResponse<AuthLoginResponse>({ success: false, error: '管理员账号已存在' })
      )

      const result = await setupAdmin('admin', 'password', 'password')

      expect(result).toEqual({ success: false, error: '管理员账号已存在' })
      expect(localStorage.getItem('admin_token')).toBeNull()
    })

    it('网络异常时返回错误信息', async () => {
      vi.mocked(authApi.setup).mockRejectedValue(new Error('网络错误'))

      const result = await setupAdmin('admin', 'password', 'password')

      expect(result.success).toBe(false)
      expect(result.error).toBe('网络错误')
    })
  })
})
