import http from './index'

export interface MultiModalRule {
  id?: number
  pattern: string
  appendType: string
  createdAt?: string
  updatedAt?: string
}

export interface RuleTestResult {
  data: string
  matched: boolean
}

export interface RuleTestResponse {
  success: boolean
  data?: RuleTestResult[]
  /** 实际匹配到的真实渠道模型（channel_models.model_name） */
  matchedModels?: string[]
  /** 参与测试的真实渠道模型总数 */
  totalModels?: number
  error?: string
}

export const multimodalApi = {
  list() {
    return http.get<MultiModalRule[]>('/multimodal-rules')
  },
  create(rule: Partial<MultiModalRule>) {
    return http.post<{ success: boolean; data?: MultiModalRule; error?: string }>('/multimodal-rules', rule)
  },
  update(id: number, rule: Partial<MultiModalRule>) {
    return http.put<{ success: boolean; data?: MultiModalRule; error?: string }>(`/multimodal-rules/${id}`, rule)
  },
  delete(id: number) {
    return http.delete<{ success: boolean; data?: MultiModalRule; error?: string }>(`/multimodal-rules/${id}`)
  },
  test(pattern: string, testData: string[]) {
    return http.post<RuleTestResponse>('/multimodal-rules/test', { pattern, testData })
  }
}
