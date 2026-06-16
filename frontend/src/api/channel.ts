import http from './index'

export interface Channel {
  id?: number
  name: string
  channelType: string
  baseUrl: string
  apiKey?: string
  enabled: number
  createdAt?: string
  updatedAt?: string
  // 用量统计字段（列表接口返回）
  requestCount?: number
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
}

export interface ChannelModel {
  id: number
  channelId: number
  modelName: string
  displayName: string
}

export interface ChannelApiKey {
  id?: number
  channelId?: number
  keyName: string
  apiKey: string
  enabled: number
  sortOrder: number
}

export interface ModelUsageStat {
  modelName: string
  requestCount: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  /** 最近30次请求的平均响应时间（毫秒） */
  avgResponseTimeRecent30: number
}

export const channelApi = {
  list() {
    return http.get<Channel[]>('/channels')
  },
  get(id: number) {
    return http.get<{ channel: Channel; channelModels: ChannelModel[]; apiKeys: ChannelApiKey[] }>(`/channels/${id}`)
  },
  create(data: Partial<Channel> & { manualModels?: string; apiKeysJson?: string }) {
    return http.post<{ success: boolean; id?: number; error?: string }>('/channels', data)
  },
  update(id: number, data: Partial<Channel> & { manualModels?: string; apiKeysJson?: string }) {
    return http.put<{ success: boolean; error?: string }>(`/channels/${id}`, data)
  },
  delete(id: number) {
    return http.delete<{ success: boolean }>(`/channels/${id}`)
  },
  getModels(id: number) {
    return http.get<{ channel: Channel; models: ChannelModel[] }>(`/channels/${id}/models`)
  },
  reloadModels(id: number) {
    return http.post<{ success: boolean; data: ChannelModel[]; count: number }>(`/channels/${id}/reload-models`)
  },
  fetchModels(baseUrl: string, apiKey: string, channelType: string) {
    return http.get<{ success: boolean; data: ChannelModel[]; count: number }>('/channels/fetch-models', {
      params: { baseUrl, apiKey, channelType }
    })
  },
  addManualModel(channelId: number, modelName: string, displayName?: string) {
    return http.post<{ success: boolean; data: ChannelModel }>(`/channels/${channelId}/models`, { modelName, displayName })
  },
  deleteModel(channelId: number, modelId: number) {
    return http.delete<{ success: boolean }>(`/channels/${channelId}/models/${modelId}`)
  },
  deleteAllModels(channelId: number) {
    return http.delete<{ success: boolean; count: number }>(`/channels/${channelId}/models`)
  },
  quickTest(id: number, message: string) {
    return http.post<{ success: boolean; response?: string; responseTime?: number; model?: string; error?: string }>(
      `/channels/${id}/quick-test`, { message }
    )
  },
  getUsageStats(id: number) {
    return http.get<{ channel: Channel; modelStats: ModelUsageStat[]; channelAvgResponseTimeRecent30: number }>(`/channels/${id}/usage-stats`)
  }
}
