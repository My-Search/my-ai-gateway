import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { LogTrace, RequestLog, LogSseCallbacks } from '@/api/log'

/**
 * 请求日志页性能重构的回归测试。
 *
 * 重点覆盖最容易静默失效的两处：
 *  1. traces 改为 shallowRef 后，SSE 推来的日志必须仍能驱动界面更新。
 *     这条路径上没有任何其他响应式 ref 发生变化，完全依赖 flushSseBuffer 里的 triggerRef；
 *  2. 行内展示文本（分组耗时、时间）改走预计算字段后取值仍正确。
 */

const listMock = vi.fn()
const usageChartMock = vi.fn()
const getRequestDataMock = vi.fn()
const cleanMock = vi.fn()

/** 测试中捕获 SSE 回调，用于手动灌入推送日志 */
let sseCallbacks: LogSseCallbacks | null = null

vi.mock('@/api/log', () => ({
  logApi: {
    list: (...a: unknown[]) => listMock(...a),
    usageChart: (...a: unknown[]) => usageChartMock(...a),
    getRequestData: (...a: unknown[]) => getRequestDataMock(...a),
    clean: (...a: unknown[]) => cleanMock(...a),
  },
  subscribeLogStream: (cb: LogSseCallbacks) => {
    sseCallbacks = cb
    return { close: () => { sseCallbacks = null } }
  },
}))

vi.mock('@/api/model', () => ({ modelApi: { list: vi.fn().mockResolvedValue({ data: [] }) } }))
vi.mock('@/api/apikey', () => ({ apikeyApi: { list: vi.fn().mockResolvedValue({ data: [] }) } }))
vi.mock('@/api/system', () => ({
  systemApi: { getConfig: vi.fn().mockResolvedValue({ data: { data: { request_data_save_level: 'info' } } }) },
}))

class IOMock {
  observe = vi.fn()
  unobserve = vi.fn()
  disconnect = vi.fn()
}
vi.stubGlobal('IntersectionObserver', IOMock)

import LogList from './List.vue'

function makeLog(over: Partial<RequestLog> & { id: number; traceId: string }): RequestLog {
  return { phase: 'start', createdAt: '2026-09-25T10:00:00Z', ...over }
}

function makeTrace(traceId: string, logs: RequestLog[], over: Partial<LogTrace> = {}): LogTrace {
  const sorted = [...logs].sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
  return {
    traceId,
    logs: sorted,
    retryCount: 0,
    successCount: 0,
    failCount: 0,
    modelName: 'gpt-4o',
    totalTimeMs: 0,
    startTime: sorted[0]?.createdAt,
    endTime: sorted[sorted.length - 1]?.createdAt,
    hasRequestData: false,
    ...over,
  }
}

function listResponse(traces: LogTrace[], hasMore = false) {
  return { data: { data: traces, total: traces.length, offset: 0, limit: 50, hasMore } }
}

async function mountPage() {
  const wrapper = mount(LogList, { global: { plugins: [createPinia()] } })
  await vi.waitFor(() => expect(listMock).toHaveBeenCalled())
  await wrapper.vm.$nextTick()
  return wrapper
}

/** 等着 80ms 的 SSE 防抖批次落盘 */
async function flushSse(wrapper: ReturnType<typeof mount>) {
  await new Promise((r) => setTimeout(r, 120))
  await wrapper.vm.$nextTick()
}

describe('请求日志页 - 性能重构回归', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sseCallbacks = null
    listMock.mockReset()
    usageChartMock.mockReset()
    getRequestDataMock.mockReset()
    cleanMock.mockReset()
    usageChartMock.mockResolvedValue({
      data: { year: 2026, month: 9, days: [], models: [], tokenValues: {}, requestValues: {}, maxValue: 0, totalValue: 0 },
    })
  })

  it('列表数据渲染出 trace 行，展示时间与模型取自预计算/接口字段', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([makeTrace('t1', [makeLog({ id: 1, traceId: 't1', phase: 'success', responseTimeMs: 120 })])]),
    )
    const wrapper = await mountPage()

    expect(wrapper.findAll('.log-trace').length).toBe(1)
    expect(wrapper.find('.trace-time').text()).not.toBe('')
    expect(wrapper.find('.model-tag').text()).toBe('gpt-4o')
  })

  it('SSE 追加日志到已存在且仍在进行中的 trace 时，界面必须更新（依赖 flushSseBuffer 的 triggerRef）', async () => {
    // 已有一条"进行中"的 trace（无 success/fail），且开屏时已自动展开
    listMock.mockResolvedValueOnce(
      listResponse([makeTrace('t1', [makeLog({ id: 1, traceId: 't1', phase: 'start' })])]),
    )
    const wrapper = await mountPage()
    expect(wrapper.findAll('.log-entry').length).toBe(1)

    // 预热：第一条推送会把 sseConnected 由 false 翻成 true，那次渲染有额外的响应式来源，
    // 不能用来检验 triggerRef。这条 start 与已有 start 同组，明细行数不变。
    sseCallbacks!.onLog!(
      makeLog({ id: 2, traceId: 't1', phase: 'start', createdAt: '2026-09-25T10:00:02Z' }),
    )
    await flushSse(wrapper)
    expect(wrapper.findAll('.log-entry').length).toBe(1)

    // 真正的检验：此时 sseConnected / sseReconnecting / 展开集合都不再变化，
    // 界面刷新完全依赖 flushSseBuffer 末尾的 triggerRef。
    sseCallbacks!.onLog!(
      makeLog({ id: 3, traceId: 't1', phase: 'retry', createdAt: '2026-09-25T10:00:05Z', responseTimeMs: 42 }),
    )
    await flushSse(wrapper)

    const entries = wrapper.findAll('.log-entry')
    expect(entries.length).toBe(2)
    // retry 行带 42ms 耗时，来自 group.durationText 预计算
    expect(entries[1].text()).toContain('42ms')
  })

  it('SSE 推来新 trace 时插入列表并渲染', async () => {
    listMock.mockResolvedValueOnce(listResponse([]))
    const wrapper = await mountPage()
    expect(wrapper.findAll('.log-trace').length).toBe(0)

    // 预热：让 sseConnected 的状态翻转先发生，后续断言才只依赖 triggerRef
    sseCallbacks!.onLog!(makeLog({ id: 1, traceId: 'warm', phase: 'start' }))
    await flushSse(wrapper)
    expect(wrapper.findAll('.log-trace').length).toBe(1)

    sseCallbacks!.onLog!(makeLog({ id: 2, traceId: 'new-trace', phase: 'start', modelName: 'claude-3' }))
    await flushSse(wrapper)

    const rows = wrapper.findAll('.log-trace')
    expect(rows.length).toBe(2)
    // 新 trace 按时间倒序排在最前
    expect(rows[0].find('.model-tag').text()).toBe('claude-3')
  })

  it('展开 trace 后明细行展示预计算的耗时文本', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          makeLog({ id: 1, traceId: 't1', phase: 'start' }),
          makeLog({ id: 2, traceId: 't1', phase: 'success', responseTimeMs: 88, createdAt: '2026-09-25T10:00:01Z' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    expect(wrapper.findAll('.log-entry').length).toBe(0)

    await wrapper.find('.log-trace').trigger('click')
    await wrapper.vm.$nextTick()

    const entries = wrapper.findAll('.log-entry')
    expect(entries.length).toBe(2)
    expect(entries[1].text()).toContain('88ms')
    expect(entries[1].find('.log-time').text()).not.toBe('')
  })
})
