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

export interface LogsResponse {
  data: LogTrace[]
  total: number
  offset: number
  limit: number
  hasMore: boolean
}

export const logApi = {
  list(offset = 0, limit = 50) {
    return http.get<LogsResponse>('/logs', { params: { offset, limit } })
  },
  clean() {
    return http.post<{ success: boolean; message: string }>('/logs/clean')
  }
}

/** SSE 日志事件回调 */
export interface LogSseCallbacks {
  onLog: (log: RequestLog) => void
  onError?: (err: Event) => void
  /** 正在尝试重连 */
  onReconnecting?: () => void
  /** 连接已（重）建立 */
  onReconnected?: () => void
}

export interface LogSseSubscription {
  close: () => void
}

/** 订阅 SSE 日志流，支持自动重连（指数退避） */
export function subscribeLogStream(callbacks: LogSseCallbacks): LogSseSubscription {
  // EventSource 不支持自定义请求头，通过 query param 传递 JWT Token 用于认证
  const MAX_RETRY_DELAY = 30000 // 最大重试间隔 30s
  let retryCount = 0
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let es: EventSource | null = null
  let stopped = false

  function connect() {
    if (stopped) return

    const token = localStorage.getItem('admin_token') || ''
    es = new EventSource(`/admin/api/logs/stream?token=${encodeURIComponent(token)}`)

    es.addEventListener('log', (event: MessageEvent) => {
      try {
        const log: RequestLog = JSON.parse(event.data)
        callbacks.onLog(log)
      } catch (e) {
        console.error('[LogSSE] 解析日志事件失败:', e)
      }
    })

    es.addEventListener('open', () => {
      retryCount = 0
      callbacks.onReconnected?.()
    })

    es.addEventListener('error', (err: Event) => {
      callbacks.onError?.(err)
      if (stopped) return
      // 关闭当前连接（防止 EventSource 内置重连干扰），由我们自己统一管理重连
      es?.close()
      scheduleReconnect()
    })
  }

  function scheduleReconnect() {
    if (stopped) return
    callbacks.onReconnecting?.()
    const delay = Math.min(2000 * (2 ** retryCount), MAX_RETRY_DELAY)
    retryCount++
    retryTimer = setTimeout(() => {
      if (!stopped) connect()
    }, delay)
  }

  function close() {
    stopped = true
    if (retryTimer) clearTimeout(retryTimer)
    es?.close()
    es = null
  }

  connect()

  return { close }
}
