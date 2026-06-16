import http from './index'

export interface ApiKey {
  id?: number
  keyName: string
  keyValue: string
  enabled: number
  shareCode?: string
  shared?: number
  lastUsedAt?: string
  createdAt?: string
}

export const apikeyApi = {
  list() {
    return http.get<ApiKey[]>('/api-keys')
  },
  get(id: number) {
    return http.get<ApiKey>(`/api-keys/${id}`)
  },
  create(data: Partial<ApiKey>) {
    return http.post<{ success: boolean; id?: number }>('/api-keys', data)
  },
  update(id: number, data: Partial<ApiKey>) {
    return http.put<{ success: boolean }>(`/api-keys/${id}`, data)
  },
  delete(id: number) {
    return http.delete<{ success: boolean }>(`/api-keys/${id}`)
  }
}
