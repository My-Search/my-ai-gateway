import http from './index'

export interface TodayTokenStats {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export interface MonthlyStats {
  requests: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  successRate: number
  avgResponseTime: number
  failCount: number
  prev: {
    requests: number
    totalTokens: number
    successRate: number
    avgResponseTime: number
    failCount: number
  }
}

export interface DashboardStats {
  todayRequests: number
  yesterdayRequests: number
  todaySuccess: number
  todayFail: number
  successRate: number
  avgResponseTime: number
  channelCount: number
  customModelCount: number
  apiKeyCount: number
  dailyTrend: { label: string; requests: number; success: number; fail: number }[]
  channelRank: { name: string; requests: number; success: number; avgTime: number; totalTokens: number }[]
  modelRank: { name: string; requests: number; success: number; totalTokens: number }[]
  channelModelRank: { name: string; channelName?: string; requests: number; success: number; totalTokens: number }[]
  recentLogs: { id: number; modelName: string; channelName: string; phase: string; createdAt: string }[]
  todayTokenStats: TodayTokenStats
  monthlyStats: MonthlyStats
}

export interface RankingPeriodParams {
  channelRankPeriod?: string
  modelRankPeriod?: string
  date?: string
}

export interface TodayTrendData {
  buckets: string[]          // ["00:00", "00:10", ..., "23:50"]
  mode: 'all' | 'entry' | 'channel'
  series: Record<string, number[]>   // { "success": [0, 0, ...], "fail": [0, 0, ...] } or { "模型名": [0, 0, ...] }
}

export const dashboardApi = {
  getStats(params?: RankingPeriodParams) {
    return http.get<DashboardStats>('/dashboard/stats', { params })
  },
  getTodayTrend(mode: 'all' | 'entry' | 'channel' = 'all', date?: string) {
    return http.get<TodayTrendData>('/dashboard/today-trend', { params: { mode, date } })
  }
}
