import http from './index'

export type InjectRole = 'system' | 'user' | 'assistant'
export type InjectPosition = 'prepend' | 'append' | 'replace_system'

export interface PromptInjection {
  id?: number
  modelId: number
  name: string
  injectRole: InjectRole
  injectPosition: InjectPosition
  content: string
  enabled: number
  priority: number
  createdAt?: string
  updatedAt?: string
}

export const promptInjectionApi = {
  listByModelId(modelId: number) {
    return http.get<PromptInjection[]>(`/models/${modelId}/prompt-injections`)
  },
  get(id: number) {
    return http.get<PromptInjection>(`/prompt-injections/${id}`)
  },
  create(modelId: number, data: Partial<PromptInjection>) {
    return http.post<{ success: boolean; id?: number }>(`/models/${modelId}/prompt-injections`, data)
  },
  update(id: number, data: Partial<PromptInjection>) {
    return http.put<{ success: boolean }>(`/prompt-injections/${id}`, data)
  },
  delete(id: number) {
    return http.delete<{ success: boolean }>(`/prompt-injections/${id}`)
  }
}
