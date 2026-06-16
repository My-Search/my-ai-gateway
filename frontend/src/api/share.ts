import axios from 'axios'
import http from './index'

// 分享 API 使用独立的 axios 实例，baseURL 为 /api/share
const shareHttp = axios.create({
  baseURL: '/api/share',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 响应拦截器
shareHttp.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const msg = error.response.data?.error || error.response.data?.message || `请求失败 (${error.response.status})`
      return Promise.reject(new Error(msg))
    }
    if (error.request) {
      return Promise.reject(new Error('网络错误，请检查服务器是否正常运行'))
    }
    return Promise.reject(error)
  }
)

export interface ModelInfo {
  id: number
  modelName: string
  description?: string
  channelTypes: string[]
}

export interface ShareData {
  id: number
  shareCode: string
  keyName: string
  keyValue: string
  keyValueMasked?: string
  baseUrl: string
  models: ModelInfo[]
  channelTypes: string[]
}

export interface ShareResponse {
  success: boolean
  error?: string
  data?: ShareData
}

export const shareApi = {
  /**
   * 获取 API 密钥分享信息（通过分享码）
   */
  getShareInfo(code: string) {
    return shareHttp.get<ShareResponse>(`/${code}`).then(res => {
      if (!res.data.success) {
        throw new Error(res.data.error || '获取分享信息失败')
      }
      return { data: res.data as unknown as ShareData }
    })
  },

  /**
   * 获取 API 密钥分享信息（通过密钥值）
   */
  getShareInfoByKey(keyValue: string) {
    return shareHttp.get<ShareResponse>(`/by-key/${encodeURIComponent(keyValue)}`).then(res => {
      if (!res.data.success) {
        throw new Error(res.data.error || '获取分享信息失败')
      }
      return { data: res.data as unknown as ShareData }
    })
  },

  /**
   * 切换密钥分享状态（开启/关闭分享）
   */
  toggleShare(id: number, shared: boolean) {
    return http.post<{ success: boolean }>(`/api-keys/${id}/toggle-share`, { shared })
  }
}
