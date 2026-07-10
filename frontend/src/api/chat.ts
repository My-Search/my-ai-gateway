import http from './index'

/**
 * 流式聊天（SSE）
 * - 管理端：自动附加 JWT Token（与 http 拦截器同源）
 * - 分享模式：URL 携带 shareCode 鉴权
 *
 * 返回 fetch Response，调用方自行解析 SSE 事件流
 */
export function chatStream(
  body: unknown,
  isShareMode: boolean,
  shareCode?: string
): Promise<Response> {
  const url = isShareMode
    ? `/api/share/chat/stream?shareCode=${shareCode}`
    : '/admin/api/chat/stream'

  const headers: Record<string, string> = {
    'Content-Type': 'application/json; charset=utf-8'
  }
  if (!isShareMode) {
    const token = localStorage.getItem('admin_token')
    if (token) {
      headers['Authorization'] = `Bearer ${token}`
    }
  }

  return fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body)
  })
}

/**
 * 非流式聊天
 * - 管理端：使用 axios（请求拦截器自动携带 JWT Token）
 * - 分享模式：URL 携带 shareCode 鉴权
 *
 * 返回 { ok, data, status }
 */
export async function chat(
  body: unknown,
  isShareMode: boolean,
  shareCode?: string
): Promise<{ ok: boolean; data: any; status: number }> {
  if (isShareMode) {
    const url = `/api/share/chat?shareCode=${shareCode}`
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(body)
    })
    const text = await response.text()
    let data: any
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
    return { ok: response.ok, data, status: response.status }
  }

  // 管理端使用 axios（拦截器自动处理 token 和 401）
  // 非流式请求可能涉及多候选重路由，后端总超时兜底 10 分钟，前端同步放宽
  const response = await http.post('/admin/api/chat', body, {
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    timeout: 600000
  })
  return { ok: true, data: response.data, status: response.status }
}
