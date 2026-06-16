import http from './index'

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
  channelRank: { name: string; requests: number; success: number; avgTime: number }[]
  modelRank: { name: string; requests: number; success: number }[]
  recentLogs: { id: number; modelName: string; channelName: string; phase: string; createdAt: string }[]
}

export const dashboardApi = {
  getStats() {
    return http.get<DashboardStats>('/dashboard/stats')
  }
}
