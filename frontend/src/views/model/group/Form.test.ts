import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'

/**
 * 小组表单的类型互转测试。
 *
 * 编辑态允许手动 ↔ 动态互转：切回动态时要恢复休眠保留的匹配规则
 * （往返切换不丢），切换时显示互转后果提示，保存载荷携带转换后的类型与规则。
 */

const getMock = vi.fn()
const updateMock = vi.fn()

vi.mock('@/api/group', () => ({
  groupApi: {
    get: (...a: unknown[]) => getMock(...a),
    update: (...a: unknown[]) => updateMock(...a),
    create: vi.fn().mockResolvedValue({ data: { success: true } }),
  },
}))

// 详情页跳转可携带 ?focus=rule：测试里动态改写 query
let routeQuery: Record<string, string> = {}

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '7' }, query: routeQuery }),
  useRouter: () => ({ push: vi.fn() }),
}))

import Form from './Form.vue'

/** 已挂载的组件：用例结束后统一卸载。 */
const mounted: Array<{ unmount: () => void }> = []

afterEach(() => {
  while (mounted.length) mounted.pop()?.unmount()
})

async function mountForm(group: Record<string, unknown>, attach = false) {
  getMock.mockResolvedValue({ data: { group } })
  const wrapper = mount(Form, {
    global: { plugins: [createPinia()] },
    // focus=rule 用例需要 document.getElementById 命中，须挂到 document
    attachTo: attach ? document.body : undefined,
  })
  mounted.push(wrapper)
  await flushPromises()
  await wrapper.vm.$nextTick()
  return wrapper
}

/** 类型区的两张卡片：[手动小组, 动态小组]。 */
function typeCards(wrapper: ReturnType<typeof mount>) {
  const section = wrapper.find('#grp-type')
  return section.findAll('button.strategy-card')
}

describe('小组类型互转', () => {
  it('编辑态类型卡片不被禁用', async () => {
    const wrapper = await mountForm({ id: 7, name: 'DF', type: 'manual', pattern: '', strategy: 'sticky' })
    for (const card of typeCards(wrapper)) {
      expect(card.attributes('disabled')).toBeUndefined()
    }
  })

  it('手动切动态时恢复休眠规则并显示互转提示；往返切换不丢失', async () => {
    const wrapper = await mountForm({ id: 7, name: 'DF', type: 'manual', pattern: '/glm/ flash', strategy: 'sticky' })

    // 切到动态：休眠规则自动恢复到输入框。
    const cards = typeCards(wrapper)
    await cards[1].trigger('click')
    await wrapper.vm.$nextTick()
    const input = wrapper.find('.pattern-row input')
    expect(input.exists()).toBe(true)
    expect((input.element as HTMLInputElement).value).toBe('/glm/ flash')
    expect(wrapper.find('.type-change-tip').text()).toContain('按匹配规则重建')

    // 切回手动：回到原始类型，提示消失；再切回动态时规则仍在（往返保留）。
    await typeCards(wrapper)[0].trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.pattern-row input').exists()).toBe(false)
    expect(wrapper.find('.type-change-tip').exists()).toBe(false)
    await typeCards(wrapper)[1].trigger('click')
    await wrapper.vm.$nextTick()
    expect((wrapper.find('.pattern-row input').element as HTMLInputElement).value).toBe('/glm/ flash')
  })

  it('动态小组切手动时显示休眠保留提示', async () => {
    const wrapper = await mountForm({ id: 7, name: 'DF', type: 'rule', pattern: 'deepseek', strategy: 'sticky' })
    await typeCards(wrapper)[0].trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.type-change-tip').text()).toContain('休眠')
  })

  it('互转后保存：载荷携带动态类型与保留的规则', async () => {
    updateMock.mockResolvedValue({ data: { success: true } })
    const wrapper = await mountForm({ id: 7, name: 'DF', type: 'manual', pattern: '/glm/ flash', strategy: 'sticky' })

    await typeCards(wrapper)[1].trigger('click')
    await wrapper.vm.$nextTick()
    const btns = wrapper.findAll('.footer-actions button')
    const saveBtn = btns[btns.length - 1]
    expect((saveBtn.element as HTMLButtonElement).disabled).toBe(false)
    await saveBtn.trigger('click')
    await flushPromises()

    expect(updateMock).toHaveBeenCalledWith(7, expect.objectContaining({ type: 'rule', pattern: '/glm/ flash' }))
  })

  it('手动小组没有休眠规则时切动态：保存按钮因缺少规则被禁用', async () => {
    const wrapper = await mountForm({ id: 7, name: 'DF', type: 'manual', pattern: '', strategy: 'sticky' })
    await typeCards(wrapper)[1].trigger('click')
    await wrapper.vm.$nextTick()
    const btns = wrapper.findAll('.footer-actions button')
    const saveBtn = btns[btns.length - 1]
    expect((saveBtn.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('?focus=rule 跳转：定位到类型区、聚焦规则输入框并闪烁高亮', async () => {
    // jsdom 未实现 scrollIntoView
    const scrollSpy = vi.fn()
    ;(Element.prototype as any).scrollIntoView = scrollSpy
    routeQuery = { focus: 'rule' }
    const wrapper = await mountForm(
      { id: 7, name: 'DF', type: 'rule', pattern: 'deepseek', strategy: 'sticky' },
      true,
    )
    expect(scrollSpy).toHaveBeenCalled()
    expect(wrapper.find('.pattern-flash').exists()).toBe(true)
    expect(document.activeElement).toBe(wrapper.find('.pattern-row input').element)
    routeQuery = {}
  })
})
