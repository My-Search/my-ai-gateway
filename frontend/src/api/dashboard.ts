import http from './index'

export interface TodayTokenStats {
  promptTokens: number
  completionTokens: number
  totalTokens: number
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
  dailyTrend: { label: string; requests: number }[]
  channelRank: { name: string; requests: number; success: number; avgTime: number; totalTokens: number }[]
  modelRank: { name: string; requests: number; success: number; totalTokens: number }[]
  channelModelRank: { name: string; channelName?: string; requests: number; success: number; totalTokens: number }[]
  recentLogs: { id: number; modelName: string; channelName: string; phase: string; createdAt: string }[]
  todayTokenStats: TodayTokenStats
}

export const dashboardApi = {
  getStats() {
    return http.get<DashboardStats>('/dashboard/stats')
  }
}
