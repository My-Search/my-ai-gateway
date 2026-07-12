import http from './index'
import type { AxiosResponse } from 'axios'
import type { AuthCheckResponse, AuthLoginResponse } from '@/types'

export const authApi = {
  check(): Promise<AxiosResponse<AuthCheckResponse>> {
    return http.get('/auth/check')
  },
  login(username: string, password: string): Promise<AxiosResponse<AuthLoginResponse>> {
    return http.post('/auth/login', { username, password })
  },
  setup(username: string, password: string, confirmPassword: string): Promise<AxiosResponse<AuthLoginResponse>> {
    return http.post('/auth/setup', { username, password, confirmPassword })
  },
  logout() {
    return http.post('/auth/logout')
  }
}
