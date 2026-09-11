import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ToastContainer from './ToastContainer.vue'
import { toasts, closeToast, useToast, type ToastType } from '@/composables/useToast'

/** 清空全局 toast 队列，避免用例间互相污染 */
function drainToasts() {
  while (toasts.value.length) closeToast(toasts.value[0].id)
}

describe('useToast', () => {
  beforeEach(drainToasts)
  afterEach(drainToasts)

  it('默认类型为 success', () => {
    const { showToast } = useToast()
    showToast('操作成功')
    expect(toasts.value[0].type).toBe('success')
  })

  it('支持显式指定 warning / error', () => {
    const { showToast } = useToast()
    showToast('即将过期', { type: 'warning' })
    showToast('复制失败', { type: 'error' })
    expect(toasts.value[0].type).toBe('warning')
    expect(toasts.value[1].type).toBe('error')
  })

  it('兼容旧的 isError 写法，映射为 error', () => {
    const { showToast } = useToast()
    showToast('旧写法')
    showToast('复制失败', { isError: true })
    expect(toasts.value[0].type).toBe('success')
    expect(toasts.value[1].type).toBe('error')
  })

  it('到期后自动移除', async () => {
    vi.useFakeTimers()
    const { showToast } = useToast()
    showToast('短暂提示', { duration: 1000 })
    expect(toasts.value).toHaveLength(1)
    vi.advanceTimersByTime(1000)
    expect(toasts.value).toHaveLength(0)
    vi.useRealTimers()
  })
})

describe('ToastContainer', () => {
  const global = { plugins: [createPinia()] }

  beforeEach(drainToasts)
  afterEach(drainToasts)

  it('渲染 data-type 属性，供样式区分配色', async () => {
    const { showToast } = useToast()
    showToast('成功', { type: 'success' })
    showToast('警告', { type: 'warning' })
    showToast('错误', { type: 'error' })

    const wrapper = mount(ToastContainer, { global })
    await wrapper.vm.$nextTick()

    const items = wrapper.findAll('.toast-item')
    expect(items).toHaveLength(3)
    expect(items.map(i => i.attributes('data-type'))).toEqual<ToastType[]>(['success', 'warning', 'error'])
  })

  it('进度条不再引用 --primary（浅色主题下会渲染成黑色）', async () => {
    const { showToast } = useToast()
    showToast('成功')
    const wrapper = mount(ToastContainer, { global })
    await wrapper.vm.$nextTick()

    // vitest 默认不注入 scoped 样式，改为读取 SFC 源码断言。
    // 用 ?raw 导入，避免依赖构建产物。
    const source = (await import('./ToastContainer.vue?raw')).default as string
    const progressRules = source.match(/\.toast-progress\s*\{[^}]*\}/g) ?? []

    expect(progressRules.length).toBeGreaterThan(0)
    for (const rule of progressRules) {
      expect(rule).not.toContain('--primary')
      expect(rule).toContain('--toast-accent')
    }
  })
})
