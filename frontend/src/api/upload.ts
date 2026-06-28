import http from './index'

export interface UploadResult {
  success: boolean
  url: string
  originalName: string
  error?: string
}

export const uploadApi = {
  /**
   * 上传图片文件
   * POST /admin/api/upload (multipart/form-data)
   * @param file 图片文件
   * @returns 上传后的 URL 等信息
   */
  async upload(file: File): Promise<{ data: UploadResult }> {
    const formData = new FormData()
    formData.append('file', file)
    return http.post('/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}
