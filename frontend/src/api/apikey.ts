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

/** API Key 单周期用量统计 */
export interface ApiKeyPeriodStats {
  requestCount: number
  totalTokens: number
}

/** API Key 多周期用量统计 */
export interface ApiKeyUsageStats {
  day?: ApiKeyPeriodStats
  week?: ApiKeyPeriodStats
  month?: ApiKeyPeriodStats
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
  },
  /** 获取所有 API Key 的日/周/月用量统计 */
  usageStats() {
    return http.get<Record<number, ApiKeyUsageStats>>('/api-keys/usage-stats')
  }
}
