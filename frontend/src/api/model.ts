import http from './index'

export interface CustomModel {
  id?: number
  modelName: string
  description?: string
  strategy?: string
  enabled: number
  createdAt?: string
}

export interface ModelChannelRel {
  id: number
  modelId: number
  channelModelId: number
  sortOrder: number
  channelName?: string
  channelModelName?: string
  channelType?: string
  channelId?: number
}

export interface CircuitBreakerConfig {
  id?: number
  modelId: number
  enabled: number
  /** 重试次数 */
  retryCount?: number
  /** 熔断持续时间（秒） */
  circuitBreakDuration?: number
  /** 熔断范围：channel（渠道级，按 API Key 熔断）/ model（模型级） */
  circuitBreakScope?: 'channel' | 'model'
}

export const modelApi = {
  list() {
    return http.get<CustomModel[]>('/models')
  },
  get(id: number) {
    return http.get<CustomModel>(`/models/${id}`)
  },
  create(data: Partial<CustomModel>) {
    return http.post<{ success: boolean; id?: number }>('/models', data)
  },
  update(id: number, data: Partial<CustomModel>) {
    return http.put<{ success: boolean }>(`/models/${id}`, data)
  },
  delete(id: number) {
    return http.delete<{ success: boolean }>(`/models/${id}`)
  },
  getRels(id: number) {
    return http.get<{ model: CustomModel; rels: ModelChannelRel[]; availableModels: any[] }>(`/models/${id}/rels`)
  },
  batchAddRels(modelId: number, channelModelIds: number[], sortedRelIds?: string) {
    return http.post<{ success: boolean; count: number }>(`/models/${modelId}/rels`, { channelModelIds, sortedRelIds })
  },
  removeRel(relId: number) {
    return http.delete<{ success: boolean }>(`/models/rels/${relId}`)
  },
  updateRelSort(relId: number, sortOrder: number) {
    return http.put<{ success: boolean }>(`/models/rels/${relId}/sort`, { sortOrder })
  },
  getCircuitBreaker(id: number) {
    return http.get<{ model: CustomModel; config: CircuitBreakerConfig }>(`/models/${id}/circuit-breaker`)
  },
  saveCircuitBreaker(id: number, config: Partial<CircuitBreakerConfig>) {
    return http.put<{ success: boolean }>(`/models/${id}/circuit-breaker`, config)
  }
}
