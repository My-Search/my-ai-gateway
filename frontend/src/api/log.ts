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
  requestHeaders?: string
  requestBody?: string
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

export interface LogFilters {
  modelName?: string
  /** 网关 API Key 主键（按 id 精确匹配，区别于渠道 API Key） */
  gatewayApiKeyId?: number
  /** 兼容旧版：API Key 名（精确匹配，可选）。新代码请优先使用 gatewayApiKeyId */
  apiKeyName?: string
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
  list(offset = 0, limit = 50, filters?: LogFilters) {
    const params: Record<string, string | number> = { offset, limit }
    if (filters?.modelName) params.modelName = filters.modelName
    if (filters?.gatewayApiKeyId != null) params.gatewayApiKeyId = filters.gatewayApiKeyId
    if (filters?.apiKeyName) params.apiKeyName = filters.apiKeyName
    if (filters?.startTime) params.startTime = filters.startTime
    if (filters?.endTime) params.endTime = filters.endTime
    return http.get<LogsResponse>('/logs', { params })
  },
  clean() {
    return http.post<{ success: boolean; message: string }>('/logs/clean')
  },
  /**
   * 获取"使用历史"堆叠柱状图数据。
   * @param params.year 目标年份（不传则后端取当前年）
   * @param params.month 目标月份 1-12（不传则后端取当前月）
   * @param params.modelName 可选：按入口模型名过滤
   * @param params.gatewayApiKeyId 可选：按网关 API Key 主键过滤（按 id 精确匹配）
   * @param params.apiKeyName 兼容旧版：按 API Key 名过滤（仅作旧数据兜底）
   */
  usageChart(params: { year?: number; month?: number; modelName?: string; gatewayApiKeyId?: number; apiKeyName?: string } = {}) {
    const query: Record<string, string | number> = {}
    if (params.year != null) query.year = params.year
    if (params.month != null) query.month = params.month
    if (params.modelName) query.modelName = params.modelName
    if (params.gatewayApiKeyId != null) query.gatewayApiKeyId = params.gatewayApiKeyId
    if (params.apiKeyName) query.apiKeyName = params.apiKeyName
    return http.get<LogUsageChart>('/logs/usage-chart', { params: query })
  }
}

/**
 * "使用历史"图表数据形状。
 * - days: 当月所有日期（yyyy-MM-dd），固定长度（28/29/30/31），便于前端稳定渲染 X 轴
 * - models: 当月出现过的入口模型，按总用量降序（前端可按顺序分配固定色板，保证 TopN 模型颜色稳定）
 * - values: model -> 长度为 days.length 的数组，按 days 顺序给出该日 token 用量
 * - maxValue: 当月所有 (date, model) 单元格中的最大值，用于 Y 轴自适应刻度
 * - totalValue: 当月所有单元格总和
 */
export interface LogUsageChart {
  year: number
  month: number
  days: string[]
  models: string[]
  values: Record<string, number[]>
  maxValue: number
  totalValue: number
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
