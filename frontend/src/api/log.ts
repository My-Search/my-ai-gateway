import http from './index'

export interface RequestLog {
  id: number
  traceId: string
  apiKeyName?: string
  channelName?: string
  channelModelName?: string
  modelName?: string
  phase: string
  status?: string
  message?: string
  retryIndex?: number
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

/** SSE 日志事件回调 */
export interface LogSseCallbacks {
  onLog: (log: RequestLog) => void
  onError?: (err: Event) => void
}

/** 订阅 SSE 日志流，返回 EventSource（外部负责 close） */
export function subscribeLogStream(callbacks: LogSseCallbacks): EventSource {
  // 使用与 axios 相同的 baseURL 路径
  const es = new EventSource('/admin/api/logs/stream')

  es.addEventListener('log', (event: MessageEvent) => {
    try {
      const log: RequestLog = JSON.parse(event.data)
      callbacks.onLog(log)
    } catch (e) {
      console.error('[LogSSE] 解析日志事件失败:', e)
    }
  })

  es.addEventListener('error', (err: Event) => {
    console.warn('[LogSSE] 连接异常，EventSource 将自动重连:', err)
    callbacks.onError?.(err)
  })

  return es
}
