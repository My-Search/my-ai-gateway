import http from './index'

export interface RequestLog {
  id: number
  traceId: string
  apiKeyName?: string
  channelName?: string
  channelModelName?: string
  modelName?: string
  phase: string
  errorMessage?: string
  duration?: number
  tokens?: number
  responseTimeMs?: number
  createdAt: string
}

export interface LogTrace {
  traceId: string
  logs: RequestLog[]
  retryCount: number
  successCount: number
  failCount: number
  modelName: string
  totalTimeMs: number
  startTime?: string
  endTime?: string
}

export const logApi = {
  list(limit = 200) {
    return http.get<LogTrace[]>('/logs', { params: { limit } })
  },
  clean() {
    return http.post<{ success: boolean; message: string }>('/logs/clean')
  }
}
