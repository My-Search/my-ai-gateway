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
  promptTokens?: number
  completionTokens?: number
  totalTokens: number
}

/** API Key 详情页：单模型用量统计 */
export interface ApiKeyModelUsageStat extends ApiKeyPeriodStats {
  modelName: string
  avgResponseTimeRecent30?: number
  avgOutputSpeedRecent30?: number
  today?: ApiKeyPeriodStats
  week?: ApiKeyPeriodStats
  month?: ApiKeyPeriodStats
}

/** API Key 详情页返回结构 */
export interface ApiKeyUsageStatsDetail {
  key: { id: number; keyName: string }
  modelStats: ApiKeyModelUsageStat[]
  keyAvgResponseTimeRecent30?: number
  keyAvgOutputSpeedRecent30?: number
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
  /** 获取单个 API Key 的详细用量统计（按模型细分） */
  usageStatsDetail(id: number) {
    return http.get<ApiKeyUsageStatsDetail>(`/api-keys/${id}/usage-stats`)
  }
}
