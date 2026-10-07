import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ModelGroupMember } from '@/api/group'

/**
 * 模型小组成员页的渲染测试。
 *
 * 该页与入口模型关联页（/admin/model/rels/:id）展示同一批渠道模型的熔断信息，
 * 两侧必须读起来一致：
 *  - 熔断列 = 「熔断中（级别）」徽章 + 探测气泡入口 + 解除按钮；正常时为「正常」；
 *  - 性能列 = 首字节平均时间（样本数）/ 生成速度；
 *  - 删除按钮必须能弹出确认框（曾因页面未渲染 Dialog 组件而点了没反应）。
 */

const getMock = vi.fn()
const removeMemberMock = vi.fn()
const clearBreakerMock = vi.fn()

vi.mock('@/api/group', () => ({
  groupApi: {
    get: (...a: unknown[]) => getMock(...a),
    removeMember: (...a: unknown[]) => removeMemberMock(...a),
    clearChannelModelCircuitBreaker: (...a: unknown[]) => clearBreakerMock(...a),
    updateMember: vi.fn().mockResolvedValue({ data: { success: true } }),
    addMembers: vi.fn().mockResolvedValue({ data: { success: true } }),
    updateMembersSort: vi.fn().mockResolvedValue({ data: { success: true } }),
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '1' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

import Detail from './Detail.vue'

function makeMember(over: Partial<ModelGroupMember> & { id: number }): ModelGroupMember {
  return {
    groupId: 1,
    channelModelId: over.id + 100,
    sortOrder: 0,
    weight: 1,
    channelName: '渠道' + over.id,
    channelModelName: 'model-' + over.id,
    channelEnabled: 1,
    apiKeyAvailable: 1,
    ...over,
  }
}

/** 已挂载的组件：用例结束后统一卸载，避免 Teleport 到 body 的对话框串到下一个用例。 */
const mounted: Array<{ unmount: () => void }> = []

async function mountDetail(members: ModelGroupMember[]) {
  getMock.mockResolvedValue({
    data: {
      group: { id: 1, name: 'DF', strategy: 'random', sticky: 1 },
      members,
      availableModels: [],
      usedByModels: [],
    },
  })
  const wrapper = mount(Detail, { global: { plugins: [createPinia()] } })
  mounted.push(wrapper)
  await flushPromises()
  await wrapper.vm.$nextTick()
  return wrapper
}

/** 取当前最新的对话框（body 中最后一个），避免命中未清理的历史节点。 */
function latestDialog(): HTMLElement | null {
  const dialogs = document.body.querySelectorAll('.dialog-box')
  return dialogs.length ? (dialogs[dialogs.length - 1] as HTMLElement) : null
}

/** 点击最新对话框的确认按钮。 */
function clickDialogConfirm() {
  const footer = latestDialog()?.querySelector('.dialog-footer')
  ;(footer?.lastElementChild as HTMLElement).click()
}

/** 首行各单元格（页面上只有一行时即该成员行）。 */
function rowCells(wrapper: ReturnType<typeof mount>) {
  const row = wrapper.findAll('tbody tr')[0]
  if (!row) throw new Error('no member row rendered')
  return row.findAll('td')
}

/** 首行的静态列按钮（删除按钮是行内最后一个操作按钮）。 */
function lastRowButton(wrapper: ReturnType<typeof mount>) {
  const row = wrapper.findAll('tbody tr')[0]
  if (!row) throw new Error('no member row rendered')
  const buttons = row.findAll('button')
  return buttons[buttons.length - 1]
}

describe('模型小组成员页', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getMock.mockReset()
    removeMemberMock.mockReset()
    clearBreakerMock.mockReset()
  })

  // Dialog 通过 Teleport 挂到 body，用例之间必须卸载组件，
  // 否则后续用例会命中上一个用例残留的对话框。
  afterEach(() => {
    mounted.splice(0).forEach(w => w.unmount())
  })

  it('熔断中的成员显示级别徽章、探测入口与解除按钮', async () => {
    const wrapper = await mountDetail([
      makeMember({ id: 1, circuitBroken: 1, circuitBrokenScope: 'channel' }),
    ])
    const tds = rowCells(wrapper)
    // 熔断列（第 9 列，索引 8）
    expect(tds[8].text()).toContain('熔断中')
    expect(tds[8].text()).toContain('渠道级')
    expect(tds[8].find('.cb-hint').exists()).toBe(true)
    expect(tds[8].find('.cb-recover-btn').exists()).toBe(true)
  })

  it('正常成员在熔断列显示「正常」且没有解除按钮', async () => {
    const wrapper = await mountDetail([makeMember({ id: 2 })])
    const tds = rowCells(wrapper)
    expect(tds[8].text()).toBe('正常')
    expect(tds[8].find('.cb-recover-btn').exists()).toBe(false)
  })

  it('性能列显示成员自身的 TTFT（含样本数）与生成速度', async () => {
    const wrapper = await mountDetail([
      makeMember({ id: 3, ttftMs: 1234, sampleCount: 12, outputSpeed: 45.6 }),
    ])
    const tds = rowCells(wrapper)
    expect(tds[6].text()).toContain('1.23s')
    expect(tds[6].text()).toContain('(12)')
    expect(tds[7].text()).toContain('45.6')
    expect(tds[7].text()).toContain('tokens/s')
  })

  it('无性能样本时两列都显示暂无数据', async () => {
    const wrapper = await mountDetail([makeMember({ id: 4, ttftMs: null, outputSpeed: null })])
    const tds = rowCells(wrapper)
    expect(tds[6].text()).toContain('暂无数据')
    expect(tds[7].text()).toContain('暂无数据')
  })

  it('点击删除弹出确认框（页面必须渲染 Dialog，否则点了没反应）', async () => {
    const wrapper = await mountDetail([makeMember({ id: 5 })])
    const deleteBtn = lastRowButton(wrapper)
    await deleteBtn.trigger('click')
    const dialog = latestDialog()
    expect(dialog).not.toBeNull()
    expect(dialog!.textContent).toContain('model-5')
  })

  it('确认删除后调用移除接口', async () => {
    removeMemberMock.mockResolvedValue({ data: { success: true } })
    const wrapper = await mountDetail([makeMember({ id: 6 })])
    await lastRowButton(wrapper).trigger('click')
    clickDialogConfirm()
    await flushPromises()
    expect(removeMemberMock).toHaveBeenCalledWith(6)
  })

  it('确认解除熔断后按渠道模型调用解除接口', async () => {
    clearBreakerMock.mockResolvedValue({ data: { success: true } })
    const wrapper = await mountDetail([
      makeMember({ id: 7, circuitBroken: 1, circuitBrokenScope: 'model' }),
    ])
    await wrapper.find('.cb-recover-btn').trigger('click')
    clickDialogConfirm()
    await flushPromises()
    // channelModelId = id + 100
    expect(clearBreakerMock).toHaveBeenCalledWith(107)
  })
})
