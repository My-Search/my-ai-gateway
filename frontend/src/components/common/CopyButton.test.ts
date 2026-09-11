import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import CopyButton from './CopyButton.vue'

/**
 * 回归测试：CopyButton 在 <form> 内必须不会触发原生表单提交。
 *
 * 背景：按钮曾缺少 type 属性（浏览器默认 submit），
 * 在渠道编辑页表单内点击「复制」会误触发保存并跳转回列表页。
 */
describe('CopyButton', () => {
  const global = { plugins: [createPinia()] }

  beforeEach(() => {
    // jsdom 没有 clipboard API，需要手动打桩
    Object.assign(navigator, {
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) }
    })
  })

  it('按钮 type 为 button，不会触发表单提交', () => {
    const wrapper = mount(CopyButton, {
      props: { text: 'sk-test' },
      global
    })
    expect(wrapper.find('button').attributes('type')).toBe('button')
  })

  it('在 form 内点击不会触发 submit 事件', async () => {
    const onSubmit = vi.fn()
    const wrapper = mount(
      {
        components: { CopyButton },
        template: `<form @submit.prevent="onSubmit"><CopyButton text="sk-test" /></form>`,
        methods: { onSubmit }
      },
      { global, attachTo: document.body }
    )

    await wrapper.find('button').trigger('click')
    await wrapper.vm.$nextTick()

    expect(onSubmit).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('点击后将文本写入剪贴板', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })

    const wrapper = mount(CopyButton, {
      props: { text: 'sk-abc123' },
      global
    })
    await wrapper.find('button').trigger('click')
    await wrapper.vm.$nextTick()

    expect(writeText).toHaveBeenCalledWith('sk-abc123')
  })
})
