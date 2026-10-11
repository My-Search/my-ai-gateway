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
  it('粘性命中的成员在日志行显示「粘性」徽章，未标记的行不显示', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          makeLog({ id: 1, traceId: 't1', phase: 'start', channelName: 'ch-a', routeSource: 'sticky' }),
          makeLog({ id: 2, traceId: 't1', phase: 'success', channelName: 'ch-a', responseTimeMs: 50,
            createdAt: '2026-09-25T10:00:01Z', routeSource: 'sticky' }),
          // 未标记的候选（非粘性小组 / 直连关联）不应出现徽章。
          makeLog({ id: 3, traceId: 't1', phase: 'start', channelName: 'ch-b',
            createdAt: '2026-09-25T10:00:02Z' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.find('.log-trace').trigger('click')
    await wrapper.vm.$nextTick()

    // 命中成员的 start 与 success 两行都带徽章；ch-b 的行不带。
    const badges = wrapper.findAll('.route-source-badge')
    expect(badges.length).toBe(2)
    for (const b of badges) {
      expect(b.text()).toBe('粘性')
      expect(b.classes()).toContain('route-source-badge--sticky')
    }
    const entries = wrapper.findAll('.log-entry')
    expect(entries[0].find('.route-source-badge').exists()).toBe(true)
    expect(entries[entries.length - 1].find('.route-source-badge').exists()).toBe(false)
  })

  it('粘性回退用不同徽章区分，便于解释为何没命中粘性成员', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          makeLog({ id: 1, traceId: 't1', phase: 'success', channelName: 'ch-b', responseTimeMs: 60,
            routeSource: 'sticky_fallback' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.find('.log-trace').trigger('click')
    await wrapper.vm.$nextTick()

    const badge = wrapper.find('.route-source-badge')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('粘性回退')
    expect(badge.classes()).toContain('route-source-badge--sticky_fallback')
  })

  it('已跳过的候选即使带粘性回退标记，徽章也与「跳过」一致降为灰色', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          // 重试耗尽后以 skip 终结的回退候选：不应再用黄色强调。
          makeLog({ id: 1, traceId: 't1', phase: 'skip', channelName: 'ch-b',
            message: '重试耗尽跳过 ch-b', routeSource: 'sticky_fallback' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.vm.$nextTick()

    const badge = wrapper.find('.route-source-badge')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('粘性回退')
    expect(badge.classes()).toContain('route-source-badge--skip')
  })

  it('「请求开始」的候选最终被熔断跳过时，粘性回退徽章同样降为灰色', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          makeLog({ id: 1, traceId: 't1', phase: 'start', channelName: 'ch-b',
            routeSource: 'sticky_fallback' }),
          // 同一候选随后以「熔断跳过」终结；两组同徽章，最终组以 skip 呈灰色。
          makeLog({ id: 2, traceId: 't1', phase: 'skip', channelName: 'ch-b',
            message: '已熔断跳过 ch-b', createdAt: '2026-09-25T10:00:01Z',
            routeSource: 'sticky_fallback' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.vm.$nextTick()

    const badges = wrapper.findAll('.route-source-badge')
    expect(badges.length).toBe(2)
    // start 行仍是「正在回退」的活跃态：保持原色，不加 skip 修饰类。
    expect(badges[0].classes()).not.toContain('route-source-badge--skip')
    // 最终 skip 行降为灰色，与「跳过」PhaseBadge 同视觉优先级。
    expect(badges[1].classes()).toContain('route-source-badge--skip')
  })

  it('粘性命中的候选被熔断跳过时，徽章同样降为灰色', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          // 粘性命中的成员自己未承接成功，最终整行以 skip 终结。
          makeLog({ id: 1, traceId: 't1', phase: 'skip', channelName: 'ch-a',
            message: '模型级熔断跳过 ch-a', routeSource: 'sticky' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.vm.$nextTick()

    const badge = wrapper.find('.route-source-badge')
    expect(badge.text()).toBe('粘性')
    expect(badge.classes()).toContain('route-source-badge--sticky')
    expect(badge.classes()).toContain('route-source-badge--skip')
  })

  it('详情弹框里说明路由来源（粘性命中）', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          makeLog({ id: 1, traceId: 't1', phase: 'success', channelName: 'ch-a', message: '路由到 ch-a',
            routeSource: 'sticky' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.find('.log-trace').trigger('click')
    await wrapper.vm.$nextTick()

    await wrapper.find('.log-entry').trigger('click')
    const dialog = document.body.querySelector('.dialog-box')
    expect(dialog).not.toBeNull()
    expect(dialog!.textContent).toContain('粘性')
  })

  it('「请求开始」行显示入站协议徽章，候选行不显示', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          // 起始行带协议（真正承载 protocol 的行）。
          makeLog({ id: 1, traceId: 't1', phase: 'start', modelName: 'gpt-4o',
            protocol: 'anthropic-messages' }),
          // 候选/终态行不带 protocol，不应重复出现徽章。
          makeLog({ id: 2, traceId: 't1', phase: 'success', channelName: 'ch-a', responseTimeMs: 50,
            createdAt: '2026-09-25T10:00:01Z' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.find('.log-trace').trigger('click')
    await wrapper.vm.$nextTick()

    const badges = wrapper.findAll('.protocol-badge')
    expect(badges.length).toBe(1)
    expect(badges[0].text()).toBe('Anthropic Messages')
    expect(badges[0].attributes('title')).toContain('Anthropic Messages')

    const entries = wrapper.findAll('.log-entry')
    expect(entries[0].find('.protocol-badge').exists()).toBe(true)
    expect(entries[entries.length - 1].find('.protocol-badge').exists()).toBe(false)
  })

  it('无 protocol 的历史行不显示协议徽章', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          makeLog({ id: 1, traceId: 't1', phase: 'start', modelName: 'gpt-4o' }),
          makeLog({ id: 2, traceId: 't1', phase: 'success', channelName: 'ch-a', responseTimeMs: 50,
            createdAt: '2026-09-25T10:00:01Z' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.find('.log-trace').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.protocol-badge').exists()).toBe(false)
  })

  it('协议 slug 未知时原样回退显示，保证新增协议不消失', async () => {
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          makeLog({ id: 1, traceId: 't1', phase: 'start', modelName: 'gpt-4o',
            protocol: 'brand-new-proto' as never }),
          makeLog({ id: 2, traceId: 't1', phase: 'success', channelName: 'ch-a', responseTimeMs: 50,
            createdAt: '2026-09-25T10:00:01Z' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.find('.log-trace').trigger('click')
    await wrapper.vm.$nextTick()

    const badge = wrapper.find('.protocol-badge')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('brand-new-proto')
  })

  it('详情弹框里说明入站协议', async () => {
    // 前面用例的 Teleport 弹框会残留在 document.body，先清掉避免取到旧弹框。
    document.body.querySelectorAll('.dialog-box').forEach(el => el.remove())
    listMock.mockResolvedValueOnce(
      listResponse([
        makeTrace('t1', [
          makeLog({ id: 1, traceId: 't1', phase: 'start', modelName: 'gpt-4o', message: '请求开始',
            protocol: 'openai-responses' }),
          makeLog({ id: 2, traceId: 't1', phase: 'success', channelName: 'ch-a', responseTimeMs: 50,
            createdAt: '2026-09-25T10:00:01Z' }),
        ]),
      ]),
    )
    const wrapper = await mountPage()
    await wrapper.find('.log-trace').trigger('click')
    await wrapper.vm.$nextTick()

    // 点击带 message 的起始行（首行），打开详情弹框。
    await wrapper.find('.log-entry').trigger('click')
    const dialog = document.body.querySelector('.dialog-box')
    expect(dialog).not.toBeNull()
    expect(dialog!.textContent).toContain('OpenAI Responses')
  })
})
