import http from './index'

export interface SystemConfig {
  log_retention_days: string
  log_cleanup_enabled: string
  request_body_ttl_hours: string
  retry_fail_ttl_hours: string
  request_data_save_level: string
  timeout_min_seconds: string
  timeout_max_seconds: string
}

export const systemApi = {
  /** 获取系统配置 */
  getConfig() {
    return http.get<{ success: boolean; data: SystemConfig }>('/config/system')
  },
  /** 更新系统配置 */
  updateConfig(data: Partial<SystemConfig>) {
    return http.put<{ success: boolean; data?: SystemConfig; error?: string }>('/config/system', data)
  }
}
