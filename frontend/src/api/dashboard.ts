import http from './index'

/** 时间段快捷选择：今日 / 本周(周一至今) / 本月(1日至今) / 自定义起止时间（含边界，上海时区） */
export type DashboardRangeKey = 'today' | 'week' | 'month' | 'custom'

export interface DashboardRangeParams {
  range?: DashboardRangeKey
  /**
   * 起始时间，仅 range=custom 时使用。支持 "yyyy-MM-dd"（当天 00:00:00）
   * 或 "yyyy-MM-ddTHH:mm:ss"（精确到秒的墙钟时间，按上海时区解释）。
   */
  from?: string
  /** 结束时间，格式同 from；仅日期时表示含当天整天，带时间时含该秒。 */
  to?: string
}

export interface DashboardRange {
  key: string
  start: string   // 实际生效的起始日期（上海时区，yyyy-MM-dd）
  end: string     // 实际生效的结束日期（含）
  startAt: string // 实际生效的起始时刻（上海时区，yyyy-MM-ddTHH:mm:ss）
  endAt: string   // 实际生效的结束时刻（含该秒）
  prev: { start: string; end: string; startAt: string; endAt: string }
}

export interface DashboardTotals {
  requests: number
  success: number
  fail: number
  successRate: number
  avgResponseTime: number   // 首字节平均时间（毫秒）
  avgOutputSpeed: number    // 生成速度（tokens/s）
  totalTokens: number
}

export interface ChannelRankItem {
  name: string
  requests: number
  success: number
  totalTokens: number
  avgTime: number
}

export interface ModelRankItem {
  name: string
  requests: number
  success: number
  totalTokens: number
  avgTime: number
}

export interface ChannelModelRankItem extends ModelRankItem {
  channelName: string
}

/** 卡片迷你趋势线（每项 32 个采样点，跟随所选时间段） */
export interface DashboardSparklines {
  requests: number[]
  successRate: number[]
  avgResponseTime: number[]
  avgOutputSpeed: number[]
}

export interface DashboardStats {
  range: DashboardRange
  totals: DashboardTotals
  prevTotals: DashboardTotals   // 上一同期窗口（昨日/上周同期/上月同期/上一个等长区间）
  sparklines: DashboardSparklines
  channelRank: ChannelRankItem[]
  modelRank: ModelRankItem[]
  channelModelRank: ChannelModelRankItem[]
}

export interface TodayTrendData {
  range: DashboardRange
  /**
   * 分桶标签：窗口不超过 24h 时为桶起点的 "HH:mm"；
   * 超过 24h 时为桶起点的 "yyyy-MM-dd"（起点非零点时带 HH:mm）。
   */
  buckets: string[]
  bucketUnit: '10m' | '1d'
  mode: 'all' | 'entry' | 'channel'
  series: Record<string, number[]>   // { success:[...], fail:[...] } 或 { "模型名":[...] }
}

export const dashboardApi = {
  getStats(params?: DashboardRangeParams) {
    return http.get<DashboardStats>('/dashboard/stats', { params })
  },
  getTodayTrend(mode: 'all' | 'entry' | 'channel' = 'all', params?: DashboardRangeParams) {
    return http.get<TodayTrendData>('/dashboard/today-trend', { params: { mode, ...params } })
  }
}
