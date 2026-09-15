import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const getTodayTrend = vi.fn()

vi.mock('@/api/dashboard', () => ({
  dashboardApi: {
    getTodayTrend: (...args: unknown[]) => getTodayTrend(...args),
  },
}))

// echarts 在 jsdom 下无 canvas，全部打桩
const chartMock = {
  setOption: vi.fn(),
  resize: vi.fn(),
  clear: vi.fn(),
  dispose: vi.fn(),
}
vi.mock('echarts', () => ({
  init: () => chartMock,
  graphic: { LinearGradient: class { constructor() {} } },
}))

/** 从最近一次 setOption 的 option 中读出 xAxis 数据，代表当前真正画在图上的内容 */
function lastPlottedBuckets(): string[] {
  const calls = chartMock.setOption.mock.calls
  if (!calls.length) return []
  const opt = calls[calls.length - 1][0] as { xAxis?: { data?: string[] } }
  return opt?.xAxis?.data ?? []
}

import TodayTrendChart from './TodayTrendChart.vue'

const res = (tag: string) => Promise.resolve({
  data: { range: { key: tag }, buckets: [tag], bucketUnit: '1d', mode: 'entry', series: { a: [1] } },
})

// 打开模式下拉并点选第 idx 个选项（0=all, 1=entry, 2=channel）
async function pickMode(wrapper: ReturnType<typeof mount>, idx: number) {
  await wrapper.find('button.mode-trigger').trigger('click')
  await wrapper.vm.$nextTick()
  const opts = wrapper.findAll('.mode-option')
  await opts[idx].trigger('click')
  await wrapper.vm.$nextTick()
}

describe('TodayTrendChart loading & race guard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getTodayTrend.mockReset()
    chartMock.setOption.mockClear()
  })

  it('切换模式时显示 loading，请求返回后消失（加载中不能继续显示旧图）', async () => {
    getTodayTrend.mockReturnValueOnce(res('init'))
    const wrapper = mount(TodayTrendChart, { props: { rangeKey: 'today' as const } })
    await vi.waitFor(() => expect(getTodayTrend).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(wrapper.find('.trend-loading').exists()).toBe(false))

    // 下一次请求挂起，模拟网络未返回
    let release: (v: unknown) => void = () => {}
    getTodayTrend.mockReturnValueOnce(new Promise((r) => { release = r }))
    await pickMode(wrapper, 0) // 切到 all

    expect(getTodayTrend).toHaveBeenCalledTimes(2)
    // 请求未返回期间必须处于 loading：用户看到的是加载态，而不是旧图
    expect(wrapper.find('.trend-loading').exists()).toBe(true)

    release({ data: { range: { key: 'x' }, buckets: ['b'], bucketUnit: '1d', mode: 'all', series: { success: [1], fail: [0] } } })
    await vi.waitFor(() => expect(wrapper.find('.trend-loading').exists()).toBe(false))
  })

  it('快速连续切换：先返回的过期响应不得覆盖后发起的结果（防脏读）', async () => {
    getTodayTrend.mockReturnValueOnce(res('init'))
    const wrapper = mount(TodayTrendChart, { props: { rangeKey: 'today' as const } })
    await vi.waitFor(() => expect(getTodayTrend).toHaveBeenCalledTimes(1))

    // 第 2 次（慢）+ 第 3 次（快）
    let releaseSlow: (v: unknown) => void = () => {}
    getTodayTrend.mockReturnValueOnce(new Promise((r) => { releaseSlow = r }))
    await pickMode(wrapper, 0) // fetch#2 (慢)
    expect(getTodayTrend).toHaveBeenCalledTimes(2)

    getTodayTrend.mockReturnValueOnce(res('fast'))
    await pickMode(wrapper, 2) // fetch#3 (快，且是最后一次)
    expect(getTodayTrend).toHaveBeenCalledTimes(3)

    await vi.waitFor(() => expect(wrapper.find('.trend-loading').exists()).toBe(false))
    // 图上已是 fetch#3 的数据
    expect(lastPlottedBuckets()).toEqual(['fast'])

    // 过期响应（fetch#2）迟到：不能把 loading 重新点亮，也不能打回旧数据
    releaseSlow({ data: { range: { key: 'stale' }, buckets: ['stale'], bucketUnit: '1d', mode: 'all', series: { success: [9], fail: [9] } } })
    await new Promise((r) => setTimeout(r, 50))
    expect(wrapper.find('.trend-loading').exists()).toBe(false)
    // 关键断言：图表内容必须仍是后发起的那次结果，未被过期响应覆盖
    expect(lastPlottedBuckets()).toEqual(['fast'])
  })

  it('切换时间段（rangeKey）同样会进入 loading 态', async () => {
    getTodayTrend.mockReturnValueOnce(res('init'))
    const wrapper = mount(TodayTrendChart, { props: { rangeKey: 'today' as const } })
    await vi.waitFor(() => expect(getTodayTrend).toHaveBeenCalledTimes(1))

    let release: (v: unknown) => void = () => {}
    getTodayTrend.mockReturnValueOnce(new Promise((r) => { release = r }))
    await wrapper.setProps({ rangeKey: 'week' })

    expect(getTodayTrend).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.trend-loading').exists()).toBe(true)

    release({ data: { range: { key: 'week' }, buckets: ['w'], bucketUnit: '1d', mode: 'entry', series: { a: [1] } } })
    await vi.waitFor(() => expect(wrapper.find('.trend-loading').exists()).toBe(false))
  })
})
