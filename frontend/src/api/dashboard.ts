import http from './index'

/** 时间段快捷选择：今日 / 本周(周一至今) / 本月(1日至今) / 自定义起止日期（含边界，上海时区） */
export type DashboardRangeKey = 'today' | 'week' | 'month' | 'custom'

export interface DashboardRangeParams {
  range?: DashboardRangeKey
  from?: string   // yyyy-MM-dd，仅 range=custom 时使用
  to?: string     // yyyy-MM-dd，仅 range=custom 时使用
}

export interface DashboardRange {
  key: string
  start: string   // 实际生效的起始日期（上海时区）
  end: string     // 实际生效的结束日期（含）
  prev: { start: string; end: string }
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
  buckets: string[]              // 单日 ["00:00".."23:50"]；多日 ["2026-09-01"..]
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
