// API Response Types
export interface ApiResponse<T = unknown> {
  success: boolean
  data?: T
  error?: string
  message?: string
}

// Channel Types
export interface Channel {
  id?: number
  name: string
  type: string
  baseUrl: string
  apiKey?: string
  priority: number
  enabled: boolean
  models?: string[]
  config?: Record<string, unknown>
  createdAt?: string
  updatedAt?: string
}

export interface ChannelListResponse {
  content: Channel[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

// Model Types
export interface CustomModel {
  id?: number
  name: string
  channelId: number
  channelName?: string
  modelId: string
  enabled: boolean
  priority: number
  config?: ModelConfig
  createdAt?: string
  updatedAt?: string
}

export interface ModelConfig {
  maxTokens?: number
  temperature?: number
  topP?: number
  systemPrompt?: string
}

export interface ModelListResponse {
  content: CustomModel[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

// API Key Types
export interface ApiKey {
  id?: number
  key: string
  name: string
  prefix: string
  enabled: boolean
  expiredAt?: string
  rateLimit?: number
  modelRestrictions?: string[]
  channelRestrictions?: number[]
  requestCount?: number
  createdAt?: string
  updatedAt?: string
}

export interface ApiKeyListResponse {
  content: ApiKey[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

// Log Types
export interface RequestLog {
  id: number
  apiKeyId: number
  apiKeyName: string
  channelId: number
  channelName: string
  modelId: string
  modelName: string
  phase: 'start' | 'retry' | 'reroute' | 'success' | 'fail'
  requestBody?: string
  responseBody?: string
  statusCode?: number
  errorMessage?: string
  duration?: number
  tokens?: number
  createdAt: string
}

export interface LogListResponse {
  content: RequestLog[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

// Dashboard Stats Types
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
  dailyTrend: DailyTrend[]
  channelRank: ChannelRank[]
  modelRank: ModelRank[]
  recentLogs: RecentLog[]
}

export interface DailyTrend {
  label: string
  requests: number
}

export interface ChannelRank {
  name: string
  requests: number
  success: number
  avgTime: number
}

export interface ModelRank {
  name: string
  requests: number
  success: number
}

export interface RecentLog {
  id: number
  modelName: string
  channelName: string
  phase: string
  createdAt: string
}

// Playground Types
export interface PlaygroundRequest {
  modelId: number
  channelId?: number
  messages: ChatMessage[]
  temperature?: number
  maxTokens?: number
  stream?: boolean
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
}

export interface PlaygroundResponse {
  id: string
  model: string
  choices: Choice[]
  usage?: Usage
  created: number
}

export interface Choice {
  index: number
  message: ChatMessage
  finishReason: string
}

export interface Usage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

// Circuit Breaker Types
export interface CircuitBreakerState {
  channelId: number
  channelName: string
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  failureCount: number
  successCount: number
  lastFailureTime?: string
  nextAttemptTime?: string
}

// Pagination
export interface PaginationParams {
  page?: number
  size?: number
  sort?: string
}

export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  first: boolean
  last: boolean
  empty: boolean
}