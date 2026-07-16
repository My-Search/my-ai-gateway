import http from './index'

/**
 * 流式聊天（SSE）
 * - 管理端 Playground：根据协议调用实际的 API 端点（/v1/chat/completions 或 /v1/messages），
 *   使用 API Key 值进行认证（模拟真实客户端调用）
 * - 分享模式：URL 携带 shareCode 鉴权
 *
 * 返回 fetch Response，调用方自行解析 SSE 事件流
 */
export function chatStream(
  body: unknown,
  isShareMode: boolean,
  shareCode?: string,
  /** 管理端 Playground：API 协议类型 */
  protocol?: 'openai' | 'anthropic',
  /** 管理端 Playground：API Key 明文值（用于认证头） */
  apiKeyValue?: string
): Promise<Response> {
  let url: string
  const headers: Record<string, string> = {
    'Content-Type': 'application/json; charset=utf-8'
  }

  if (isShareMode) {
    url = `/api/share/chat/stream?shareCode=${shareCode}`
  } else if (protocol === 'anthropic') {
    // Anthropic 兼容端点
    url = '/v1/messages'
    if (apiKeyValue) {
      headers['x-api-key'] = apiKeyValue
    }
    headers['anthropic-version'] = '2023-06-01'
    // Playground 内部调用标记，后端据此发送 _gateway_meta 等内部事件
    headers['X-Internal-Client'] = 'playground'
  } else {
    // OpenAI 兼容端点（默认）
    url = '/v1/chat/completions'
    if (apiKeyValue) {
      headers['Authorization'] = `Bearer ${apiKeyValue}`
    }
    // Playground 内部调用标记，后端据此发送 _gateway_meta 等内部事件
    headers['X-Internal-Client'] = 'playground'
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
