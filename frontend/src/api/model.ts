import http from './index'

/** 关联模式：自添加（手动配置） / 继承（从源模型实时映射，只读） */
export type RelMode = 'self_add' | 'inherit'

export interface CustomModel {
  id?: number
  modelName: string
  description?: string
  strategy?: string
  enabled: number
  /** 是否隐藏（hidden=1 时不在模型列表中展示，但通过模型ID仍可直接调用） */
  hidden?: number
  createdAt?: string
  /** 关联模式：self_add | inherit，默认 self_add */
  relMode?: RelMode
  /** 继承源模型 ID（仅在 relMode='inherit' 时有值） */
  inheritFromModelId?: number | null
  /** 图片失效会话数：0=关闭；N>0 表示最近一个含图片的 user 消息后有 N 个 user 消息时，图片失效被移除 */
  imageInvalidateCount?: number
  /** 视频失效会话数：0=关闭 */
  videoInvalidateCount?: number
  /** 音频失效会话数：0=关闭 */
  audioInvalidateCount?: number
}

export interface ModelChannelRel {
  id: number
  modelId: number
  channelModelId: number
  sortOrder: number
  enabled?: number
  channelName?: string
  channelModelName?: string
  channelType?: string
  channelId?: number
  /** 渠道是否启用 */
  channelEnabled?: number
  /** 该渠道模型的平均响应时间 (ms) */
  ttftMs?: number | null
  /** 样本数 */
  sampleCount?: number | null
  /** 默认思考强度（reasoning_effort） */
  reasoningEffort?: string | null
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

export interface ModelStatsItem {
  modelName: string
  requests: number
  successRate: number
  avgResponseTime: number
}

export interface ModelStatsResponse {
  stats: ModelStatsItem[]
  trends: Record<string, number[]>
  buckets: string[]
}

export const modelApi = {
  list() {
    return http.get<CustomModel[]>('/models')
  },
  getStats(date?: string) {
    return http.get<ModelStatsResponse>('/models/stats', { params: date ? { date } : undefined })
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
    return http.get<{
      model: CustomModel
      rels: ModelChannelRel[]
      availableModels: any[]
      /** 继承模式下，源模型名（用于展示） */
      inheritFromModelName?: string | null
    }>(`/models/${id}/rels`)
  },
  /**
   * 获取可作为继承源的入口模型（不含自身）
   */
  getInheritableModels(id: number) {
    return http.get<CustomModel[]>(`/models/${id}/inheritable`)
  },
  /**
   * 切换关联模式。
   * @param id 目标模型 ID
   * @param mode self_add | inherit
   * @param sourceModelId 仅 mode='inherit' 时必填
   */
  setRelMode(id: number, mode: RelMode, sourceModelId?: number) {
    return http.put<{ success: boolean; model?: CustomModel; error?: string }>(
      `/models/${id}/rel-mode`,
      { mode, sourceModelId }
    )
  },
  batchAddRels(modelId: number, channelModelIds: number[], sortedRelIds?: string) {
    return http.post<{ success: boolean; count: number; error?: string }>(`/models/${modelId}/rels`, { channelModelIds, sortedRelIds })
  },
  removeRel(relId: number) {
    return http.delete<{ success: boolean; error?: string }>(`/models/rels/${relId}`)
  },
  updateRelSort(relId: number, sortOrder: number) {
    return http.put<{ success: boolean; error?: string }>(`/models/rels/${relId}/sort`, { sortOrder })
  },
  batchUpdateSortOrders(sortedRelIds: number[]) {
    return http.put<{ success: boolean; error?: string }>('/models/rels/sort', { sortedRelIds })
  },
  updateRelReasoningEffort(relId: number, reasoningEffort: string | null) {
    return http.put<{ success: boolean; error?: string }>(`/models/rels/${relId}/reasoning-effort`, { reasoningEffort })
  },
  getCircuitBreaker(id: number) {
    return http.get<{ model: CustomModel; config: CircuitBreakerConfig }>(`/models/${id}/circuit-breaker`)
  },
  saveCircuitBreaker(id: number, config: Partial<CircuitBreakerConfig>) {
    return http.put<{ success: boolean }>(`/models/${id}/circuit-breaker`, config)
  }
}
