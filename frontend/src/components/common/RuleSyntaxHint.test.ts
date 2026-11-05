import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RuleSyntaxHint from './RuleSyntaxHint.vue'

/** 匹配规则写法提示：疑问图标默认收起，点击展开三种示例，点击外部收起。 */
describe('RuleSyntaxHint', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => vi.restoreAllMocks())

  it('默认收起；点击后展示普通/正则/混合三种写法', async () => {
    const wrapper = mount(RuleSyntaxHint, { attachTo: document.body })
    expect(wrapper.find('.rule-syntax-pop').exists()).toBe(false)
    expect(wrapper.find('.rule-syntax-trigger').attributes('aria-expanded')).toBe('false')

    await wrapper.find('.rule-syntax-trigger').trigger('click')
    const pop = wrapper.find('.rule-syntax-pop')
    expect(pop.exists()).toBe(true)
    // 示例贴合实际渠道配置：真实模型名/渠道名，且每条都给出命中样例
    expect(pop.text()).toContain('deepseek flash')
    expect(pop.text()).toContain('/gpt-4o|claude/')
    expect(pop.text()).toContain('/workbuddy|trae/ deepseek')
    expect(pop.text()).toContain('deepseek-v4.1-flash (workbuddy)')
    expect(wrapper.find('.rule-syntax-trigger').attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })

  it('点击图标外任意位置收起', async () => {
    const wrapper = mount(RuleSyntaxHint, { attachTo: document.body })
    await wrapper.find('.rule-syntax-trigger').trigger('click')
    expect(wrapper.find('.rule-syntax-pop').exists()).toBe(true)

    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.rule-syntax-pop').exists()).toBe(false)
    wrapper.unmount()
  })
})
