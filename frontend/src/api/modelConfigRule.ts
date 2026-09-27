import http from './index'

/**
 * 模型配置规则：一条规则 = 正则 + 输入模态 + 上下文大小。
 * 两个字段都可选（至少填一项），留空表示不覆盖该维度。
 * 规则结果始终优先于 models.dev 数据文件的基线值。
 */
export interface ModelConfigRule {
  id?: number
  pattern: string
  /** 追加的输入模态，逗号分隔（如 text,image）；空字符串=不覆盖 */
  appendType: string
  /** 上下文窗口（tokens）；0=不覆盖 */
  contextLength: number
  createdAt?: string
  updatedAt?: string
}

export interface RuleTestResult {
  data: string
  matched: boolean
}

export interface RuleTestResponse {
  success: boolean
  data?: RuleTestResult[]
  /** 实际匹配到的真实渠道模型（channel_models.model_name） */
  matchedModels?: MatchedModel[]
  /** 参与测试的真实渠道模型总数 */
  totalModels?: number
  error?: string
}

export interface MatchedModel {
  modelName: string
  /** 最终生效的上下文大小（规则 > models.dev > null） */
  contextLength: number | null
  /** 该值的来源：rule=models.dev 优先的本地规则，catalog=models.dev 文件，none=未知 */
  contextSource: 'rule' | 'catalog' | 'none'
  /** 最终生效的输入模态 */
  input: string
  /** models.dev 文件提供的原始基线值，便于对比规则覆盖了什么 */
  catalogContextLength: number
  catalogInput: string
}

export interface ModelsDevStatus {
  success: boolean
  /** 是否启用本地缓存文件的定时更新 */
  enabled: boolean
  /** 本地数据文件路径 */
  file: string
  /** 下载地址（定时拉取写入 file） */
  sourceUrl: string
  /** 已加载的模型条数 */
  count: number
  /** 实际加载的文件路径 */
  path: string
  /** 文件修改时间 */
  updatedAt: string
  /** 本地加载时间 */
  loadedAt: string
  /** 最近一次加载/下载失败原因 */
  lastError: string
}

export const modelConfigRuleApi = {
  list() {
    return http.get<ModelConfigRule[]>('/model-config-rules')
  },
  create(rule: Partial<ModelConfigRule>) {
    return http.post<{ success: boolean; data?: ModelConfigRule; error?: string }>('/model-config-rules', rule)
  },
  update(id: number, rule: Partial<ModelConfigRule>) {
    return http.put<{ success: boolean; data?: ModelConfigRule; error?: string }>(`/model-config-rules/${id}`, rule)
  },
  delete(id: number) {
    return http.delete<{ success: boolean; data?: ModelConfigRule; error?: string }>(`/model-config-rules/${id}`)
  },
  test(pattern: string, testData: string[]) {
    return http.post<RuleTestResponse>('/model-config-rules/test', { pattern, testData })
  }
}

export const modelsDevApi = {
  status() {
    return http.get<ModelsDevStatus>('/models-dev/status')
  },
  /** 只重新读取本地文件（不联网） */
  reload() {
    return http.post<{ success: boolean; count?: number; error?: string }>('/models-dev/reload')
  },
  /** 下载最新数据写入本地文件，然后重新加载 */
  refresh() {
    return http.post<{ success: boolean; count?: number; sourceUrl?: string; error?: string }>('/models-dev/refresh')
  }
}
