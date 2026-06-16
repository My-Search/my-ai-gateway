import http from './index'

export const authApi = {
  check() {
    return http.get('/auth/check')
  },
  login(username: string, password: string) {
    return http.post('/auth/login', { username, password })
  },
  setup(username: string, password: string, confirmPassword: string) {
    return http.post('/auth/setup', { username, password, confirmPassword })
  },
  logout() {
    return http.post('/auth/logout')
  }
}
