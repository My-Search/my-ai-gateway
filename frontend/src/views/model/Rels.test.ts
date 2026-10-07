import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ModelChannelRel, ModelGroupRel } from '@/api/model'

/**
 * 入口模型关联页「模型小组行」的渲染测试。
 *
 * 小组行必须与渠道模型行保持相同列式布局：
 *  - 渠道列 = 小组徽章（禁用时附加禁用徽章）；
 *  - 模型列 = 成员模型名逐行 + 右侧堆叠的粘性徽章 / 策略文字；
 *  - 熔断列 = 状态与熔断成员占比合并为「状态（n/m）」，n = 熔断成员数、m = 成员总数；
 *  - 输入列 = 可路由成员聚合模态；上下文列 = 最大正值上下文；
 *  - TTFT/速度/思考强度 = --（小组没有对应数据）；
 *  - 操作列 = 删除（继承模式为 --）。
 */

const getRelsMock = vi.fn()

vi.mock('@/api/model', () => ({
  modelApi: {
    getRels: (...a: unknown[]) => getRelsMock(...a),
    getInheritableModels: vi.fn().mockResolvedValue({ data: [] }),
    batchUpdateSortOrders: vi.fn().mockResolvedValue({ data: { success: true } }),
    batchRemoveRels: vi.fn().mockResolvedValue({ data: { success: true } }),
    removeRel: vi.fn().mockResolvedValue({ data: { success: true } }),
    updateRelReasoningEffort: vi.fn().mockResolvedValue({ data: { success: true } }),
    clearRelCircuitBreaker: vi.fn().mockResolvedValue({ data: { success: true } }),
  },
}))

vi.mock('@/api/group', () => ({
  groupApi: {
    addModelRel: vi.fn().mockResolvedValue({ data: { success: true } }),
    removeModelRel: vi.fn().mockResolvedValue({ data: { success: true } }),
    batchRemoveModelRels: vi.fn().mockResolvedValue({ data: { success: true } }),
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

import Rels from './Rels.vue'

function makeRel(over: Partial<ModelChannelRel> & { id: number }): ModelChannelRel {
  return {
    modelId: 1,
    channelModelId: over.id,
    sortOrder: 0,
    channelName: '渠道' + over.id,
    channelModelName: 'model-' + over.id,
    channelEnabled: 1,
    apiKeyAvailable: 1,
    ...over,
  } as ModelChannelRel
}

function makeGroupRel(over: Partial<ModelGroupRel> & { id: number; groupId: number }): ModelGroupRel {
  return {
    modelId: 1,
    sortOrder: 0,
    groupName: 'DF',
    groupStrategy: 'random',
    groupSticky: 1,
    groupEnabled: 1,
    memberCount: 2,
    availableCount: 2,
    brokenCount: 0,
    input: 'text,image',
    maxContextLength: 2000000,
    memberModelNames: ['model-a', 'model-b'],
    ...over,
  } as ModelGroupRel
}

/** 测试里预设了 getRelsMock 时跳过默认 payload（inherit 用例需要自定义 relMode）。 */
let getRelsPreset = false

async function mountRels(rels: ModelChannelRel[], groupRels: ModelGroupRel[]) {
  if (!getRelsPreset) {
    getRelsMock.mockResolvedValue(relsPayload(rels, groupRels, 'self_add'))
  }
  getRelsPreset = false
  const wrapper = mount(Rels, { global: { plugins: [createPinia()] } })
  await flushPromises()
  await wrapper.vm.$nextTick()
  return wrapper
}

function relsPayload(rels: ModelChannelRel[], groupRels: ModelGroupRel[], relMode: string) {
  return {
    data: {
      model: { id: 1, modelName: 'entry', relMode, ...(relMode === 'inherit' ? { inheritFromModelId: 5 } : {}) },
      rels,
      groupRels,
      availableModels: [],
      availableGroups: [],
      inheritFromModelName: relMode === 'inherit' ? 'parent' : null,
    },
  }
}

function groupRow(wrapper: ReturnType<typeof mount>) {
  const row = wrapper.find('tr.row-group')
  expect(row.exists()).toBe(true)
  return row
}

describe('入口模型关联页 - 小组行渲染', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getRelsMock.mockReset()
  })

  it('小组行与渠道模型行同为 9 个数据列，且聚合值落在正确的列', async () => {
    const wrapper = await mountRels(
      [makeRel({ id: 11, sortOrder: 0 })],
      [makeGroupRel({ id: 2, groupId: 7, sortOrder: 1 })]
    )
    const row = groupRow(wrapper)
    // 排序 + 类型 + 名称 + 8 个数据/操作列（无多选列）
    expect(row.findAll('td').length).toBe(11)

    const tds = row.findAll('td')
    // 类型列：小组徽章
    expect(tds[1].text()).toContain('小组')
    // 名称列：组名
    expect(tds[2].text()).toContain('DF')
    // 模型列：成员模型名一行一个 + 右侧粘性徽章与策略文字
    expect(tds[3].text()).toContain('model-a')
    expect(tds[3].text()).toContain('model-b')
    expect(tds[3].text()).toContain('粘性')
    expect(tds[3].text()).toContain('随机')
    // 输入列：聚合模态
    expect(tds[4].text()).toContain('text')
    expect(tds[4].text()).toContain('image')
    // 上下文列：最大正值上下文
    expect(tds[5].text()).toContain('2.0M')
    // 熔断列：状态与熔断成员占比合并展示（0 个熔断成员 → 正常（0/2））
    expect(tds[8].text()).toContain('正常')
    expect(tds[8].text()).toContain('（0/2）')
    // 思考强度：--（小组思考强度在成员上，不在关联上）
    expect(tds[9].text()).toBe('--')
    // 操作列：仅删除（管理入口移到渠道列的组名链接上）
    expect(tds[10].text()).toContain('删除')
    expect(tds[10].text()).not.toContain('管理成员')
    // 渠道列组名带下划线链接，可点击进入成员管理
    //（测试环境无 router 插件，router-link 不渲染为 <a>，按类名断言）
    const nameLink = tds[2].find('.group-name-link')
    expect(nameLink.exists()).toBe(true)
    expect(nameLink.text()).toContain('DF')
  })

  it('小组禁用 / 无可用成员 / 全未知上下文时的降级展示', async () => {
    const wrapper = await mountRels(
      [],
      [makeGroupRel({
        id: 3, groupId: 8, groupEnabled: 0, availableCount: 0, brokenCount: 0,
        input: undefined, maxContextLength: null, memberModelNames: [],
      })]
    )
    const tds = groupRow(wrapper).findAll('td')
    expect(tds[2].text()).toContain('禁用')
    // 无可路由成员 → 模型列 -- 占位；熔断成员数随熔断列展示
    expect(tds[3].text()).toContain('--')
    expect(tds[8].text()).toContain('（0/2）')
    // 聚合输入为空 → --
    expect(tds[4].text()).toBe('--')
    // 上下文全未知 → --
    expect(tds[5].text()).toBe('--')
    // 无性能样本 → 暂无数据；非全熔断 → 正常
    expect(tds[6].text()).toContain('暂无数据')
    expect(tds[7].text()).toContain('暂无数据')
    expect(tds[8].text()).toContain('正常')
  })

  it('可路由成员全部熔断时小组行显示熔断中（n/m）（无解除/详情按钮）', async () => {
    const wrapper = await mountRels(
      [],
      [makeGroupRel({ id: 5, groupId: 10, circuitBroken: 1, brokenCount: 2 })]
    )
    const tds = groupRow(wrapper).findAll('td')
    expect(tds[8].text()).toContain('熔断中（2/2）')
    // 没有解除按钮和探测详情问号 icon
    expect(tds[8].find('.cb-recover-btn').exists()).toBe(false)
    expect(tds[8].find('.cb-hint').exists()).toBe(false)
  })

  it('全熔断时按聚合级别显示熔断中（模型级/渠道级 n/m）', async () => {
    const cases: Array<{ scope: ModelGroupRel['circuitBrokenScope']; want: string }> = [
      { scope: 'model', want: '熔断中（模型级 2/2）' },
      { scope: 'channel', want: '熔断中（渠道级 2/2）' },
      { scope: 'both', want: '熔断中（渠道级+模型级 2/2）' },
    ]
    for (const c of cases) {
      const wrapper = await mountRels(
        [],
        [makeGroupRel({ id: 7, groupId: 12, circuitBroken: 1, circuitBrokenScope: c.scope, brokenCount: 2, memberCount: 2 })]
      )
      const tds = groupRow(wrapper).findAll('td')
      expect(tds[8].text()).toContain(c.want)
      wrapper.unmount()
    }
  })

  it('小组行性能列显示成员均值（TTFT/速度/样本数）', async () => {
    const wrapper = await mountRels(
      [],
      [makeGroupRel({ id: 6, groupId: 11, ttftMs: 1234, sampleCount: 12, outputSpeed: 45.6 })]
    )
    const tds = groupRow(wrapper).findAll('td')
    expect(tds[6].text()).toContain('1.23s')
    expect(tds[6].text()).toContain('(12)')
    expect(tds[7].text()).toContain('45.6')
    expect(tds[7].text()).toContain('tokens/s')
  })

  it('继承模式下小组行操作列为只读 --', async () => {
    getRelsMock.mockResolvedValue(relsPayload([], [makeGroupRel({ id: 4, groupId: 9 })], 'inherit'))
    getRelsPreset = true
    const wrapper = await mountRels([], [])
    const row = wrapper.find('tr.row-group')
    expect(row.exists()).toBe(true)
    const tds = row.findAll('td')
    expect(tds[10].text()).toBe('--')
  })
})
